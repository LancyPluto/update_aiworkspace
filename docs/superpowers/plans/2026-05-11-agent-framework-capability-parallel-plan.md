# Agent Framework Capability Parallel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continue framework capability integration across Deep Agents file workspace, subagent permission boundaries, run observability, and LangGraph subagent alignment.

**Architecture:** Use a thin platform abstraction first, then map it into Deep Agents native capabilities and LangGraph nodes/tools. Keep Spring Boot as the browser-facing API and permission boundary; Agent Service consumes signed internal APIs and emits run events; user-web renders observability from existing run event streams.

**Tech Stack:** Spring Boot backend, FastAPI/Python Agent Service, LangGraph, Deep Agents, Vue user-web, pytest, Maven tests, vue-tsc.

---

## Parallel Strategy

### Wave 0: Shared Contracts, Serial Gate

This wave must finish before the four parallel tracks start because every track depends on stable event and permission names.

**Owner:** Main controller or one focused worker.

**Files:**
- Modify: `agent-service/app/core/event_types.py`
- Modify: `agent-service/app/runtime/subagent_profiles.py`
- Modify: `user-web/src/api/types.ts`
- Test: `agent-service/tests/test_subagent_profiles.py`

- [ ] **Step 1: Define shared event names**

Add constants if missing:

```python
SUBAGENT_STARTED = "subagent.started"
SUBAGENT_COMPLETED = "subagent.completed"
SUBAGENT_FAILED = "subagent.failed"
WORKSPACE_FILE_CREATED = "workspace_file.created"
WORKSPACE_FILE_UPDATED = "workspace_file.updated"
WORKSPACE_FILE_READ = "workspace_file.read"
MEMORY_CONTEXT_INJECTED = "memory.context_injected"
```

- [ ] **Step 2: Define profile permission keys**

Extend `AgentSubagentProfile.permissions` usage with these string policy names:

```python
"memory:read"
"workspace_file:read"
"workspace_file:write"
"tool:invoke"
```

Default profile intent:

```text
researcher: memory:read
file-analyst: workspace_file:read
tool-operator: tool:invoke
```

- [ ] **Step 3: Write failing permission mapping test**

Run:

```powershell
pytest tests/test_subagent_profiles.py::test_default_subagent_profiles_have_permission_boundaries -q
```

Expected: fail until default profiles expose the expected permission names.

- [ ] **Step 4: Implement minimal permission mapping**

Update defaults in `agent-service/app/runtime/subagent_profiles.py`.

- [ ] **Step 5: Verify Wave 0**

Run:

```powershell
pytest tests/test_subagent_profiles.py -q
```

Expected: all tests pass.

---

## Wave 1: Four Parallel Tracks

After Wave 0 passes, dispatch four workers concurrently. Each worker owns a mostly disjoint write set.

### Track A: Deep Agents File Workspace

**Parallel worker:** A

**Depends on:** Wave 0 event names.

**Purpose:** Let Deep Agents read current workspace/session files through platform-controlled context and emit file operation events. First iteration should be read-only; write artifacts can be a follow-up within the same track after read-only tests pass.

**Files:**
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Create: `agent-service/app/runtime/workspace_files.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`
- Test: `agent-service/tests/test_workspace_files_runtime.py`

**Implementation shape:**
- Convert `RunContext.agentFiles` and `RunContext.agentFileChunks` into a Deep Agents-readable file context.
- Keep host filesystem paths hidden.
- Emit `workspace_file.read` when file context is prepared.
- Do not add browser-facing API in this track; existing upload/list APIs already exist.

**TDD checklist:**
- [ ] Write `test_deep_agents_engine_passes_workspace_file_context_to_native_runtime`.
- [ ] Run the test and confirm it fails because Deep Agents receives no file workspace context.
- [ ] Implement `workspace_files.py` formatter/builder.
- [ ] Inject formatted file workspace context into Deep Agents `memory` or `backend` integration, whichever is supported without bypassing Spring Boot.
- [ ] Emit `workspace_file.read` event with file IDs and filenames.
- [ ] Run:

```powershell
pytest tests/test_deep_agents_runtime.py tests/test_workspace_files_runtime.py -q
```

Expected: pass.

**Acceptance:**
- Deep Agents receives file context without direct filesystem access.
- Event stream includes file read context.
- Existing file upload/list tests remain unchanged.

### Track B: Subagent Permission Boundaries

**Parallel worker:** B

**Depends on:** Wave 0 permission names.

**Purpose:** Make subagent profiles enforceable before giving subagents broader file/tool powers.

**Files:**
- Modify: `agent-service/app/runtime/subagent_profiles.py`
- Create: `agent-service/app/runtime/subagent_permissions.py`
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Test: `agent-service/tests/test_subagent_profiles.py`
- Test: `agent-service/tests/test_subagent_permissions.py`

**Implementation shape:**
- Add `SubagentPermissionPolicy`.
- Validate each profile only receives allowed permissions.
- Filter Deep Agents profile-to-subagent mapping so unsupported permissions are not passed as raw Deep Agents filesystem permissions.
- Keep platform permissions in our profile abstraction for now.

**TDD checklist:**
- [ ] Write `test_researcher_cannot_get_tool_invoke_permission`.
- [ ] Write `test_file_analyst_is_read_only_for_workspace_files`.
- [ ] Run tests and confirm they fail.
- [ ] Implement `subagent_permissions.py`.
- [ ] Wire validation into `default_subagent_profiles()`.
- [ ] Run:

