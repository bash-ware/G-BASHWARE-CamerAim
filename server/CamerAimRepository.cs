using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace CamerAim.Server;

public sealed class CamerAimRepository
{
    private readonly Database database;
    private readonly CamerAimOptions options;

    public CamerAimRepository(Database database, IOptions<CamerAimOptions> options)
    {
        this.database = database;
        this.options = options.Value;
    }

    public async Task SaveStatusAsync(StatusUpdate update, CancellationToken cancellationToken)
    {
        string receivedAt = Format(DateTimeOffset.UtcNow);
        string payload = JsonSerializer.Serialize(update, CamerAimJsonContext.Default.StatusUpdate);
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using SqliteTransaction transaction = connection.BeginTransaction();

        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO statuses(machine_id, plugin_instance_id, observed_at, received_at, payload)
                VALUES($machine, $instance, $observed, $received, $payload)
                ON CONFLICT(machine_id) DO UPDATE SET
                    plugin_instance_id=excluded.plugin_instance_id,
                    observed_at=excluded.observed_at,
                    received_at=excluded.received_at,
                    payload=excluded.payload;
                """;
            command.Parameters.AddWithValue("$machine", update.MachineId);
            command.Parameters.AddWithValue("$instance", update.PluginInstanceId.ToString());
            command.Parameters.AddWithValue("$observed", Format(update.ObservedAt));
            command.Parameters.AddWithValue("$received", receivedAt);
            command.Parameters.AddWithValue("$payload", payload);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }

        if (update.Job is { } job)
        {
            await using SqliteCommand command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO jobs(machine_id, run_id, file_name, state, started_at, ended_at)
                VALUES($machine, $run, $file, $state, $started, $ended)
                ON CONFLICT(machine_id, run_id) DO UPDATE SET
                    file_name=COALESCE(excluded.file_name, jobs.file_name),
                    state=CASE
                        WHEN jobs.state IN ('completed', 'cancelled', 'alarm') THEN jobs.state
                        WHEN excluded.state IN ('completed', 'cancelled', 'alarm') THEN jobs.state
                        ELSE excluded.state
                    END,
                    started_at=jobs.started_at,
                    ended_at=jobs.ended_at;
                """;
            command.Parameters.AddWithValue("$machine", update.MachineId);
            command.Parameters.AddWithValue("$run", job.RunId.ToString());
            command.Parameters.AddWithValue("$file", (object?)job.FileName ?? DBNull.Value);
            bool terminalStatus = job.State is "completed" or "cancelled" or "alarm";
            command.Parameters.AddWithValue("$state", terminalStatus ? "unknown" : job.State);
            command.Parameters.AddWithValue("$started", Format(job.StartedAt));
            command.Parameters.AddWithValue("$ended", DBNull.Value);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }

