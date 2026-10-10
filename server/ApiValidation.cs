using System.Text.RegularExpressions;

namespace CamerAim.Server;

public static partial class ApiValidation
{
    private static readonly HashSet<string> Connections =
        ["connected", "disconnected", "connecting", "unknown"];

    private static readonly HashSet<string> JobStates =
        ["running", "paused", "completed", "cancelled", "alarm", "unknown"];

    private static readonly HashSet<string> EventKinds =
        ["started", "paused", "resumed", "completed", "cancelled", "alarm", "disconnected", "reconnected", "cameraUnavailable"];

    [GeneratedRegex("^[A-Za-z0-9._-]{1,80}$", RegexOptions.CultureInvariant)]
    private static partial Regex MachineIdRegex();

    public static string? Validate(StatusUpdate value)
    {
        string? common = ValidateCommon(value.ApiVersion, value.MachineId, value.ObservedAt);
        if (common is not null) return common;
        if (value.PluginInstanceId == Guid.Empty) return "pluginInstanceId must be a non-empty UUID.";
        if (!Connections.Contains(value.Connection)) return "connection has an unsupported value.";
        if (value.Camera.Detail?.Length > 300) return "camera.detail exceeds 300 characters.";
        if (value.Job is { } job)
        {
            if (job.RunId == Guid.Empty) return "job.runId must be a non-empty UUID.";
            if (!JobStates.Contains(job.State)) return "job.state has an unsupported value.";
            if (job.ProgressPercent is < 0 or > 100) return "job.progressPercent must be between 0 and 100.";
            if (job.ElapsedSeconds < 0 || job.EstimatedRemainingSeconds < 0) return "Job durations cannot be negative.";
        }
        if (value.Motion?.Position is { } position)
        {
            if (position.Unit is not ("mm" or "in")) return "position.unit must be mm or in.";
            if (position.CoordinateSystem is not ("machine" or "work")) return "position.coordinateSystem must be machine or work.";
            if (position.Source != "reported") return "position.source must be reported in API v1.";
        }
        return null;
    }

    public static string? Validate(EventInput value)
    {
        string? common = ValidateCommon(value.ApiVersion, value.MachineId, value.OccurredAt);
        if (common is not null) return common;
        if (value.EventId == Guid.Empty) return "eventId must be a non-empty UUID.";
        if (!EventKinds.Contains(value.Kind)) return "kind has an unsupported value.";
        if (value.Detail?.Length > 1000) return "detail exceeds 1000 characters.";
        if (value.Kind is not ("disconnected" or "reconnected" or "cameraUnavailable") && value.RunId is null)
            return $"runId is required for {value.Kind} events.";
        return null;
    }

    public static string? Validate(ImageMetadata value) =>
        ValidateCommon(value.ApiVersion, value.MachineId, value.CapturedAt);

    public static bool IsMachineId(string value) => MachineIdRegex().IsMatch(value);

    private static string? ValidateCommon(string apiVersion, string machineId, DateTimeOffset timestamp)
    {
        if (apiVersion != "1.0") return "apiVersion must be 1.0.";
        if (!IsMachineId(machineId)) return "machineId may contain only letters, digits, dot, underscore, and hyphen (maximum 80 characters).";
        if (timestamp == default) return "A valid timestamp is required.";
        if (timestamp > DateTimeOffset.UtcNow.AddMinutes(5)) return "Timestamp cannot be more than five minutes in the future.";
        return null;
    }
}
