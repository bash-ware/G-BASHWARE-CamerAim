namespace CamerAim.Server;

public sealed class CamerAimOptions
{
    public string DataDirectory { get; set; } = "data";
    public int StaleAfterSeconds { get; set; } = 10;
    public int OfflineAfterSeconds { get; set; } = 30;
    public long MaximumImageBytes { get; set; } = 8 * 1024 * 1024;
    public int ImageRetentionDays { get; set; } = 14;
    public long MaximumImageStorageBytes { get; set; } = 2L * 1024 * 1024 * 1024;
}