```powershell
pytest tests/test_subagent_profiles.py tests/test_subagent_permissions.py -q
```

Expected: pass.

**Acceptance:**
- `researcher` has `memory:read` only.
- `file-analyst` has `workspace_file:read` only.
- `tool-operator` has `tool:invoke` only.
- Invalid profile permission combinations fail in tests.

### Track C: Agent Run Observability UI

**Parallel worker:** C

**Depends on:** Wave 0 event names.

**Purpose:** Show users/admins what the Agent runtime is doing: memory injection, subagent scheduling, file reads, tool calls.

**Files:**
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/pages/AgentHome/RunTimeline.vue`
- Test/build: `user-web` typecheck and build.

**Implementation shape:**
- Keep event source unchanged; consume existing `AgentRunEvent`.
- Add typed helpers for event kind display.
- Render a compact timeline inside AgentHome.
- Highlight:
  - `subagent.started`
  - `subagent.completed`
  - `subagent.failed`
  - `memory.context_injected`
  - `workspace_file.read`
  - `tool.started`
  - `tool.finished`

**TDD/check checklist:**
- [ ] Add `RunTimeline.vue` with props `{ events: AgentRunEvent[] }`.
- [ ] Add TypeScript type guards/helpers in the component or a local helper.
- [ ] Integrate `RunTimeline` into AgentHome using existing `events`.
- [ ] Run:

```powershell
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
```

Expected: pass.

- [ ] Run:

```powershell
npm.cmd run build
```

Expected: pass.

**Acceptance:**
- User can see subagent lifecycle events without opening raw JSON.
- Failed subagents are visually distinct.
- Timeline does not replace chat output.

### Track D: LangGraph Subagent Alignment

**Parallel worker:** D

**Depends on:** Wave 0 profile abstraction.

**Purpose:** Let LangGraph share the same `AgentSubagentProfile` abstraction even if full delegation remains lighter than Deep Agents.

**Files:**
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Create: `agent-service/app/graphs/subagent_router.py`
- Test: `agent-service/tests/test_universal_graph.py`
- Test: `agent-service/tests/test_subagent_profiles.py`

**Implementation shape:**
- Add a `SubagentRouter` that selects a profile for complex independent tasks.
- First iteration should not execute real nested agents; it should emit a platform event and produce a clear model prompt section saying which subagent profile is recommended.
- Keep real LangGraph nested execution for the next phase after observability and permissions stabilize.

**TDD checklist:**
- [ ] Write `test_langgraph_uses_shared_subagent_profiles_for_delegation_hint`.
- [ ] Run test and confirm it fails.
- [ ] Implement `subagent_router.py`.
- [ ] Inject a concise subagent delegation context into LangGraph prompt for qualifying messages.
- [ ] Emit `subagent.started/completed` only if an actual subagent execution happens; for this track, prefer `subagent.recommended` only if Wave 0 adds that event. If not, keep it prompt-only.
- [ ] Run:

```powershell
pytest tests/test_universal_graph.py tests/test_subagent_profiles.py -q
```

Expected: pass.

**Acceptance:**
- LangGraph and Deep Agents share profile definitions.
- LangGraph tests prove profile-aware routing without pretending full subagent execution exists.
- No new backend or frontend dependency is introduced.

---

## Wave 2: Integration Review

Run after all four tracks return.

**Owner:** Main controller.

- [ ] **Step 1: Check write set conflicts**

Expected likely overlaps:
- Track A and B both touch `agent-service/app/runtime/deep_agents_engine.py`.
- Track B and D both touch profile abstractions.

Resolve by preserving:
- Track B permission validation.
- Track A file workspace injection.
- Existing memory/native memory/subagent trace behavior.

- [ ] **Step 2: Agent Service full verification**

Run:

```powershell
pytest -q
```

Expected: all pass.

- [ ] **Step 3: Backend targeted verification**

Run:

```powershell
mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest,AgentApiTest,OpenApiContractTest" test
```

Expected: all pass.

- [ ] **Step 4: Frontend verification**

Run:

```powershell
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
npm.cmd run build
```

Expected: both pass.

- [ ] **Step 5: Cross-runtime smoke**

Verify these paths manually or through existing smoke scripts:
- LangGraph run still answers with workspace memory.
- Deep Agents run receives native memory and subagents.
- Deep Agents subagent callback events appear in run events.
- AgentHome timeline renders events.

---

## Recommended Dispatch

Use four parallel workers after Wave 0:

```text
Worker A: Deep Agents file workspace
Worker B: subagent permission boundaries
Worker C: AgentHome run timeline
Worker D: LangGraph subagent alignment
```

If resources are limited, run this order:

```text
1. Wave 0 shared contracts
2. Track B permissions + Track C observability in parallel
3. Track A file workspace + Track D LangGraph alignment in parallel
4. Wave 2 integration review
```

## Risk Notes

- Deep Agents native filesystem backend may require a stronger adapter than simple memory/context injection. If so, keep Track A read-only and defer write artifacts.
- Permissions must stay platform-owned; do not trust Deep Agents middleware alone as the security boundary.
- Frontend observability should consume existing run events; avoid new polling endpoints unless event shape is insufficient.
- LangGraph full nested subagent execution should wait until permission and trace semantics are stable.
