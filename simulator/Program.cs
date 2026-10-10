using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

string serverUrl = ReadArgument("--url") ?? "http://127.0.0.1:5187";
string machineId = ReadArgument("--machine") ?? "simulator-cnc";
string? token = ReadArgument("--token")
    ?? Environment.GetEnvironmentVariable("CAMERAIM_INGEST_TOKEN")
    ?? ReadTokenFromCredentials();
if (string.IsNullOrWhiteSpace(token))
    throw new InvalidOperationException("Set CAMERAIM_INGEST_TOKEN, pass --token, or start the server once to create server/data/credentials.json.");

using var client = new HttpClient { BaseAddress = new Uri(serverUrl) };
client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
Guid pluginInstanceId = Guid.NewGuid();
Guid runId = Guid.NewGuid();
DateTimeOffset startedAt = DateTimeOffset.UtcNow;

await SendEventAsync("started", "Simulator job started.");
await SendStatusAsync("running", 5, 15);
await SendImageAsync(false);
await SendEventAsync("paused", "Simulator paused the job.");
await SendStatusAsync("paused", 25, 20);
await SendEventAsync("resumed", "Simulator resumed the job.");
await SendEventAsync("disconnected", "Simulated controller connection loss.");
await SendStatusAsync("running", 55, 25, connection: "disconnected");
await SendEventAsync("reconnected", "Simulated controller reconnection.");
await SendStatusAsync("running", 75, 30);
await SendEventAsync("completed", "Simulator emitted controller-confirmed completion.");
await SendStatusAsync("completed", 100, 35);
await SendImageAsync(true);

Console.WriteLine($"Simulation complete for {machineId} / {runId}.");

async Task SendStatusAsync(string state, double progress, long elapsed, string connection = "connected")
{
    var payload = new
    {
        apiVersion = "1.0",
        machineId,
        pluginInstanceId,
        observedAt = DateTimeOffset.UtcNow,
        connection,
        camera = new { available = true, detail = (string?)null, lastFrameAt = DateTimeOffset.UtcNow },
        job = new
        {
            runId,
            fileName = "cameraim-simulation.nc",
            state,
            startedAt,
            progressPercent = progress,
            elapsedSeconds = elapsed,
            estimatedRemainingSeconds = state == "completed" ? 0 : Math.Max(0, 40 - elapsed)
        },
        motion = new
        {
            feed = 500.0,
            feedUnit = "mm/min",
            rpm = 12000.0,
            position = new
            {
                x = progress,
                y = progress / 2,
                z = -1.0,
                unit = "mm",
                coordinateSystem = "work",
                source = "reported"
            }
        }
    };
    using HttpResponseMessage response = await client.PostAsJsonAsync("/api/v1/ingest/status", payload);
    await EnsureSuccessAsync(response);
}

async Task SendEventAsync(string kind, string detail)
{
    Guid eventId = Guid.NewGuid();
    var payload = new
    {
        apiVersion = "1.0",
        eventId,
        machineId,
        runId,
        occurredAt = DateTimeOffset.UtcNow,
        kind,
        detail
    };
    using HttpResponseMessage first = await client.PostAsJsonAsync("/api/v1/ingest/events", payload);
    await EnsureSuccessAsync(first);
    string firstJson = await first.Content.ReadAsStringAsync();
    using HttpResponseMessage duplicate = await client.PostAsJsonAsync("/api/v1/ingest/events", payload);
    await EnsureSuccessAsync(duplicate);
    string duplicateJson = await duplicate.Content.ReadAsStringAsync();
    long firstSequence = JsonDocument.Parse(firstJson).RootElement.GetProperty("serverSequence").GetInt64();
    long duplicateSequence = JsonDocument.Parse(duplicateJson).RootElement.GetProperty("serverSequence").GetInt64();
    if (firstSequence != duplicateSequence)
        throw new InvalidOperationException("Idempotency failure: duplicate event received a new sequence.");
}

async Task SendImageAsync(bool isFinal)
{
    byte[] jpeg = Convert.FromBase64String(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABAf/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPxB//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPxB//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxB//9k=");
    var metadata = new { apiVersion = "1.0", machineId, runId, capturedAt = DateTimeOffset.UtcNow, isFinal };
    using var form = new MultipartFormDataContent();
    form.Add(new StringContent(JsonSerializer.Serialize(metadata)), "metadata");
    var image = new ByteArrayContent(jpeg);
    image.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
    form.Add(image, "image", "simulator.jpg");
    using HttpResponseMessage response = await client.PostAsync("/api/v1/ingest/images", form);
    await EnsureSuccessAsync(response);
}

static async Task EnsureSuccessAsync(HttpResponseMessage response)
{
    if (response.IsSuccessStatusCode) return;
    string body = await response.Content.ReadAsStringAsync();
    throw new HttpRequestException($"Server returned {(int)response.StatusCode}: {body}");
}

string? ReadArgument(string name)
{
    int index = Array.IndexOf(args, name);
    return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
}

static string? ReadTokenFromCredentials()
{
    string path = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "server", "data", "credentials.json"));
    if (!File.Exists(path)) return null;
    using JsonDocument document = JsonDocument.Parse(File.ReadAllText(path));
    return document.RootElement.TryGetProperty("IngestToken", out JsonElement token)
        ? token.GetString()
        : null;
}
