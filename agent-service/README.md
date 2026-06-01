# Agent Service

FastAPI service for the Cloud Universal Agent.

## Stack

- FastAPI for the internal HTTP API.
- LangGraph for the universal agent graph when the dependency is installed.
- LangChain-compatible message/tool boundaries for model and tool orchestration.
- Workspace memory and controlled tool calling are built into the default runtime.
- Deep Agents remains an optional enhancement layer for planning, sub-agents, and richer workspace context.

## Local Run

```powershell
pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8090
```

```powershell
curl http://localhost:8090/health
```

## Tests

```powershell
pytest -q
```

## Agent Tool Routing

The default runtime uses a product-tool-first pass before the legacy intent
router. The pass exposes visible backend AI tools as structured model tools and
then sends any selected tool through the existing backend bridge, budget guard,
confirmation flow, and task polling.

Each run receives runtime settings from the Spring Boot context endpoint. Those
settings are request-scoped: model/tool call limits, task timeouts, polling
intervals, stream relay, and product tool loop limits do not mutate shared
runtime instances. The runtime emits `runtime_settings.applied` so backend/admin
views can audit the effective settings for a run.

Useful local switches:

```powershell
AGENT_PRODUCT_TOOL_LOOP_ENABLED=true
AGENT_PRODUCT_TOOL_LOOP_MAX_CALLS=1
AGENT_PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER=true
```

## Memory

Long-term memory is owned by Spring Boot and injected into the model as a frozen
workspace memory snapshot. The agent may request safe memory writes through
`memory_add`, `memory_replace`, and `memory_remove`, but backend policy and admin
review remain the source of truth.

Memory safety checks reject prompt-injection phrases, invisible control
characters, and secret-like values. Rejections are emitted as `memory.rejected`
events with a reason such as `prompt_injection_pattern`, `secret_like_value`, or
`invisible_control_character`.

## Credit Boundary

The agent performs an early local dispatch check before tool execution, but final
credit estimation, freezing, settlement, and release stay in Spring Boot. Backend
tasks created through the agent still flow through the same task and credit
services as normal product usage.

## Runtime Boundary

Spring Boot owns users, sessions, runs, tools, credits, persistence, and audit. This service only loads run context through signed internal APIs, routes intent, executes the LangGraph flow, calls allowed tools through Spring Boot callbacks, and completes or fails the run.
