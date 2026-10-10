# CamerAim shared API contract

This directory is the compatibility boundary between the UGS plugin, local
server, simulator, and Android application.

The first public contract is `v1` and is described by `openapi.json`. JSON
examples in `examples/` are intended both as documentation and as fixtures for
component tests.

## Compatibility rules

- HTTP routes are prefixed with `/api/v1`.
- Every write payload contains `apiVersion: "1.0"`.
- Additive response fields are backwards-compatible. Consumers must ignore
  fields they do not know.
- Removing a field, changing its meaning, or narrowing an accepted enum needs a
  new API major version.
- Timestamps are RFC 3339 UTC instants. Identifiers are UUID strings, except
  human-configured `machineId` values.
- Each plugin event has a globally unique `eventId`. Re-sending the same
  `(machineId, eventId)` is idempotent.
- The server assigns a strictly increasing `serverSequence` to accepted events.
  Clients reconnect with `afterSequence` and persist the largest processed
  sequence so that notifications are not repeated.
- `completed`, `cancelled`, and `alarm` finalize job history only through an
  explicit event. Status telemetry is advisory and cannot finalize or overwrite
  a terminal job. A lost connection is always `disconnected`.
- Unknown telemetry is omitted or `null`; user interfaces render it as an em
  dash. Estimated remaining time is named `estimatedRemainingSeconds` and must
  be presented as an estimate.
- `reported` motion values come from UGS/controller feedback. `commanded`
  values, when later introduced, must never be presented as reported values.

## Authentication

All API calls except `/health` use a bearer token. Ingest tokens can write
plugin data; read tokens can access dashboard data and the event stream. Tokens
are generated locally and must not be committed or logged.

## Image upload

`POST /api/v1/ingest/images` uses `multipart/form-data` with:

- `metadata`: JSON matching `ImageMetadata`
- `image`: one JPEG file

The initial maximum accepted JPEG size is 8 MiB. The server stores images by
generated IDs rather than by user-supplied file names.
