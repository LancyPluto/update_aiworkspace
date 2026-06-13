# Agent Service

FastAPI service for the Cloud Universal Agent.

## Stack

- FastAPI for the internal HTTP API.
- LangChain-compatible message/tool boundaries for model and tool orchestration.
- Three selectable execution engines (see Runtime Engines below).
- Workspace memory and controlled tool calling are built into every runtime.

## Runtime Engines

`RuntimeRouter.select_engine` picks one of three engines per run:

1. `LegacyDispatcherEngine` (default / rollback) — the pre-refactor single-turn
   router that classifies intent once and dispatches at most one product tool.
   This engine does NOT use a state graph.
2. `AgentGraphEngine` — the multi-step agentic loop built on a LangGraph
   `StateGraph`. It performs native function-calling over product tools, chains
   multiple tools, reflects on failures, and surfaces a plan/todo. Enabled via
   `AGENT_GRAPH_ENGINE_ENABLED=true` or per-run via
   `runtimeSettings.intelligenceLevel=graph`.
3. `DeepAgentsRuntimeEngine` (native deepagents) — optional planning/sub-agent
   layer, enabled via `AGENT_DEEP_AGENTS_ENABLED=true`.

All three execute product tools exclusively through
`ToolOrchestrator.execute_with_guard` -> `BackendToolBridge` -> backend task
APIs, so the model can never bypass credit, permission, or confirmation checks.

> Full architecture & maintenance guide (engines, graph loop, HITL checkpoint,
> event contract, file map): [docs/agent/架构与维护指南.md](../docs/agent/架构与维护指南.md).

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

Intent routing uses the LLM router as the primary decision path. Configure router
behavior from the admin Prompts page (`agent.router.*`), including:

- `agent.router.history_turns` — how many prior user turns are sent to the router (default 4)
- `agent.router.recent_tool_calls` — how many recent successful tool calls are included (default 5)

Routing order:

1. Infrastructure rules (file / pending tool context)
2. LLM Router (primary)
3. Product Tool Call Loop fallback when router rejects the model output (optional, default on via `agent.runtime.product_tool_loop_enabled`)
4. Safe `general_chat` if both fail

When routing falls back to chat, check run event `router.fallback` for `validationFailure` (for example `confidence_below_min`, `tool_not_available`, `output_modality_mismatch`).

Useful local switches:

```powershell
AGENT_LLM_ROUTER_ENABLED=true
AGENT_PRODUCT_TOOL_LOOP_ENABLED=true
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
