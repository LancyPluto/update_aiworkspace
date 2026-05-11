# Agent Platform Blueprint Implementation Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the approved Agent Platform Blueprint into sequenced, testable implementation phases without destabilizing the current Agent MVP.

**Architecture:** Keep Spring Boot as the business source of truth and FastAPI `agent-service` as the execution layer. Stabilize the current LangGraph MVP first, then introduce a runtime abstraction, workspace memory, and Deep Agents as a feature-flagged runtime. Each major phase gets its own detailed implementation plan before code changes begin.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, MySQL/H2, Redis, FastAPI, LangChain, LangGraph, Deep Agents, Vue 3, Vite, Pinia, SSE, Docker Compose.

---

## 1. Scope Decision

The blueprint covers several independent subsystems:

- current Agent MVP stabilization
- runtime abstraction
- workspace and memory model
- Deep Agents runtime
- tool marketplace policy expansion
- knowledge base plugin
- production observability and hardening

This roadmap does not try to implement all of them in one code branch. It decomposes them into phase plans. Phase A and Phase B are detailed enough to execute next because they unblock all later work. Phase C-G should each receive a separate detailed TDD plan before implementation.

Source design:

```text
docs/superpowers/specs/2026-05-10-agent-platform-blueprint-design.md
```

Current MVP plan to preserve:

```text
docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md
```

## 2. Phase Overview

```text
Phase A: Stabilize Current MVP
  -> Fix user-web typecheck
  -> Verify backend and agent-service
  -> Add smoke script for current Agent flow

Phase B: Runtime Abstraction
  -> Introduce AgentRuntimeEngine in agent-service
  -> Wrap current UniversalAgentGraph as LangGraphRuntimeEngine
  -> Add runtime metadata to backend traces/events

Phase C: Workspace Memory MVP
  -> Add workspace tables and APIs
  -> Attach sessions to workspace
  -> Add memory candidates and retrieval
  -> Add memory UI

Phase D: Deep Agents Preview Runtime
  -> Feature-flag deepagents dependency
  -> Add read-only workspace runtime
  -> Add virtual file API bridge
  -> Add sub-agent traces and budget controls

Phase E: Tool Marketplace Expansion
  -> Add side-effect levels and policy
  -> Improve tool schemas
  -> Add argument extraction and validation

Phase F: Knowledge Base Plugin
  -> Add knowledge base entities
  -> Add permissions and retrieval APIs
  -> Integrate as separate context source

Phase G: Production Hardening
  -> Checkpoint/resume
  -> dashboards
  -> audit export
  -> workspace sharing
```

## 3. Files By Phase

### Phase A Files

- Modify: `user-web/src/api/client.ts`
- Create: `scripts/agent-e2e-smoke.ps1`
- Modify: `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`

### Phase B Files

- Create: `agent-service/app/runtime/engine.py`
- Create: `agent-service/app/runtime/langgraph_engine.py`
- Create: `agent-service/app/runtime/router.py`
- Create: `agent-service/app/runtime/__init__.py`
- Modify: `agent-service/app/core/runtime.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Create: `agent-service/tests/test_runtime_router.py`
- Create: `agent-service/tests/test_langgraph_runtime_engine.py`
- Modify: `agent-service/tests/test_universal_graph.py`

### Phase C Plan Files

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-c-workspace-memory.md`
- Later code files will include backend workspace entities, mappers, services, controllers, SQL migrations, agent-service memory retrieval client code, and user-web memory UI.

### Phase D Plan Files

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-d-deep-agents-runtime.md`
- Later code files will include Deep Agents runtime engine, feature flag config, virtual file bridge, sub-agent trace models, and tests.

### Phase E Plan Files

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-e-tool-policy-expansion.md`
- Later code files will include tool side-effect policy, descriptor upgrades, schema validation, argument extraction, and tool confirmation UI changes.

### Phase F Plan Files

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-f-knowledge-base-plugin.md`
- Later code files will include knowledge base tables, permissions, ingestion, retrieval, and citation UI.

### Phase G Plan Files

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-g-production-hardening.md`
- Later code files will include checkpointing, resume, audit export, metrics, dashboards, and workspace sharing.

---

## 4. Phase A: Stabilize Current MVP

### Task A1: Fix user-web API body typing

**Files:**

- Modify: `user-web/src/api/client.ts`

- [ ] **Step 1: Confirm the failing typecheck**

Run:

```powershell
cd user-web
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
```

Expected:

