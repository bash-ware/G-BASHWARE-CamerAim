using System.Text.Json.Serialization;

namespace CamerAim.Server;

public sealed record CameraStatus(bool Available, string? Detail, DateTimeOffset? LastFrameAt);

public sealed record Position(
    double? X,
    double? Y,
    double? Z,
    string Unit,
    string CoordinateSystem,
    string Source);

public sealed record MotionStatus(double? Feed, string? FeedUnit, double? Rpm, Position? Position);

public sealed record JobStatus(
    Guid RunId,
    string? FileName,
    string State,
    DateTimeOffset StartedAt,
    double? ProgressPercent,
    long? ElapsedSeconds,
    long? EstimatedRemainingSeconds);

public sealed record StatusUpdate(
    string ApiVersion,
    string MachineId,
    Guid PluginInstanceId,
    DateTimeOffset ObservedAt,
    string Connection,
    CameraStatus Camera,
    JobStatus? Job,
    MotionStatus? Motion);

public sealed record EventInput(
    string ApiVersion,
    Guid EventId,
    string MachineId,
    Guid? RunId,
    DateTimeOffset OccurredAt,
    string Kind,
    string? Detail);

public sealed record EventRecord(
    string ApiVersion,
    Guid EventId,
    string MachineId,
    Guid? RunId,
    DateTimeOffset OccurredAt,
    string Kind,
    string? Detail,
    long ServerSequence,
    DateTimeOffset ReceivedAt);

public sealed record ImageMetadata(
    string ApiVersion,
    string MachineId,
    Guid? RunId,
    DateTimeOffset CapturedAt,
    bool IsFinal);

public sealed record ImageRecord(
    string ApiVersion,
    Guid ImageId,
    string MachineId,
    Guid? RunId,
    DateTimeOffset CapturedAt,
    bool IsFinal,
    DateTimeOffset ReceivedAt,
    long ContentLength,
    string Url);

public sealed record MachineState(
    string MachineId,
    string Freshness,
    DateTimeOffset LastStatusAt,
    string Connection,
    CameraStatus Camera,
    JobStatus? Job,
    MotionStatus? Motion,
    ImageRecord? LatestImage,
    long LastServerSequence);

public sealed record JobRun(
    string MachineId,
    Guid RunId,
    string? FileName,
    string State,
    DateTimeOffset StartedAt,
    DateTimeOffset? EndedAt,
    Guid? FinalImageId);

public sealed record StreamEnvelope<T>(string Type, T Data);

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(StatusUpdate))]
[JsonSerializable(typeof(EventInput))]
[JsonSerializable(typeof(EventRecord))]
[JsonSerializable(typeof(ImageMetadata))]
[JsonSerializable(typeof(ImageRecord))]
[JsonSerializable(typeof(MachineState))]
[JsonSerializable(typeof(JobRun))]
[JsonSerializable(typeof(List<EventRecord>))]
[JsonSerializable(typeof(List<JobRun>))]
[JsonSerializable(typeof(IReadOnlyList<EventRecord>))]
[JsonSerializable(typeof(IReadOnlyList<JobRun>))]
[JsonSerializable(typeof(StreamEnvelope<EventRecord>))]
[JsonSerializable(typeof(StreamEnvelope<MachineState>))]
public partial class CamerAimJsonContext : JsonSerializerContext;
