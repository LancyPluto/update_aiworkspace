# Agent Local Smoke Checks

These scripts validate the local Agent MVP loop through the backend API.

## End-to-end smoke

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\agent-e2e-smoke.ps1 -BaseUrl http://127.0.0.1:8080
```

The expected completed event chain is:

```text
run.started -> intent.detected -> message.delta -> message.completed -> run.completed
```

The script now prints both `expectedLifecycle` and `actualLifecycle`, then fails if any required event is missing or out of order. It also checks for duplicate lifecycle events among `run.started`, `intent.detected`, `message.completed`, and `run.completed`. Multiple `message.delta` events may be valid while streaming, so they are not treated as duplicate lifecycle failures.

Readiness and polling knobs:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\agent-e2e-smoke.ps1 -ReadyAttempts 30 -PollAttempts 30 -PollDelaySeconds 1
```

If the backend is still starting, login is retried and each retry prints the failing operation plus HTTP/status details. If the run fails or times out, the error includes `sessionId`, `runId`, and the event summary collected so far.

## Load smoke

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\agent-smoke-load.ps1 -BackendBaseUrl http://127.0.0.1:8080 -Requests 8
```

The load smoke accepts rate limiting as an expected reliability outcome. It fails only when no Agent message is accepted. The summary includes accepted run IDs, business failures, HTTP failures, and per-request failure details.

Use `-DryRun` to confirm parameters without sending messages.