```text
src/api/client.ts(74,5): error TS2322: Type '{} | null | undefined' is not assignable to type 'BodyInit | null | undefined'.
```

- [ ] **Step 2: Replace inline fetch body expression with typed request body**

In `user-web/src/api/client.ts`, replace the current `fetch` call body construction:

```ts
  const res = await fetch(url, {
    method,
    headers,
    body: options?.body === undefined ? undefined : isFormData ? options.body : JSON.stringify(options.body),
  })
```

with:

```ts
  let requestBody: BodyInit | undefined
  if (options?.body !== undefined) {
    requestBody = isFormData ? options.body : JSON.stringify(options.body)
  }

  const res = await fetch(url, {
    method,
    headers,
    body: requestBody,
  })
```

This preserves runtime behavior and gives TypeScript a concrete `BodyInit | undefined` value.

- [ ] **Step 3: Re-run user-web typecheck**

Run:

```powershell
cd user-web
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
```

Expected:

```text
No TypeScript errors.
```

- [ ] **Step 4: Build user-web**

Run:

```powershell
cd user-web
npm run build
```

Expected:

```text
vite build completes successfully.
```

- [ ] **Step 5: Commit Phase A1**

Run:

```powershell
git add user-web/src/api/client.ts
git commit -m "fix: type api request body for user web"
```

Expected:

```text
Commit succeeds when running inside a git repository.
```

If the workspace is still not a git repository, record the changed file in the final handoff instead of committing.

### Task A2: Verify current backend and agent-service baseline

**Files:**

- No code changes.

- [ ] **Step 1: Run agent-service tests**

Run:

```powershell
cd agent-service
pytest -q
```

Expected:

```text
44 passed
```

One LangGraph/LangChain deprecation warning is acceptable.

- [ ] **Step 2: Run backend Agent contract tests**

Run:

```powershell
mvn -f backend\pom.xml "-Dtest=AgentApiTest,RedisAgentRateLimitServiceTest,OpenApiContractTest" test
```

Expected:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 3: Run user-web typecheck after A1**

Run:

```powershell
cd user-web
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
```

Expected:

```text
No TypeScript errors.
```

### Task A3: Add current Agent smoke script

**Files:**

- Create: `scripts/agent-e2e-smoke.ps1`

- [ ] **Step 1: Create smoke script skeleton**

Create `scripts/agent-e2e-smoke.ps1` with:

```powershell
param(
  [string]$BaseUrl = "http://127.0.0.1:8080",
  [string]$Username = "user1",
  [string]$Password = "123456"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
  param(
    [string]$Method,
    [string]$Url,
    [object]$Body = $null,
    [string]$Token = ""
  )

  $headers = @{ Accept = "application/json" }
  if ($Token) {
    $headers["Authorization"] = "Bearer $Token"
  }

  if ($null -eq $Body) {
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers
  }

  $headers["Content-Type"] = "application/json"
  $json = $Body | ConvertTo-Json -Depth 20
  return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -Body $json
}

Write-Host "[1/6] Login user $Username"
$login = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/auth/login" -Body @{
  account = $Username
  password = $Password
}
$token = $login.data.token
if (-not $token) {
  $token = $login.data.accessToken
}
if (-not $token) {
  throw "Login response did not include token or accessToken"
}

Write-Host "[2/6] Create Agent session"
$session = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/agent/sessions" -Token $token -Body @{
  title = "Agent Smoke"
}
$sessionId = $session.data.id
if (-not $sessionId) {
  throw "Session response did not include data.id"
}

Write-Host "[3/6] Send Agent message"
$message = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/agent/sessions/$sessionId/messages" -Token $token -Body @{
  content = "帮我给护肤套装写一篇小红书种草文案"
}
$runId = $message.data.runId
if (-not $runId) {
  throw "Message response did not include data.runId"
}

Write-Host "[4/6] Poll Agent events"
$events = @()
for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep -Seconds 1
  $eventPage = Invoke-Json -Method GET -Url "$BaseUrl/api/v1/agent/runs/$runId/events?pageSize=200" -Token $token
  $events = @($eventPage.data.list)
  $types = $events | ForEach-Object { $_.eventType }
  if ($types -contains "tool.confirmation_required" -or $types -contains "run.completed" -or $types -contains "run.failed") {
    break
  }
}

if ($events.Count -eq 0) {
  throw "No Agent events returned for run $runId"
}

Write-Host "[5/6] Inspect terminal or confirmation state"
$eventTypes = $events | ForEach-Object { $_.eventType }
if ($eventTypes -contains "run.failed") {
  throw "Agent run failed during smoke test"
}
if (($eventTypes -notcontains "tool.confirmation_required") -and ($eventTypes -notcontains "run.completed")) {
  throw "Agent run did not reach confirmation or completion"
}

Write-Host "[6/6] Smoke result"
Write-Host "sessionId=$sessionId runId=$runId events=$($eventTypes -join ',')"
```