        await transaction.CommitAsync(cancellationToken);
    }

    public async Task<(EventRecord Record, bool Created)> SaveEventAsync(
        EventInput input,
        CancellationToken cancellationToken)
    {
        DateTimeOffset receivedAt = DateTimeOffset.UtcNow;
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using SqliteTransaction transaction = connection.BeginTransaction();
        int inserted;
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                INSERT OR IGNORE INTO events(
                    machine_id, event_id, run_id, occurred_at, received_at, kind, detail)
                VALUES($machine, $event, $run, $occurred, $received, $kind, $detail);
                """;
            command.Parameters.AddWithValue("$machine", input.MachineId);
            command.Parameters.AddWithValue("$event", input.EventId.ToString());
            command.Parameters.AddWithValue("$run", input.RunId?.ToString() is { } run ? run : DBNull.Value);
            command.Parameters.AddWithValue("$occurred", Format(input.OccurredAt));
            command.Parameters.AddWithValue("$received", Format(receivedAt));
            command.Parameters.AddWithValue("$kind", input.Kind);
            command.Parameters.AddWithValue("$detail", (object?)input.Detail ?? DBNull.Value);
            inserted = await command.ExecuteNonQueryAsync(cancellationToken);
        }

        if (inserted == 1 && input.RunId is { } runId)
            await ApplyEventToJobAsync(connection, transaction, input, runId, cancellationToken);

        EventRecord record;
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                SELECT server_sequence, occurred_at, received_at, kind, detail, run_id
                FROM events WHERE machine_id=$machine AND event_id=$event;
                """;
            command.Parameters.AddWithValue("$machine", input.MachineId);
            command.Parameters.AddWithValue("$event", input.EventId.ToString());
            await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
                throw new InvalidOperationException("Event insert could not be read back.");
            record = ReadEvent(reader, input.MachineId, input.EventId);
        }

        await transaction.CommitAsync(cancellationToken);
        return (record, inserted == 1);
    }

    public async Task<IReadOnlyList<EventRecord>> GetEventsAsync(
        string machineId,
        long afterSequence,
        int limit,
        CancellationToken cancellationToken)
    {
        var result = new List<EventRecord>();
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            SELECT event_id, server_sequence, occurred_at, received_at, kind, detail, run_id
            FROM events
            WHERE machine_id=$machine AND server_sequence>$after
            ORDER BY server_sequence
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$machine", machineId);
        command.Parameters.AddWithValue("$after", afterSequence);
        command.Parameters.AddWithValue("$limit", limit);
        await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            Guid eventId = Guid.Parse(reader.GetString(0));
            result.Add(ReadEvent(reader, machineId, eventId, columnOffset: 1));
        }
        return result;
    }

    public async Task<ImageRecord> SaveImageAsync(
        ImageMetadata metadata,
        ReadOnlyMemory<byte> bytes,
        CancellationToken cancellationToken)
    {
        Guid imageId = Guid.NewGuid();
        DateTimeOffset receivedAt = DateTimeOffset.UtcNow;
        string fileName = $"{imageId:N}.jpg";
        string fullPath = Path.Combine(database.ImageDirectory, fileName);
        await File.WriteAllBytesAsync(fullPath, bytes.ToArray(), cancellationToken);
        try
        {
            await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
            await using SqliteTransaction transaction = connection.BeginTransaction();
            await using (SqliteCommand command = connection.CreateCommand())
            {
                command.Transaction = transaction;
                command.CommandText = """
                    INSERT INTO images(
                        image_id, machine_id, run_id, captured_at, received_at,
                        is_final, relative_path, content_length)
                    VALUES($id, $machine, $run, $captured, $received, $final, $path, $length);
                    """;
                command.Parameters.AddWithValue("$id", imageId.ToString());
                command.Parameters.AddWithValue("$machine", metadata.MachineId);
                command.Parameters.AddWithValue("$run", metadata.RunId?.ToString() is { } run ? run : DBNull.Value);
                command.Parameters.AddWithValue("$captured", Format(metadata.CapturedAt));
                command.Parameters.AddWithValue("$received", Format(receivedAt));
                command.Parameters.AddWithValue("$final", metadata.IsFinal ? 1 : 0);
                command.Parameters.AddWithValue("$path", fileName);
                command.Parameters.AddWithValue("$length", bytes.Length);
                await command.ExecuteNonQueryAsync(cancellationToken);
            }
            if (metadata.IsFinal && metadata.RunId is { } runId)
            {
                await using SqliteCommand update = connection.CreateCommand();
                update.Transaction = transaction;
                update.CommandText = """
                    UPDATE jobs SET final_image_id=$image
                    WHERE machine_id=$machine AND run_id=$run;
                    """;
                update.Parameters.AddWithValue("$image", imageId.ToString());
                update.Parameters.AddWithValue("$machine", metadata.MachineId);
                update.Parameters.AddWithValue("$run", runId.ToString());
                await update.ExecuteNonQueryAsync(cancellationToken);
            }
            await transaction.CommitAsync(cancellationToken);
        }
        catch
        {
            File.Delete(fullPath);
            throw;
        }

        return ToImageRecord(metadata, imageId, receivedAt, bytes.Length);
    }

    public async Task<(ImageRecord Record, string FullPath)?> GetImageAsync(
        Guid imageId,
        CancellationToken cancellationToken)
    {
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            SELECT machine_id, run_id, captured_at, received_at, is_final, relative_path, content_length
            FROM images WHERE image_id=$id;
            """;
        command.Parameters.AddWithValue("$id", imageId.ToString());
        await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return null;
        string relativePath = reader.GetString(5);
        var metadata = new ImageMetadata(
            "1.0",
            reader.GetString(0),
            reader.IsDBNull(1) ? null : Guid.Parse(reader.GetString(1)),
            Parse(reader.GetString(2)),
            reader.GetInt64(4) == 1);
        ImageRecord record = ToImageRecord(metadata, imageId, Parse(reader.GetString(3)), reader.GetInt64(6));
        return (record, Path.Combine(database.ImageDirectory, relativePath));
    }

    public async Task<MachineState?> GetStateAsync(string machineId, CancellationToken cancellationToken)
    {
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        StatusUpdate update;
        DateTimeOffset receivedAt;
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.CommandText = "SELECT payload, received_at FROM statuses WHERE machine_id=$machine;";
            command.Parameters.AddWithValue("$machine", machineId);
            await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken)) return null;
            update = JsonSerializer.Deserialize(reader.GetString(0), CamerAimJsonContext.Default.StatusUpdate)
                ?? throw new InvalidOperationException("Stored status JSON is invalid.");
            receivedAt = Parse(reader.GetString(1));
        }

        ImageRecord? latestImage = null;
        JobStatus? canonicalJob = update.Job;
        if (update.Job is { } reportedJob)
        {
            await using SqliteCommand command = connection.CreateCommand();
            command.CommandText = "SELECT state FROM jobs WHERE machine_id=$machine AND run_id=$run;";
            command.Parameters.AddWithValue("$machine", machineId);
            command.Parameters.AddWithValue("$run", reportedJob.RunId.ToString());
            object? storedState = await command.ExecuteScalarAsync(cancellationToken);
            if (storedState is string state)
                canonicalJob = reportedJob with { State = state };
        }
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT image_id, run_id, captured_at, received_at, is_final, content_length
                FROM images WHERE machine_id=$machine ORDER BY captured_at DESC LIMIT 1;
                """;
            command.Parameters.AddWithValue("$machine", machineId);
            await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                var metadata = new ImageMetadata(
                    "1.0",
                    machineId,
                    reader.IsDBNull(1) ? null : Guid.Parse(reader.GetString(1)),
                    Parse(reader.GetString(2)),
                    reader.GetInt64(4) == 1);
                latestImage = ToImageRecord(
                    metadata,
                    Guid.Parse(reader.GetString(0)),
                    Parse(reader.GetString(3)),
                    reader.GetInt64(5));
            }
        }

        long lastSequence;
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.CommandText = "SELECT COALESCE(MAX(server_sequence), 0) FROM events WHERE machine_id=$machine;";
            command.Parameters.AddWithValue("$machine", machineId);
            lastSequence = Convert.ToInt64(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
        }

        double ageSeconds = Math.Max(0, (DateTimeOffset.UtcNow - receivedAt).TotalSeconds);
        string freshness = ageSeconds >= options.OfflineAfterSeconds
            ? "offline"
            : ageSeconds >= options.StaleAfterSeconds ? "stale" : "fresh";
        return new MachineState(
            machineId,
            freshness,
            receivedAt,
            update.Connection,
            update.Camera,
            canonicalJob,
            update.Motion,
            latestImage,
            lastSequence);
    }

    public async Task<IReadOnlyList<JobRun>> GetJobsAsync(
        string machineId,
        int limit,
        CancellationToken cancellationToken)
    {
        var result = new List<JobRun>();
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            SELECT run_id, file_name, state, started_at, ended_at, final_image_id
            FROM jobs WHERE machine_id=$machine ORDER BY started_at DESC LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$machine", machineId);
        command.Parameters.AddWithValue("$limit", limit);
        await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            result.Add(new JobRun(
                machineId,
                Guid.Parse(reader.GetString(0)),
                reader.IsDBNull(1) ? null : reader.GetString(1),
                reader.GetString(2),
                Parse(reader.GetString(3)),
                reader.IsDBNull(4) ? null : Parse(reader.GetString(4)),
                reader.IsDBNull(5) ? null : Guid.Parse(reader.GetString(5))));
        }
        return result;
    }

    public async Task ApplyRetentionAsync(CancellationToken cancellationToken)
    {
        DateTimeOffset cutoff = DateTimeOffset.UtcNow.AddDays(-options.ImageRetentionDays);
        var images = new List<(Guid Id, string RelativePath, long Length, DateTimeOffset ReceivedAt)>();
        await using SqliteConnection connection = await database.OpenAsync(cancellationToken);
        await using (SqliteCommand command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT image_id, relative_path, content_length, received_at
                FROM images ORDER BY received_at DESC;
                """;
            await using SqliteDataReader reader = await command.ExecuteReaderAsync(cancellationToken);
            while (await reader.ReadAsync(cancellationToken))
            {
                images.Add((
                    Guid.Parse(reader.GetString(0)),
                    reader.GetString(1),
                    reader.GetInt64(2),
                    Parse(reader.GetString(3))));
            }
        }

        long retainedBytes = 0;
        foreach ((Guid id, string relativePath, long length, DateTimeOffset imageReceivedAt) in images)
        {
            bool expired = imageReceivedAt < cutoff;
            bool overBudget = retainedBytes + length > options.MaximumImageStorageBytes;
            if (!expired && !overBudget)
            {
                retainedBytes += length;
                continue;
            }

            await using SqliteTransaction transaction = connection.BeginTransaction();
            await using (SqliteCommand clearReference = connection.CreateCommand())
            {
                clearReference.Transaction = transaction;
                clearReference.CommandText = "UPDATE jobs SET final_image_id=NULL WHERE final_image_id=$id;";
                clearReference.Parameters.AddWithValue("$id", id.ToString());
                await clearReference.ExecuteNonQueryAsync(cancellationToken);
            }
            await using (SqliteCommand deleteImage = connection.CreateCommand())
            {
                deleteImage.Transaction = transaction;
                deleteImage.CommandText = "DELETE FROM images WHERE image_id=$id;";
                deleteImage.Parameters.AddWithValue("$id", id.ToString());
                await deleteImage.ExecuteNonQueryAsync(cancellationToken);
            }
            await transaction.CommitAsync(cancellationToken);
            string fullPath = Path.Combine(database.ImageDirectory, relativePath);
            if (File.Exists(fullPath)) File.Delete(fullPath);
        }
    }

    private static async Task ApplyEventToJobAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        EventInput input,
        Guid runId,
        CancellationToken cancellationToken)
    {
        string? state = input.Kind switch
        {
            "started" => "running",
            "paused" => "paused",
            "resumed" => "running",
            "completed" => "completed",
            "cancelled" => "cancelled",
            "alarm" => "alarm",
            _ => null
        };
        if (state is null) return;
        bool terminal = state is "completed" or "cancelled" or "alarm";
        await using SqliteCommand command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO jobs(machine_id, run_id, state, started_at, ended_at)
            VALUES($machine, $run, $state, $occurred, $ended)
            ON CONFLICT(machine_id, run_id) DO UPDATE SET
                state=excluded.state,
                ended_at=COALESCE(excluded.ended_at, jobs.ended_at);
            """;
        command.Parameters.AddWithValue("$machine", input.MachineId);
        command.Parameters.AddWithValue("$run", runId.ToString());
        command.Parameters.AddWithValue("$state", state);
        command.Parameters.AddWithValue("$occurred", Format(input.OccurredAt));
        command.Parameters.AddWithValue("$ended", terminal ? Format(input.OccurredAt) : DBNull.Value);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    private static EventRecord ReadEvent(
        SqliteDataReader reader,
        string machineId,
        Guid eventId,
        int columnOffset = 0)
    {
        int sequenceColumn = columnOffset;
        return new EventRecord(
            "1.0",
            eventId,
            machineId,
            reader.IsDBNull(sequenceColumn + 5) ? null : Guid.Parse(reader.GetString(sequenceColumn + 5)),
            Parse(reader.GetString(sequenceColumn + 1)),
            reader.GetString(sequenceColumn + 3),
            reader.IsDBNull(sequenceColumn + 4) ? null : reader.GetString(sequenceColumn + 4),
            reader.GetInt64(sequenceColumn),
            Parse(reader.GetString(sequenceColumn + 2)));
    }

    private static ImageRecord ToImageRecord(
        ImageMetadata metadata,
        Guid imageId,
        DateTimeOffset receivedAt,
        long contentLength) =>
        new(
            metadata.ApiVersion,
            imageId,
            metadata.MachineId,
            metadata.RunId,
            metadata.CapturedAt,
            metadata.IsFinal,
            receivedAt,
            contentLength,
            $"/api/v1/images/{imageId}");

    private static string Format(DateTimeOffset value) =>
        value.UtcDateTime.ToString("O", CultureInfo.InvariantCulture);

    private static DateTimeOffset Parse(string value) =>
        DateTimeOffset.Parse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal);
}
