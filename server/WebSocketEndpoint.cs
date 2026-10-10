using System.Net.WebSockets;
using System.Text;

namespace CamerAim.Server;

public static class WebSocketEndpoint
{
    public static async Task HandleAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsync("A WebSocket upgrade is required.", context.RequestAborted);
            return;
        }

        string machineId = context.Request.Query["machineId"].ToString();
        if (!ApiValidation.IsMachineId(machineId))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsync("A valid machineId query parameter is required.", context.RequestAborted);
            return;
        }
        long afterSequence = 0;
        string afterText = context.Request.Query["afterSequence"].ToString();
        if (afterText.Length > 0 && (!long.TryParse(afterText, out afterSequence) || afterSequence < 0))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsync("afterSequence must be a non-negative integer.", context.RequestAborted);
            return;
        }

        CamerAimRepository repository = context.RequestServices.GetRequiredService<CamerAimRepository>();
        StreamBroker broker = context.RequestServices.GetRequiredService<StreamBroker>();
        using WebSocket socket = await context.WebSockets.AcceptWebSocketAsync();
        using StreamBroker.Subscription subscription = broker.Subscribe(machineId);
        using var closed = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        Task receiveTask = ReceiveUntilClosedAsync(socket, closed);
        try
        {
            long cursor = afterSequence;
            while (!closed.IsCancellationRequested)
            {
                IReadOnlyList<EventRecord> missed = await repository.GetEventsAsync(
                    machineId, cursor, 500, closed.Token);
                foreach (EventRecord item in missed)
                {
                    string json = System.Text.Json.JsonSerializer.Serialize(
                        new StreamEnvelope<EventRecord>("event", item),
                        CamerAimJsonContext.Default.StreamEnvelopeEventRecord);
                    await SendAsync(socket, json, closed.Token);
                    cursor = item.ServerSequence;
                }
                if (missed.Count < 500) break;
            }

            while (await subscription.Reader.WaitToReadAsync(closed.Token))
            {
                while (subscription.Reader.TryRead(out string? json))
                    await SendAsync(socket, json, closed.Token);
            }
        }
        catch (OperationCanceledException) when (closed.IsCancellationRequested)
        {
        }
        catch (WebSocketException)
        {
            closed.Cancel();
        }
        finally
        {
            closed.Cancel();
            try { await receiveTask; } catch (OperationCanceledException) { }
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
        }
    }

    private static async Task SendAsync(WebSocket socket, string json, CancellationToken cancellationToken)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(json);
        await socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
    }

    private static async Task ReceiveUntilClosedAsync(WebSocket socket, CancellationTokenSource closed)
    {
        byte[] buffer = new byte[256];
        try
        {
            while (!closed.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, closed.Token);
                if (result.MessageType == WebSocketMessageType.Close) break;
            }
        }
        catch (OperationCanceledException) when (closed.IsCancellationRequested)
        {
        }
        catch (WebSocketException)
        {
        }
        finally
        {
            closed.Cancel();
        }
    }
}