- [ ] **Step 2: Run smoke script against local backend**

Run:

```powershell
.\scripts\agent-e2e-smoke.ps1 -BaseUrl http://127.0.0.1:8080
```

Expected:

```text
[6/6] Smoke result
sessionId=<number> runId=<number> events=<comma-separated event types>
```

If local backend is not running, expected failure is a connection error. Start the local stack before retrying.

- [ ] **Step 3: Commit smoke script**

Run:

```powershell
git add scripts/agent-e2e-smoke.ps1
git commit -m "test: add agent e2e smoke script"
```

Expected:

```text
Commit succeeds when running inside a git repository.
```

## 5. Phase B: Runtime Abstraction

### Task B1: Add runtime engine protocol

**Files:**

- Create: `agent-service/app/runtime/__init__.py`
- Create: `agent-service/app/runtime/engine.py`
- Create: `agent-service/tests/test_runtime_router.py`

- [ ] **Step 1: Write runtime router test for default engine**

Create `agent-service/tests/test_runtime_router.py` with:

```python
from app.runtime.router import RuntimeRouter
from app.runtime.langgraph_engine import LangGraphRuntimeEngine


def test_runtime_router_defaults_to_langgraph():
    router = RuntimeRouter()

    engine = router.select_engine({"message": "hello"})

    assert isinstance(engine, LangGraphRuntimeEngine)
```

- [ ] **Step 2: Run test to confirm missing router fails**

Run:

```powershell
cd agent-service
pytest tests/test_runtime_router.py -q
```

Expected:

```text
ModuleNotFoundError: No module named 'app.runtime'
```

- [ ] **Step 3: Add runtime package marker**

Create `agent-service/app/runtime/__init__.py` with:

```python
"""Agent runtime engine abstractions."""
```

- [ ] **Step 4: Add runtime engine protocol**

Create `agent-service/app/runtime/engine.py` with:

```python
from typing import Protocol

from app.core.schemas import RunContext


class AgentRuntimeEngine(Protocol):
    async def run(self, context: RunContext) -> None:
        """Execute an Agent run using a concrete runtime."""
        ...
```

- [ ] **Step 5: Leave router and LangGraph engine creation to the next tasks**

Do not create `RuntimeRouter` or `LangGraphRuntimeEngine` in this task. They are introduced in Tasks B2 and B3 so tests fail for the expected next missing piece.

### Task B2: Wrap current graph as LangGraphRuntimeEngine

**Files:**

- Create: `agent-service/app/runtime/langgraph_engine.py`
- Create: `agent-service/tests/test_langgraph_runtime_engine.py`

- [ ] **Step 1: Write wrapper test**

Create `agent-service/tests/test_langgraph_runtime_engine.py` with:

```python
import pytest

from app.runtime.langgraph_engine import LangGraphRuntimeEngine


class FakeGraph:
    def __init__(self):
        self.contexts = []

    async def run(self, context):
        self.contexts.append(context)


@pytest.mark.asyncio
async def test_langgraph_runtime_engine_delegates_to_graph():
    graph = FakeGraph()
    engine = LangGraphRuntimeEngine(graph_factory=lambda backend, model: graph, backend_client=object(), model_client=object())
    context = object()

    await engine.run(context)

    assert graph.contexts == [context]
```

- [ ] **Step 2: Run wrapper test to confirm failure**

Run:

```powershell
cd agent-service
pytest tests/test_langgraph_runtime_engine.py -q
```

Expected:

```text
ModuleNotFoundError or ImportError for app.runtime.langgraph_engine
```

- [ ] **Step 3: Implement LangGraphRuntimeEngine**

Create `agent-service/app/runtime/langgraph_engine.py` with:

```python
from collections.abc import Callable
from typing import Any

from app.core.schemas import RunContext
from app.graphs.universal_agent_graph import UniversalAgentGraph


class LangGraphRuntimeEngine:
    def __init__(
        self,
        backend_client: Any,
        model_client: Any,
        *,
        graph_factory: Callable[[Any, Any], Any] = UniversalAgentGraph,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.graph_factory = graph_factory

    async def run(self, context: RunContext) -> None:
        graph = self.graph_factory(self.backend, self.model)
        await graph.run(context)
```

