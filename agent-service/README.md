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

Routing lives in `app/routing/` as a layered pipeline:

1. **StateGuard** — deterministic only (empty message, pending tool context, structured field follow-ups)
2. **LLMClassifier** — semantic intent + tool selection (`attachmentSignals`, `capabilityFlags`, v2 prompt)
3. **ToolResolver** — optional function-calling refinement when classifier confidence is below threshold
4. **PolicyValidator** — capability gates (file analysis / RAG / workflow) and modality checks

Legacy shims remain at `app/core/intent_router.py` and `app/runtime/agent_router_service.py`.

Configure behavior via env:

```powershell
AGENT_LLM_ROUTER_ENABLED=true
AGENT_ROUTING_V2_ENABLED=true
AGENT_ROUTING_V2_SHADOW_MODE=false
AGENT_ROUTING_V2_LLM_ONLY=false
AGENT_CAPABILITY_FILE_ANALYSIS=false
AGENT_PRODUCT_TOOL_LOOP_ENABLED=true
```

Admin Prompts (`agent.router.*`) still override router prompt/history. When routing falls back, inspect run event `router.fallback` for `validationFailure`.

## Multimodal Context Contract

Follow-up image editing must use structured state, not raw-text keyword patches.

- `app/runtime/session_state.py` hydrates the latest generated image from `recentToolCalls.mediaUrls` plus the original prompt from `argumentsJson` / `resultJson`, then injects it as `<SessionState>`.
- The LLM resolves pronouns such as "this", "previous image", "刚刚", or "原图" by reading `<SessionState>`. Python business code must not special-case those words with `if`, regex, or whitelist routing.
- GPT/OpenAI image tools expose `base_image_url` for the image being edited and `reference_images` for extra face/style/pose references. Do not collapse them back into one ambiguous `image` field in Agent-facing schema.
- `app/core/attachment_catalog.py` treats inline chips and `@图片` labels as pointers. Tool arguments must be resolved by `resolve_media_argument_pointers` before task creation, so workers never receive UI labels such as `@图片1`.
- `app/tools/registry.py` must keep output modality detection conservative. Cinematic style or composition language is image intent unless the user explicitly asks for video output.

Regression coverage for this contract lives in `tests/test_session_state.py`, `tests/test_attachment_catalog.py`, `tests/test_user_attachment_priority.py`, `tests/test_backend_tool_bridge.py`, and `tests/test_tool_registry.py`.

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
