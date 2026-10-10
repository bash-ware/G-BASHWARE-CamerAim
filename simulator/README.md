# CamerAim job simulator

The simulator exercises the local server without UGS. It sends status updates,
JPEG frames, and the full started/paused/resumed/disconnected/reconnected/
completed event path. Every event is sent twice and the simulator fails if the
server assigns a second sequence number, providing an end-to-end idempotency
check.

Start the server first so its local credentials file exists, then run:

```powershell
dotnet run --project simulator/CamerAim.Simulator.csproj
```

Use `--url`, `--machine`, and `--token` to override defaults. The token can
also be supplied as `CAMERAIM_INGEST_TOKEN`.
