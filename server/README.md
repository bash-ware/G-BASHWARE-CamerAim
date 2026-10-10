# CamerAim local server

The server implements API `v1` from `../shared/openapi.json`. It stores state,
jobs, deduplicated events, and JPEG metadata in SQLite; JPEG bytes are kept in a
bounded local folder.

## Development run

Requires the .NET 10 SDK:

```powershell
dotnet run --project server/CamerAim.Server.csproj
```

The default endpoint is `http://127.0.0.1:5187`, intentionally reachable only
from the same computer. On first start, the server creates
`server/data/credentials.json` with separate `IngestToken` and `ReadToken`
values. Token values are never logged. The entire `server/data` directory is
ignored by Git.

To run the simulator in another terminal:

```powershell
dotnet run --project simulator/CamerAim.Simulator.csproj
```

For a LAN test, set `DOTNET_ENVIRONMENT=Lan` or explicitly configure the
Kestrel URL. Binding to `0.0.0.0` exposes the API to the local network. Add a
narrowly scoped Windows Firewall rule only after choosing the trusted network
profile. API v1 uses bearer tokens but plain HTTP does not encrypt them; use
only a trusted isolated LAN during prototyping. A later installer must provide
HTTPS or document a VPN-based deployment before remote access is recommended.

Environment variables `CAMERAIM_INGEST_TOKEN` and `CAMERAIM_READ_TOKEN`
override the credentials file only when both are set.

## Persistence and retention

- SQLite database: `server/data/cameraim.db`
- JPEG files: `server/data/images/`
- Default JPEG limit: 8 MiB each
- Default retention: 14 days and 2 GiB total, newest images retained first

Retention values are configurable in `appsettings.json`. Events and status rows
are not deleted by the image retention job.
