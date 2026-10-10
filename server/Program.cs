using System.Text.Json;
using CamerAim.Server;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.Extensions.Options;

WebApplicationBuilder builder = WebApplication.CreateBuilder(args);
builder.Services.AddOptions<CamerAimOptions>()
    .Bind(builder.Configuration.GetSection("CamerAim"))
    .Validate(
        value => value.StaleAfterSeconds > 0 &&
            value.OfflineAfterSeconds > value.StaleAfterSeconds &&
            value.MaximumImageBytes > 0 &&
            value.ImageRetentionDays > 0 &&
            value.MaximumImageStorageBytes >= value.MaximumImageBytes,
        "CamerAim retention and freshness limits are inconsistent.")
    .ValidateOnStart();
builder.Services.Configure<FormOptions>(options =>
{
    options.MultipartBodyLengthLimit = 9L * 1024 * 1024;
    options.ValueLengthLimit = 64 * 1024;
});
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 9L * 1024 * 1024);
builder.Services.ConfigureHttpJsonOptions(options =>
    options.SerializerOptions.TypeInfoResolverChain.Insert(0, CamerAimJsonContext.Default));
builder.Services.AddSingleton<Database>();
builder.Services.AddSingleton<CamerAimRepository>();
builder.Services.AddSingleton<TokenService>();
builder.Services.AddSingleton<StreamBroker>();
builder.Services.AddHostedService<RetentionService>();

WebApplication app = builder.Build();
await app.Services.GetRequiredService<Database>().InitializeAsync(CancellationToken.None);

app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
app.Use(async (context, next) =>
{
    if (!context.Request.Path.StartsWithSegments("/api/v1"))
    {
        await next();
        return;
    }

    TokenRole requiredRole = context.Request.Path.StartsWithSegments("/api/v1/ingest")
        ? TokenRole.Ingest
        : TokenRole.Read;
    TokenService tokens = context.RequestServices.GetRequiredService<TokenService>();
    if (!tokens.Authorize(context.Request, requiredRole))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await Results.Problem(
            statusCode: StatusCodes.Status401Unauthorized,
            title: "A valid bearer token is required.").ExecuteAsync(context);
        return;
    }
    await next();
});

app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    apiVersion = "1.0",
    serverTime = DateTimeOffset.UtcNow
}));

app.MapPost("/api/v1/ingest/status", async (
    StatusUpdate update,
    CamerAimRepository repository,
    StreamBroker broker,
    CancellationToken cancellationToken) =>
{
    string? error = ApiValidation.Validate(update);
    if (error is not null) return Results.Problem(statusCode: 400, title: "Invalid status update", detail: error);
    await repository.SaveStatusAsync(update, cancellationToken);
    MachineState? state = await repository.GetStateAsync(update.MachineId, cancellationToken);
    if (state is not null)
    {
        string json = JsonSerializer.Serialize(
            new StreamEnvelope<MachineState>("state", state),
            CamerAimJsonContext.Default.StreamEnvelopeMachineState);
        broker.Publish(update.MachineId, json);
    }
    return Results.Accepted();
});

app.MapPost("/api/v1/ingest/events", async (
    EventInput input,
    CamerAimRepository repository,
    StreamBroker broker,
    CancellationToken cancellationToken) =>
{
    string? error = ApiValidation.Validate(input);
    if (error is not null) return Results.Problem(statusCode: 400, title: "Invalid event", detail: error);
    (EventRecord record, bool created) = await repository.SaveEventAsync(input, cancellationToken);
    if (created)
    {
        string json = JsonSerializer.Serialize(
            new StreamEnvelope<EventRecord>("event", record),
            CamerAimJsonContext.Default.StreamEnvelopeEventRecord);
        broker.Publish(input.MachineId, json);
    }
    return Results.Json(
        record,
        CamerAimJsonContext.Default.EventRecord,
        statusCode: created ? StatusCodes.Status201Created : StatusCodes.Status200OK);
});