- [ ] **Step 4: Run wrapper test to verify pass**

Run:

```powershell
cd agent-service
pytest tests/test_langgraph_runtime_engine.py -q
```

Expected:

```text
1 passed
```

### Task B3: Add runtime router

**Files:**

- Create: `agent-service/app/runtime/router.py`
- Modify: `agent-service/tests/test_runtime_router.py`

- [ ] **Step 1: Replace runtime router test with backend/model injection**

Replace `agent-service/tests/test_runtime_router.py` with:

```python
from app.runtime.langgraph_engine import LangGraphRuntimeEngine
from app.runtime.router import RuntimeRouter


def test_runtime_router_defaults_to_langgraph():
    backend_client = object()
    model_client = object()
    router = RuntimeRouter(backend_client=backend_client, model_client=model_client)

    engine = router.select_engine(message="hello", requested_runtime=None)

    assert isinstance(engine, LangGraphRuntimeEngine)
    assert engine.backend is backend_client
    assert engine.model is model_client


def test_runtime_router_uses_langgraph_when_deep_agents_requested_but_disabled():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=False)

    engine = router.select_engine(message="请规划并执行这个复杂任务", requested_runtime="deep_agents")

    assert isinstance(engine, LangGraphRuntimeEngine)
```

- [ ] **Step 2: Run router test to confirm failure**

Run:

```powershell
cd agent-service
pytest tests/test_runtime_router.py -q
```

Expected:

```text
ImportError for RuntimeRouter or TypeError for missing constructor
```

- [ ] **Step 3: Implement RuntimeRouter**

Create `agent-service/app/runtime/router.py` with:

```python
from typing import Any

from app.runtime.langgraph_engine import LangGraphRuntimeEngine


class RuntimeRouter:
    def __init__(
        self,
        backend_client: Any | None = None,
        model_client: Any | None = None,
        *,
        deep_agents_enabled: bool = False,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.deep_agents_enabled = deep_agents_enabled

    def select_engine(self, *, message: str, requested_runtime: str | None = None):
        if requested_runtime == "deep_agents" and self.deep_agents_enabled:
            # DeepAgentsRuntimeEngine is introduced in Phase D.
            return LangGraphRuntimeEngine(self.backend, self.model)
        return LangGraphRuntimeEngine(self.backend, self.model)
```

- [ ] **Step 4: Run router tests**

Run:

```powershell
cd agent-service
pytest tests/test_runtime_router.py -q
```

Expected:

```text
2 passed
```

### Task B4: Use runtime router from AgentRuntime

**Files:**

- Modify: `agent-service/app/core/runtime.py`
- Modify: `agent-service/tests/test_runtime_model_config.py`
- Create: `agent-service/tests/test_agent_runtime_engine_selection.py`

- [ ] **Step 1: Write AgentRuntime engine selection test**

Create `agent-service/tests/test_agent_runtime_engine_selection.py` with:

```python
import pytest

from app.core.runtime import AgentRuntime


class FakeBackend:
    def __init__(self):
        self.events = []
        self.context = type("Context", (), {"runId": 123, "message": "hello"})()
        self.model_config = type(
            "ModelConfig",
            (),
            {
                "enabled": False,
                "provider": "mock",
                "modelName": "mock",
                "baseUrl": "",
                "apiKey": "",
                "minimaxGroupId": "",
                "timeoutSeconds": 60,
            },
        )()

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType))

    async def get_run_context(self, run_id):
        return self.context

    async def get_active_model_config(self):
        return self.model_config


class FakeEngine:
    def __init__(self):
        self.contexts = []

    async def run(self, context):
        self.contexts.append(context)


class FakeRuntimeRouter:
    def __init__(self, engine):
        self.engine = engine
        self.messages = []

    def select_engine(self, *, message, requested_runtime=None):
        self.messages.append((message, requested_runtime))
        return self.engine


@pytest.mark.asyncio
async def test_agent_runtime_uses_runtime_router_engine():
    backend = FakeBackend()
    engine = FakeEngine()
    runtime = AgentRuntime(
        backend,
        model_client=object(),
        runtime_router_factory=lambda backend_client, model_client: FakeRuntimeRouter(engine),
    )

    await runtime.execute_run(123)

    assert engine.contexts == [backend.context]
```

- [ ] **Step 2: Run test to confirm constructor failure**

Run:

```powershell
cd agent-service
pytest tests/test_agent_runtime_engine_selection.py -q
```

