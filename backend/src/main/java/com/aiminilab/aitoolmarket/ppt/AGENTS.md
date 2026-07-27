# PPT Backend Engineering Rules

This file governs code under `com.aiminilab.aitoolmarket.ppt`.

## Architecture boundaries

- Keep the dependency direction `controller -> application service -> domain -> engine adapter/client`.
- Controllers validate transport input and authorization context only. They must not call an engine client directly.
- Platform DTOs and entities must not expose engine-specific identifiers, raw engine JSON, engine download URLs, or Banana naming.
- Engine-specific request/response classes belong under `ppt.engine.<engine-code>`. Translate them at the adapter boundary.
- New engines implement `PptEngineAdapter`; business services select engines by registered capabilities, never by `if ("banana")` branches.
- Persist project, version, slide, job, export, and billing state in the platform database. An engine is a renderer, not the system of record.

## Jobs, billing, and consistency

- Every submission, retry, callback, settlement, and release operation requires an idempotency key or conditional state transition.
- Submission reserves credits. Only terminal success settles credits. Failure and cancellation release the reservation.
- Unknown or timed-out outcomes enter reconciliation; never guess success and never charge twice.
- Only failures that may have occurred after a mutating request was transmitted are unknown outcomes. DNS, connection refusal, and connect timeout are definite non-submissions and fail immediately.
- External calls run after the local transaction commits. Persist enough state to recover polling after process restart.
- Job state transitions must use compare-and-set updates and reject backwards or duplicate terminal transitions.
- Every active state has an immutable stage deadline. A successful poll updates heartbeat state but never extends that deadline.
- Recovery workers must atomically claim a job lease before submitting, polling, reconciling, settling, or releasing it.
- Do not use untyped `Map<String, Object>` across application or controller boundaries. Raw engine payloads may exist only inside the engine integration package.
- Reuse `agent_model_configs`, platform routing, and vendor-account credentials. Do not add user-facing PPT API-key fields or a second model control plane.
- Production engines receive only task-scoped execution tokens. Never send provider API keys, account credentials, or a reusable internal token to an engine.
- Model invocations are children of a PPT job. They record provider usage and route attempts but must not charge the user a second time.
- User-selectable models are persisted as project-scoped platform model-config IDs. Validate capability, enabled state, and executable platform credentials on create and update; never persist copied credentials.
- Project model selection overrides the tool workflow default. A null selection means platform auto-routing, and model changes are rejected while the project has an active job.
- Do not synchronize model credentials through engine-global settings. Project and job identity must be carried on every engine submission and model invocation.
- Execution tokens must be short-lived, scoped to one project and job, capability-limited, and protected against cross-job replay.
- Automatic stage continuation uses deterministic project-scoped idempotency keys. Failure to schedule the next stage must not roll back a completed stage.
- Engine submissions must support receipt lookup by idempotency key so a lost HTTP response can be reconciled without creating a second engine task.

## API and security

- V2 endpoints live below `/api/v2/ppt` and use platform-owned request and response types.
- Scope every project, job, slide, and export lookup by the authenticated user unless the endpoint is explicitly administrative.
- Export URLs must be platform-controlled or validated storage URLs. Never return an arbitrary engine URL to the browser.
- Sanitize engine error details before returning them; keep actionable internal diagnostics in structured logs.
- Internal model-proxy endpoints require the task-scoped token even on the Docker network. Network locality is not authentication.

## Required tests

- Adapter contract fixtures for success, failure, timeout, malformed responses, and export artifacts.
- Project ownership, job idempotency, restart recovery, cancellation, retry, and terminal-state concurrency.
- Credit reserve/settle/release exactly-once behavior.
- Execution-token expiry, capability scope, cross-project isolation, invocation idempotency, and no-double-charge behavior.
- Schema contract coverage in `backend/src/test/resources/schema-test.sql`.
- Regression coverage for existing V1 PPT endpoints while V2 is additive.