app.MapPost("/api/v1/ingest/images", async (
    HttpRequest request,
    CamerAimRepository repository,
    IOptions<CamerAimOptions> options,
    CancellationToken cancellationToken) =>
{
    if (!request.HasFormContentType)
        return Results.Problem(statusCode: 400, title: "multipart/form-data is required.");
    IFormCollection form = await request.ReadFormAsync(cancellationToken);
    string metadataJson = form["metadata"].ToString();
    IFormFile? image = form.Files.GetFile("image");
    if (string.IsNullOrWhiteSpace(metadataJson) || image is null)
        return Results.Problem(statusCode: 400, title: "Both metadata and image fields are required.");
    if (!string.Equals(image.ContentType, "image/jpeg", StringComparison.OrdinalIgnoreCase))
        return Results.Problem(statusCode: 400, title: "Only image/jpeg uploads are accepted.");
    if (image.Length is <= 0 || image.Length > options.Value.MaximumImageBytes)
        return Results.Problem(statusCode: 413, title: "The JPEG exceeds the configured size limit.");

    ImageMetadata? metadata;
    try
    {
        metadata = JsonSerializer.Deserialize(metadataJson, CamerAimJsonContext.Default.ImageMetadata);
    }
    catch (JsonException)
    {
        return Results.Problem(statusCode: 400, title: "metadata is not valid JSON.");
    }
    if (metadata is null)
        return Results.Problem(statusCode: 400, title: "metadata is required.");
    string? error = ApiValidation.Validate(metadata);
    if (error is not null) return Results.Problem(statusCode: 400, title: "Invalid image metadata", detail: error);

    await using var memory = new MemoryStream(checked((int)image.Length));
    await image.CopyToAsync(memory, cancellationToken);
    byte[] bytes = memory.ToArray();
    if (bytes.Length < 4 || bytes[0] != 0xff || bytes[1] != 0xd8 || bytes[^2] != 0xff || bytes[^1] != 0xd9)
        return Results.Problem(statusCode: 400, title: "The uploaded file is not a complete JPEG.");
    ImageRecord record = await repository.SaveImageAsync(metadata, bytes, cancellationToken);
    return Results.Json(record, CamerAimJsonContext.Default.ImageRecord, statusCode: StatusCodes.Status201Created);
});

app.MapGet("/api/v1/machines/{machineId}/state", async (
    string machineId,
    CamerAimRepository repository,
    CancellationToken cancellationToken) =>
{
    if (!ApiValidation.IsMachineId(machineId))
        return Results.Problem(statusCode: 400, title: "Invalid machineId.");
    MachineState? state = await repository.GetStateAsync(machineId, cancellationToken);
    return state is null
        ? Results.Problem(statusCode: 404, title: "Machine was not found.")
        : Results.Json(state, CamerAimJsonContext.Default.MachineState);
});

app.MapGet("/api/v1/machines/{machineId}/events", async (
    string machineId,
    long? afterSequence,
    int? limit,
    CamerAimRepository repository,
    CancellationToken cancellationToken) =>
{
    if (!ApiValidation.IsMachineId(machineId))
        return Results.Problem(statusCode: 400, title: "Invalid machineId.");
    long after = Math.Max(0, afterSequence ?? 0);
    int take = Math.Clamp(limit ?? 100, 1, 500);
    IReadOnlyList<EventRecord> events = await repository.GetEventsAsync(machineId, after, take, cancellationToken);
    return Results.Json(events, CamerAimJsonContext.Default.IReadOnlyListEventRecord);
});

app.MapGet("/api/v1/machines/{machineId}/jobs", async (
    string machineId,
    int? limit,
    CamerAimRepository repository,
    CancellationToken cancellationToken) =>
{
    if (!ApiValidation.IsMachineId(machineId))
        return Results.Problem(statusCode: 400, title: "Invalid machineId.");
    IReadOnlyList<JobRun> jobs = await repository.GetJobsAsync(
        machineId,
        Math.Clamp(limit ?? 25, 1, 100),
        cancellationToken);
    return Results.Json(jobs, CamerAimJsonContext.Default.IReadOnlyListJobRun);
});

app.MapGet("/api/v1/images/{imageId:guid}", async (
    Guid imageId,
    CamerAimRepository repository,
    CancellationToken cancellationToken) =>
{
    (ImageRecord Record, string FullPath)? image = await repository.GetImageAsync(imageId, cancellationToken);
    return image is null || !File.Exists(image.Value.FullPath)
        ? Results.NotFound()
        : Results.File(image.Value.FullPath, "image/jpeg");
});

app.Map("/api/v1/stream", WebSocketEndpoint.HandleAsync);

app.Run();

public partial class Program;