Expected:

```text
TypeError: AgentRuntime.__init__() got an unexpected keyword argument 'runtime_router_factory'
```

- [ ] **Step 3: Modify AgentRuntime to accept runtime_router_factory**

In `agent-service/app/core/runtime.py`, add the import:

```python
from app.runtime.router import RuntimeRouter
```

Update the constructor signature:

```python
    def __init__(
        self,
        backend_client: BackendClient,
        model_client: ModelClient | None = None,
        *,
        model_client_factory=ModelClient,
        runtime_router_factory=RuntimeRouter,
        default_settings: Settings | None = None,
    ) -> None:
        self.backend = backend_client
        self.model_client = model_client
        self.model_client_factory = model_client_factory
        self.runtime_router_factory = runtime_router_factory
        self.default_settings = default_settings or Settings()
```

Replace the graph execution line in `execute_run`:

```python
            await UniversalAgentGraph(self.backend, await self._model_client()).run(context)
```

with:

```python
            model_client = await self._model_client()
            engine = self.runtime_router_factory(self.backend, model_client).select_engine(
                message=context.message,
                requested_runtime=None,
            )
            await engine.run(context)
```

Keep `execute_confirmed_tool` on `UniversalAgentGraph` for now because confirmed-tool resume stays on the MVP graph path until Phase D defines Deep Agents interruptions.

- [ ] **Step 4: Run new AgentRuntime test**

Run:

```powershell
cd agent-service
pytest tests/test_agent_runtime_engine_selection.py -q
```

Expected:

```text
1 passed
```

- [ ] **Step 5: Run full agent-service tests**

Run:

```powershell
cd agent-service
pytest -q
```

Expected:

```text
All tests pass.
```

### Task B5: Commit runtime abstraction

**Files:**

- Add/modify all Phase B files.

- [ ] **Step 1: Review changed files**

Run:

```powershell
git status --short
```

Expected:

```text
Shows only Phase B runtime files and Phase A files if not committed yet.
```

- [ ] **Step 2: Commit runtime abstraction**

Run:

```powershell
git add agent-service/app/runtime agent-service/app/core/runtime.py agent-service/tests/test_runtime_router.py agent-service/tests/test_langgraph_runtime_engine.py agent-service/tests/test_agent_runtime_engine_selection.py
git commit -m "feat: add agent runtime engine abstraction"
```

Expected:

```text
Commit succeeds when running inside a git repository.
```

## 6. Phase C-G Planning Tasks

### Task C1: Create detailed Workspace Memory MVP plan

**Files:**

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-c-workspace-memory.md`

- [ ] **Step 1: Create plan header**

The plan must start with:

```markdown
# Agent Platform Phase C Workspace Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add workspace-scoped memory so Agent sessions can persist and retrieve project context across runs.

**Architecture:** Spring Boot owns workspace, memory persistence, permissions, and APIs. Agent Service requests workspace memory through signed backend APIs and treats returned memory as cited context. user-web exposes memory inspection and deletion inside the Agent workspace surface.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, MySQL/H2, FastAPI, LangGraph, Vue 3, Pinia.

---
```

- [ ] **Step 2: Include at least these implementation tasks**

The detailed Phase C plan must include TDD tasks for:

```text
backend workspace schema and migration
backend workspace ownership tests
session-to-workspace compatibility
workspace memory entity and CRUD APIs
internal memory retrieval API
agent-service backend client memory retrieval
LangGraph context injection
user-web memory panel
backend, agent-service, and user-web verification
```

### Task D1: Create detailed Deep Agents Preview Runtime plan

**Files:**

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-d-deep-agents-runtime.md`

- [ ] **Step 1: Create plan header**

The plan must start with:

```markdown
# Agent Platform Phase D Deep Agents Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a feature-flagged Deep Agents runtime for read-only workspace planning, file reasoning, and sub-agent delegation.

**Architecture:** Deep Agents runs inside FastAPI `agent-service` behind `AGENT_DEEP_AGENTS_ENABLED=false` by default. It uses backend-provided context, virtual file access, platform-approved tools, and budget-limited sub-agent traces. It never directly accesses host files, business tables, shell, browser, or external network.

**Tech Stack:** FastAPI, LangChain, LangGraph, Deep Agents, Spring Boot signed internal APIs.

---
```

- [ ] **Step 2: Include at least these implementation tasks**

The detailed Phase D plan must include TDD tasks for:

