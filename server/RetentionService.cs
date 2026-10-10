namespace CamerAim.Server;

public sealed class RetentionService : BackgroundService
{
    private readonly CamerAimRepository repository;
    private readonly ILogger<RetentionService> logger;

    public RetentionService(CamerAimRepository repository, ILogger<RetentionService> logger)
    {
        this.repository = repository;
        this.logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        do
        {
            try
            {
                await repository.ApplyRetentionAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception error)
            {
                logger.LogError(error, "Image retention cleanup failed.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
