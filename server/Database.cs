using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace CamerAim.Server;

public sealed class Database
{
    private readonly string connectionString;
    public string DataDirectory { get; }
    public string ImageDirectory { get; }

    public Database(IWebHostEnvironment environment, IOptions<CamerAimOptions> options)
    {
        DataDirectory = Path.GetFullPath(options.Value.DataDirectory, environment.ContentRootPath);
        ImageDirectory = Path.Combine(DataDirectory, "images");
        Directory.CreateDirectory(DataDirectory);
        Directory.CreateDirectory(ImageDirectory);
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(DataDirectory, "cameraim.db"),
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            ForeignKeys = true
        }.ToString();
    }

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        await using SqliteConnection connection = await OpenAsync(cancellationToken);
        await using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;
            PRAGMA foreign_keys = ON;
            CREATE TABLE IF NOT EXISTS statuses (
                machine_id TEXT PRIMARY KEY,
                plugin_instance_id TEXT NOT NULL,
                observed_at TEXT NOT NULL,
                received_at TEXT NOT NULL,
                payload TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS jobs (
                machine_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                file_name TEXT NULL,
                state TEXT NOT NULL,
                started_at TEXT NOT NULL,
                ended_at TEXT NULL,
                final_image_id TEXT NULL,
                PRIMARY KEY (machine_id, run_id)
            );
            CREATE TABLE IF NOT EXISTS events (
                server_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                machine_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                run_id TEXT NULL,
                occurred_at TEXT NOT NULL,
                received_at TEXT NOT NULL,
                kind TEXT NOT NULL,
                detail TEXT NULL,
                UNIQUE (machine_id, event_id)
            );
            CREATE INDEX IF NOT EXISTS ix_events_machine_sequence
                ON events(machine_id, server_sequence);
            CREATE TABLE IF NOT EXISTS images (
                image_id TEXT PRIMARY KEY,
                machine_id TEXT NOT NULL,
                run_id TEXT NULL,
                captured_at TEXT NOT NULL,
                received_at TEXT NOT NULL,
                is_final INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                content_length INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ix_images_machine_captured
                ON images(machine_id, captured_at DESC);
            """;
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<SqliteConnection> OpenAsync(CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(connectionString);
        await connection.OpenAsync(cancellationToken);
        await using SqliteCommand command = connection.CreateCommand();
        command.CommandText = "PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON;";
        await command.ExecuteNonQueryAsync(cancellationToken);
        return connection;
    }
}