```text
deepagents optional dependency and config
DeepAgentsRuntimeEngine skeleton
runtime router feature flag behavior
virtual file read API bridge
generated artifact write API bridge
sub-agent trace event model
budget and permission enforcement
read-only workspace task smoke test
```

### Task E1: Create detailed Tool Policy Expansion plan

**Files:**

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-e-tool-policy-expansion.md`

- [ ] **Step 1: Create plan header**

The plan must start with:

```markdown
# Agent Platform Phase E Tool Policy Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand marketplace tools into policy-controlled Agent tools with side-effect levels, schema validation, argument extraction, and confirmation rules.

**Architecture:** Spring Boot emits signed tool descriptors and enforces final execution policy. Agent Service selects, extracts, and validates arguments but can only call backend-approved tools. user-web renders side-effect-aware confirmation cards.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, FastAPI, LangChain structured output, JSON Schema, Vue 3.

---
```

- [ ] **Step 2: Include at least these implementation tasks**

The detailed Phase E plan must include TDD tasks for:

```text
tool side-effect enum
descriptor schema upgrade
JSON Schema conversion tests
agent-service tool selection scoring
argument extraction
schema validation
confirmation card payload
unsafe tool denial
```

### Task F1: Create detailed Knowledge Base Plugin plan

**Files:**

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-f-knowledge-base-plugin.md`

- [ ] **Step 1: Create plan header**

The plan must start with:

```markdown
# Agent Platform Phase F Knowledge Base Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add business knowledge as a permissioned, cited, pluggable context source separate from workspace memory.

**Architecture:** Spring Boot owns knowledge bases, document permissions, ingestion metadata, and retrieval APIs. Agent Service requests cited snippets through signed APIs and labels them separately from workspace memory and uploaded files.

**Tech Stack:** Spring Boot 3, MySQL/H2, FastAPI, LangChain text splitting/retrieval interfaces, Vue 3.

---
```

- [ ] **Step 2: Include at least these implementation tasks**

The detailed Phase F plan must include TDD tasks for:

```text
knowledge base tables
document ingestion metadata
permission checks
chunk retrieval
citation payloads
agent-service context injection
frontend source display
```

### Task G1: Create detailed Production Hardening plan

**Files:**

- Create: `docs/superpowers/plans/2026-05-10-agent-platform-phase-g-production-hardening.md`

- [ ] **Step 1: Create plan header**

The plan must start with:

```markdown
# Agent Platform Phase G Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the Agent platform for long-running production use with checkpointing, resume, observability, audit export, and workspace sharing.

**Architecture:** Spring Boot coordinates durable state, audit, sharing, and operational APIs. Agent Service exposes runtime trace summaries and checkpoint-aware execution. Frontend surfaces long-task status, cancellation, and audit summaries without exposing hidden chain-of-thought.

**Tech Stack:** Spring Boot 3, Redis, MySQL, FastAPI, LangGraph checkpointing, Deep Agents runtime, Vue 3.

---
```

- [ ] **Step 2: Include at least these implementation tasks**

The detailed Phase G plan must include TDD tasks for:

```text
checkpoint storage decision
run resume API
audit export
metrics emission
workspace sharing permissions
long-task cancellation regression
```

## 7. Global Verification Gates

After each phase, run:

```powershell
mvn -f backend\pom.xml test
cd agent-service
pytest -q
cd ..\user-web
node node_modules\vue-tsc\bin\vue-tsc.js --noEmit
npm run build
```

Expected:

```text
Backend BUILD SUCCESS.
agent-service tests pass.
user-web typecheck and build pass.
```

Before enabling Deep Agents in any non-local environment, also verify:

```text
AGENT_DEEP_AGENTS_ENABLED is explicitly set.
No shell execution tool is registered.
No host filesystem path is exposed to the runtime.
All generated artifacts go through backend APIs.
All sub-agent actions are attached to a parent run.
```

## 8. Plan Self-Review

Spec coverage:

- Framework positioning maps to Phase B and Phase D.
- Target architecture maps to all phases and the global boundary rules.
- Runtime router maps to Phase B.
- Workspace model and memory strategy map to Phase C.
- File workspace and sub-agent model map to Phase D.
- Tool marketplace integration maps to Phase E.
- Permissions and safety map to Phase D/E/G.
- Credits, limits, and long tasks map to Phase A/G plus later detailed plans.
- Observability and audit map to Phase G.
- Knowledge memory plugin maps to Phase F.

Known execution note:

- The current workspace path is not a git repository. Commit steps are included because future execution should happen inside the actual repository or an isolated worktree. If the same non-git workspace is used, record changed files instead of committing.
