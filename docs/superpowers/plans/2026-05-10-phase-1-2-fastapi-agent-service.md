# Phase 1.2 FastAPI Agent Service Framework Implementation Plan

> Superseded: Phase 1 implementation should now follow `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`. This file is retained only as historical detail.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the standalone FastAPI Agent Service that executes the Cloud Universal Agent MVP using LangGraph, LangChain-style tool abstractions, signed Spring Boot internal APIs, and a minimal intent router.

**Architecture:** The Agent Service is an internal Python service. Spring Boot owns users, sessions, runs, tools, credits, and persistence; FastAPI owns intent routing, model orchestration, tool selection, LangGraph execution, and run callbacks to Spring Boot.

**Tech Stack:** FastAPI, Uvicorn, Pydantic, httpx, LangGraph, LangChain, python-dotenv, pytest, respx, Redis optional for local runtime locks, existing HMAC internal signature contract.

---

## 1. Scope

Phase 1.2 builds only the `agent-service/` framework.

It includes:

- FastAPI project skeleton.
- Health endpoint.
- Internal execute-run endpoint.
- Backend client with HMAC signing.
- Model client with mock and OpenAI-compatible chat completion support.
- LangGraph universal agent graph.
- Minimal intent router.
- Tool registry generated from Spring Boot run context.
- Backend tool call bridge.
- Run event callbacks.
- Run complete/fail callbacks.
- Unit tests for signature, intent routing, graph flow, and backend client behavior.
- Dockerfile and Compose service design.

It does not include:

- Next.js Agent UI.
- Spring Boot module implementation.
- Public browser access to Agent Service.
- RAG.
- File upload.
- Vector database.
- RabbitMQ/Celery.
- Multi-agent collaboration.
- Production LangSmith/Grafana/ELK integration.

---

## 2. Dependency On Phase 1.1

This service consumes the Spring Boot internal APIs defined in:

`docs/superpowers/plans/2026-05-10-phase-1-1-spring-boot-agent-module.md`

Required internal APIs:

```text
GET  /api/internal/v1/agent/runs/{runId}/context
POST /api/internal/v1/agent/runs/{runId}/events
POST /api/internal/v1/agent/runs/{runId}/tool-calls
POST /api/internal/v1/agent/tool-calls/{toolCallId}/complete
POST /api/internal/v1/agent/tool-calls/{toolCallId}/fail
POST /api/internal/v1/agent/runs/{runId}/complete
POST /api/internal/v1/agent/runs/{runId}/fail
```

The Agent Service must not bypass Spring Boot to access business database tables.

---

## 3. Service Boundary

### 3.1 Agent Service can do

- Load run context from Spring Boot.
- Classify user intent.
- Select an allowed tool from `availableTools`.
- Generate tool arguments.
- Call Spring Boot internal tool call endpoints.
- Call model provider.
- Stream or append run events through Spring Boot.
- Complete or fail a run through Spring Boot.

### 3.2 Agent Service cannot do

- Authenticate browser users.
- Modify credit accounts directly.
- Read or write Spring Boot database tables directly.
- Access tools not returned by Spring Boot.
- Access files or knowledge bases in Phase 1.2.
- Execute arbitrary code.

---

## 4. Directory Structure

Create:

```text
agent-service/
  app/
    __init__.py
    main.py
    config.py
    api/
      __init__.py
      health.py
      internal_runs.py
    clients/
      __init__.py
      backend_client.py
      model_client.py
    core/
      __init__.py
      event_types.py
      intent_router.py
      runtime.py
      schemas.py
    graphs/
      __init__.py
      universal_agent_graph.py
    tools/
      __init__.py
      backend_tool.py
      registry.py
      schemas.py
    security/
      __init__.py
      signature.py
    observability/
      __init__.py
      logging.py
  tests/
    __init__.py
    test_signature.py
    test_intent_router.py
    test_tool_registry.py
    test_universal_graph.py
    test_backend_client.py
  .env.example
  requirements.txt
  Dockerfile
  README.md
```

---

## 5. Runtime Flow

Spring Boot creates an Agent run, then calls:

```text
POST /internal/v1/agent/runs/{runId}/execute
```

FastAPI executes:

```text
receive runId
  -> append run.started event
  -> load context from Spring Boot
  -> classify intent
  -> append intent.detected event
  -> route:
       general_chat -> model response
       tool_use -> select tool -> build args -> call tool bridge -> model synthesis
       needs_clarification -> ask clarifying question
       unsupported -> graceful answer
  -> complete run with final answer
```

On any unrecoverable error:

```text
append run.failed event
fail run through Spring Boot
```

---

## 6. API Design

### 6.1 Health

```text
GET /health
```

Response:

```json
{
  "service": "agent-service",
  "status": "ok"
}
```

### 6.2 Execute Run

```text
POST /internal/v1/agent/runs/{runId}/execute
```

This endpoint is called by Spring Boot.

Phase 1.2 request body can be empty:

```json
{}
```

Response:

```json
{
  "runId": 10001,
  "status": "accepted"
}
```

Execution mode in Phase 1.2:

- Initial implementation can run synchronously inside the request for simpler integration tests.
- Before production load testing, convert to FastAPI `BackgroundTasks` or an internal async task queue.
- The API contract remains the same.

### 6.3 Internal API Security

Phase 1.2 should verify signatures on inbound Spring Boot calls if Spring Boot signs Agent Service requests.

Headers:

```text
X-Internal-Timestamp
X-Internal-Nonce
X-Internal-Signature
```

Signature format must match current backend verifier:

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256_HEX(BODY)
```

For local development, config may allow:

```text
AGENT_VERIFY_INTERNAL_SIGNATURE=false
```

Production must require:

```text
AGENT_VERIFY_INTERNAL_SIGNATURE=true
```

---

## 7. Configuration

### 7.1 `.env.example`

```text
APP_ENV=local
HOST=0.0.0.0
PORT=8090
LOG_LEVEL=INFO

BACKEND_INTERNAL_BASE_URL=http://127.0.0.1:8080
INTERNAL_API_TOKEN=local-internal-token
AGENT_VERIFY_INTERNAL_SIGNATURE=false

MODEL_PROVIDER=mock
MODEL_API_BASE_URL=https://api.deepseek.com
MODEL_API_KEY=replace-with-model-key
MODEL_NAME=deepseek-chat
MODEL_TIMEOUT_SECONDS=60

AGENT_MAX_TOOL_CALLS=3
AGENT_MAX_HISTORY_MESSAGES=20
AGENT_DEFAULT_CONSUMED_CREDITS=1
```

### 7.2 `Settings`

Use Pydantic settings or dataclass settings.

Required fields:

```text
app_env
host
port
log_level
backend_internal_base_url
internal_api_token
agent_verify_internal_signature
model_provider
model_api_base_url
model_api_key
model_name
model_timeout_seconds
agent_max_tool_calls
agent_max_history_messages
agent_default_consumed_credits
```

Recommendation:

- Use Pydantic `BaseSettings` if available.
- Use simple dataclass + `os.getenv` if avoiding extra dependency.

For consistency with the existing `worker/config.py`, a dataclass is acceptable in Phase 1.2.

---

## 8. Backend Client

`app/clients/backend_client.py`

Use `httpx.AsyncClient`.

### 8.1 Methods

```python
async def get_run_context(run_id: int) -> RunContext: ...
async def append_event(run_id: int, event: RunEventCreate) -> None: ...
async def create_tool_call(run_id: int, request: ToolCallCreate) -> ToolCallResponse: ...
async def complete_tool_call(tool_call_id: int, request: ToolCallComplete) -> None: ...
async def fail_tool_call(tool_call_id: int, request: ToolCallFail) -> None: ...
async def complete_run(run_id: int, request: RunComplete) -> None: ...
async def fail_run(run_id: int, request: RunFail) -> None: ...
```

### 8.2 HMAC signing

Port the existing worker logic from:

```text
worker/client/backend_client.py
```

But adapt it to:

- async `httpx`.
- Pydantic/dataclass payloads.
- canonical compact JSON body.
- clear unit tests.

### 8.3 Response parsing

Spring Boot returns:

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {}
}
```

Rules:

- HTTP non-2xx -> raise `BackendClientError`.
- JSON parse failure -> raise `BackendClientError`.
- `code != SUCCESS` -> raise `BackendBusinessError`.
- missing data is treated as `{}` for void callbacks.

---

## 9. Model Client

`app/clients/model_client.py`

### 9.1 Provider modes

Support:

```text
mock
openai_compatible
```

`mock` mode returns deterministic responses for local development and tests.

`openai_compatible` mode posts to:

```text
{MODEL_API_BASE_URL}/chat/completions
```

Payload:

```json
{
  "model": "deepseek-chat",
  "messages": [
    {
      "role": "system",
      "content": "..."
    },
    {
      "role": "user",
      "content": "..."
    }
  ],
  "stream": false
}
```

### 9.2 Phase 1.2 streaming choice

Phase 1.2 should not depend on provider-native streaming.

Instead:

- generate full text from model.
- split answer into small chunks.
- append `message.delta` events.
- complete run.

Native streaming is deferred to Phase 1.5.

This keeps Phase 1.2 focused on the Agent Service framework.

---

## 10. Pydantic Schemas

Create `app/core/schemas.py`.

### 10.1 RunContext

```python
class ChatMessage(BaseModel):
    role: str
    content: str

class ToolDescriptor(BaseModel):
    toolCode: str
    name: str
    description: str
    creditCost: int = 0
    inputSchema: dict[str, Any] = Field(default_factory=dict)
    autoCallable: bool = False

class RunContext(BaseModel):
    runId: int
    sessionId: int
    userId: int
    message: str
    history: list[ChatMessage] = Field(default_factory=list)
    availableTools: list[ToolDescriptor] = Field(default_factory=list)
    creditBudget: int = 0
```

### 10.2 Events

```python
class RunEventCreate(BaseModel):
    eventType: str
    eventText: str | None = None
    eventJson: dict[str, Any] | None = None
```

### 10.3 Run completion

```python
class RunComplete(BaseModel):
    finalAnswer: str
    intent: str
    modelProviderCode: str | None = None
    modelName: str | None = None
    consumedCredits: int

class RunFail(BaseModel):
    errorCode: str
    errorMessage: str
```

---

## 11. Event Types

Create constants in `app/core/event_types.py`:

```python
RUN_STARTED = "run.started"
INTENT_DETECTED = "intent.detected"
TOOL_SELECTED = "tool.selected"
TOOL_STARTED = "tool.started"
TOOL_FINISHED = "tool.finished"
MESSAGE_DELTA = "message.delta"
MESSAGE_COMPLETED = "message.completed"
RUN_COMPLETED = "run.completed"
RUN_FAILED = "run.failed"
```

All event type strings must match Phase 1.1.

---

## 12. Intent Router

`app/core/intent_router.py`

### 12.1 Intent enum

```text
general_chat
tool_use
needs_clarification
unsupported
rag
file_analysis
workflow
```

Phase 1.2 only executes:

```text
general_chat
tool_use
needs_clarification
unsupported
```

### 12.2 Heuristic first implementation

Do not require an LLM classifier in Phase 1.2.

Use deterministic heuristics:

`tool_use` if:

- message contains a known tool keyword.
- or message matches tool description keywords.
- or tool code semantic keywords match.

`needs_clarification` if:

- message length is very short.
- message asks to "帮我做一下" without object or target.

`unsupported` if:

- message asks for file analysis, knowledge base, upload, RAG, or multi-agent workflow before those features exist.

`general_chat` otherwise.

### 12.3 Tool matching keywords

Initial mapping:

```text
xiaohongshu_copywriting:
  小红书, 种草, 笔记, 爆款笔记

moments_copywriting_generator:
  朋友圈, 微信朋友圈, 私域文案

product_title_optimizer:
  商品标题, 标题优化, 电商标题

wechat_longform_generator:
  公众号, 微信长文, 长文
```

### 12.4 Output

```python
class IntentResult(BaseModel):
    intent: str
    confidence: float
    selectedToolCode: str | None = None
    reason: str
```

---

## 13. Tool Registry

`app/tools/registry.py`

Build registry from `RunContext.availableTools`.

Methods:

```python
def list_tools(self) -> list[ToolDescriptor]: ...
def get(self, tool_code: str) -> ToolDescriptor | None: ...
def match_by_intent(self, message: str) -> ToolDescriptor | None: ...
```

Rules:

- Only tools with `autoCallable=true` can be selected automatically.
- Unknown tools cannot be called.
- If selected tool is not in registry, downgrade to `general_chat` or `needs_clarification`.

---

## 14. Backend Tool Bridge

`app/tools/backend_tool.py`

This does not execute the original AI tool directly.

It asks Spring Boot to record and execute or approve the tool call.

Phase 1.2 implementation can be minimal:

```text
create tool call
append tool.started
return a mock structured tool result if Spring Boot tool execution is not implemented yet
complete tool call
```

When Spring Boot exposes real tool execution later, replace the mock result with the actual internal execution endpoint.

### 14.1 Tool argument generation

Phase 1.2 should keep this simple.

For selected known tools:

```python
arguments = {
    "userRequest": context.message
}
```

If tool schema requires specific fields and the Agent Service can infer them, add:

```python
{
  "productName": "...",
  "targetCustomer": "...",
  "style": "..."
}
```

Do not overbuild schema inference in Phase 1.2. Phase 1.4 will specialize tool argument generation.

---

## 15. LangGraph Universal Graph

`app/graphs/universal_agent_graph.py`

### 15.1 State

```python
class AgentState(TypedDict):
    run_id: int
    context: RunContext
    intent: IntentResult | None
    selected_tool: ToolDescriptor | None
    tool_result: dict[str, Any] | None
    final_answer: str | None
    error_code: str | None
    error_message: str | None
```

### 15.2 Nodes

```text
classify_intent
select_tool
execute_tool
generate_chat_answer
generate_clarifying_answer
generate_unsupported_answer
synthesize_tool_answer
complete_run
fail_run
```

### 15.3 Routing

```text
classify_intent
  -> tool_use: select_tool -> execute_tool -> synthesize_tool_answer -> complete_run
  -> general_chat: generate_chat_answer -> complete_run
  -> needs_clarification: generate_clarifying_answer -> complete_run
  -> unsupported: generate_unsupported_answer -> complete_run
```

### 15.4 Events

Each node should append events through BackendClient:

- `classify_intent` -> `intent.detected`
- `select_tool` -> `tool.selected`
- `execute_tool` -> `tool.started`, `tool.finished`
- answer nodes -> `message.delta`, `message.completed`
- complete -> `run.completed`
- fail -> `run.failed`

---

## 16. Runtime Service

`app/core/runtime.py`

Methods:

```python
class AgentRuntime:
    async def execute_run(self, run_id: int) -> None:
        ...
```

Responsibilities:

1. Append `run.started`.
2. Load context.
3. Execute universal graph.
4. Catch exceptions.
5. Fail run on unrecoverable error.

Exception mapping:

```text
BackendClientError -> BACKEND_CALL_FAILED
ModelClientError -> MODEL_CALL_FAILED
IntentRouterError -> INTENT_ROUTER_FAILED
ToolExecutionError -> TOOL_CALL_FAILED
Unexpected -> AGENT_INTERNAL_ERROR
```

---

## 17. Internal Signature Verification

`app/security/signature.py`

### 17.1 Sign outgoing backend requests

Function:

```python
def signature_headers(method: str, path: str, body: bytes, secret: str) -> dict[str, str]:
    ...
```

Use the same algorithm as existing worker:

```python
body_hash = hashlib.sha256(body).hexdigest()
content = "\n".join([method.upper(), path, timestamp, nonce, body_hash])
signature = hmac.new(secret.encode("utf-8"), content.encode("utf-8"), hashlib.sha256).hexdigest()
```

### 17.2 Verify incoming requests

Function:

```python
def verify_signature(method: str, path: str, timestamp: str, nonce: str, signature: str, body: bytes, secret: str) -> bool:
    ...
```

Rules:

- Reject missing fields.
- Reject timestamp older than 5 minutes.
- Use constant-time comparison.
- Nonce replay protection can be in-memory for Phase 1.2 local dev.
- Redis-backed nonce storage should be added before production.

---

## 18. FastAPI App

`app/main.py`

Responsibilities:

- Configure logging.
- Create FastAPI app.
- Include routers.
- Register exception handlers.
- Provide dependency injection for BackendClient, ModelClient, AgentRuntime.

Routers:

```python
app.include_router(health.router)
app.include_router(internal_runs.router)
```

`app/api/internal_runs.py`:

```python
@router.post("/internal/v1/agent/runs/{run_id}/execute")
async def execute_run(run_id: int, background_tasks: BackgroundTasks):
    background_tasks.add_task(runtime.execute_run, run_id)
    return {"runId": run_id, "status": "accepted"}
```

For tests, allow synchronous execution through config:

```text
AGENT_EXECUTION_MODE=sync
```

Modes:

```text
sync
background
```

---

## 19. Dockerfile

Create `agent-service/Dockerfile`:

```dockerfile
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
```

---

## 20. requirements.txt

Initial dependencies:

```text
fastapi>=0.115.0
uvicorn[standard]>=0.30.0
pydantic>=2.8.0
httpx>=0.27.0
python-dotenv>=1.0.0
langchain>=0.3.0
langgraph>=0.2.0
pytest>=8.0.0
pytest-asyncio>=0.23.0
respx>=0.21.0
```

If dependency resolution conflicts, prefer latest compatible versions and record the final pinned versions in `requirements.txt`.

---

## 21. Docker Compose Integration

Modify later in:

```text
deploy/docker-compose.yml
```

Add:

```yaml
  agent-service:
    image: python:3.12-slim
    container_name: ai-supermarket-agent-service
    working_dir: /app
    restart: unless-stopped
    depends_on:
      - backend
      - redis
    environment:
      APP_ENV: local
      PORT: 8090
      BACKEND_INTERNAL_BASE_URL: http://backend:8080
      INTERNAL_API_TOKEN: ${INTERNAL_API_TOKEN:-local-internal-token}
      AGENT_VERIFY_INTERNAL_SIGNATURE: "false"
      MODEL_PROVIDER: ${MODEL_PROVIDER:-mock}
      MODEL_API_BASE_URL: ${MODEL_API_BASE_URL:-https://api.deepseek.com}
      MODEL_API_KEY: ${MODEL_API_KEY:-replace-with-model-key}
      MODEL_NAME: ${MODEL_NAME:-deepseek-chat}
    ports:
      - "${AGENT_SERVICE_PORT:-8090}:8090"
    volumes:
      - ../agent-service:/app
    command: sh -c "pip install --no-cache-dir -r requirements.txt && uvicorn app.main:app --host 0.0.0.0 --port 8090"
```

---

## 22. Testing Strategy

### 22.1 Unit tests

`test_signature.py`

- outgoing signature matches known fixture.
- verification accepts valid signature.
- verification rejects missing timestamp.
- verification rejects stale timestamp.
- verification rejects wrong signature.

`test_intent_router.py`

- message with "小红书" routes to `tool_use`.
- message with "商品标题" routes to `tool_use`.
- vague message routes to `needs_clarification`.
- file analysis request routes to `unsupported`.
- normal question routes to `general_chat`.

`test_tool_registry.py`

- only autoCallable tools are selectable.
- unknown tool returns none.
- inactive or unavailable tool is not selected.

`test_universal_graph.py`

- general chat completes run.
- tool use creates and completes tool call.
- unsupported request completes with graceful answer.
- backend failure fails run.

`test_backend_client.py`

- parses Spring Boot `ApiResponse`.
- raises on business error.
- signs request headers.

### 22.2 Integration smoke test

After Phase 1.1 exists:

```text
1. Start backend.
2. Start agent-service.
3. Create Agent run through backend.
4. Call agent-service execute endpoint.
5. Verify backend run becomes SUCCESS.
6. Verify events exist.
7. Verify assistant message exists.
```

---

## 23. Implementation Tasks

### Task 1: Create agent-service skeleton

**Files:**

- Create: `agent-service/app/main.py`
- Create: `agent-service/app/config.py`
- Create: `agent-service/app/api/health.py`
- Create: `agent-service/requirements.txt`
- Create: `agent-service/.env.example`
- Create: `agent-service/README.md`

- [ ] **Step 1: Create directories and empty `__init__.py` files**

Create all directories listed in Section 4.

- [ ] **Step 2: Add settings loader**

Implement config fields from Section 7.

- [ ] **Step 3: Add health router**

Implement `GET /health`.

- [ ] **Step 4: Run service locally**

Run:

```powershell
cd agent-service
python -m uvicorn app.main:app --reload --port 8090
```

Expected:

```text
GET http://localhost:8090/health returns status ok
```

### Task 2: Implement signature module

**Files:**

- Create: `agent-service/app/security/signature.py`
- Create: `agent-service/tests/test_signature.py`

- [ ] **Step 1: Write signature tests**

Cover valid, invalid, stale, missing header cases.

- [ ] **Step 2: Implement outgoing signature generation**

Match Spring Boot verifier and existing worker logic.

- [ ] **Step 3: Implement incoming signature verification**

Use constant-time comparison.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_signature.py -q
```

Expected:

```text
All tests pass
```

### Task 3: Implement schemas

**Files:**

- Create: `agent-service/app/core/schemas.py`
- Create: `agent-service/app/tools/schemas.py`

- [ ] **Step 1: Add RunContext schemas**

Implement `ChatMessage`, `ToolDescriptor`, `RunContext`.

- [ ] **Step 2: Add callback schemas**

Implement event, run complete/fail, tool call schemas.

- [ ] **Step 3: Run import smoke test**

```powershell
cd agent-service
python -c "from app.core.schemas import RunContext; print(RunContext)"
```

Expected:

```text
Prints RunContext class
```

### Task 4: Implement backend client

**Files:**

- Create: `agent-service/app/clients/backend_client.py`
- Create: `agent-service/tests/test_backend_client.py`

- [ ] **Step 1: Write backend client tests with respx**

Mock Spring Boot responses.

- [ ] **Step 2: Implement async HTTP client**

Use `httpx.AsyncClient`.

- [ ] **Step 3: Add HMAC headers**

Use `signature_headers`.

- [ ] **Step 4: Add ApiResponse parsing**

Handle success and business errors.

- [ ] **Step 5: Run tests**

```powershell
cd agent-service
pytest tests/test_backend_client.py -q
```

Expected:

```text
All tests pass
```

### Task 5: Implement model client

**Files:**

- Create: `agent-service/app/clients/model_client.py`
- Create: `agent-service/tests/test_model_client.py`

- [ ] **Step 1: Implement mock provider**

Return deterministic answer containing the user message summary.

- [ ] **Step 2: Implement OpenAI-compatible provider**

Use `/chat/completions`.

- [ ] **Step 3: Add tests for mock mode**

Ensure mock returns non-empty content.

- [ ] **Step 4: Add tests for error mapping**

Model timeout maps to `ModelClientError` subtype.

### Task 6: Implement intent router

**Files:**

- Create: `agent-service/app/core/intent_router.py`
- Create: `agent-service/tests/test_intent_router.py`

- [ ] **Step 1: Write tests from Section 22.1**

Cover tool use, clarification, unsupported, and general chat.

- [ ] **Step 2: Implement heuristic router**

Use keyword rules from Section 12.

- [ ] **Step 3: Run tests**

```powershell
cd agent-service
pytest tests/test_intent_router.py -q
```

Expected:

```text
All tests pass
```

### Task 7: Implement tool registry and backend tool bridge

**Files:**

- Create: `agent-service/app/tools/registry.py`
- Create: `agent-service/app/tools/backend_tool.py`
- Create: `agent-service/tests/test_tool_registry.py`

- [ ] **Step 1: Write registry tests**

Ensure only available auto-callable tools can be matched.

- [ ] **Step 2: Implement registry**

Build from `RunContext.availableTools`.

- [ ] **Step 3: Implement backend tool bridge**

Create tool call, complete tool call, return structured result.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_tool_registry.py -q
```

Expected:

```text
All tests pass
```

### Task 8: Implement universal LangGraph

**Files:**

- Create: `agent-service/app/graphs/universal_agent_graph.py`
- Create: `agent-service/tests/test_universal_graph.py`

- [ ] **Step 1: Write graph tests**

Use fake BackendClient and fake ModelClient.

- [ ] **Step 2: Implement state and nodes**

Implement nodes from Section 15.

- [ ] **Step 3: Implement routing**

Route by intent.

- [ ] **Step 4: Emit events**

Call fake backend in tests.

- [ ] **Step 5: Run tests**

```powershell
cd agent-service
pytest tests/test_universal_graph.py -q
```

Expected:

```text
All tests pass
```

### Task 9: Implement runtime and execute endpoint

**Files:**

- Create: `agent-service/app/core/runtime.py`
- Create: `agent-service/app/api/internal_runs.py`
- Modify: `agent-service/app/main.py`
- Create: `agent-service/tests/test_internal_runs_api.py`

- [ ] **Step 1: Implement AgentRuntime**

Load context and execute graph.

- [ ] **Step 2: Implement execute endpoint**

Support sync and background mode.

- [ ] **Step 3: Add optional incoming signature verification**

Use `AGENT_VERIFY_INTERNAL_SIGNATURE`.

- [ ] **Step 4: Run API tests**

```powershell
cd agent-service
pytest tests/test_internal_runs_api.py -q
```

Expected:

```text
All tests pass
```

### Task 10: Add Docker and Compose support

**Files:**

- Create: `agent-service/Dockerfile`
- Modify: `deploy/docker-compose.yml`

- [ ] **Step 1: Add Dockerfile**

Use Section 19.

- [ ] **Step 2: Add docker-compose service**

Use Section 21.

- [ ] **Step 3: Build service**

```powershell
docker compose -f deploy/docker-compose.yml build agent-service
```

Expected:

```text
Build succeeds
```

### Task 11: Final verification

- [ ] **Step 1: Run all agent-service tests**

```powershell
cd agent-service
pytest -q
```

- [ ] **Step 2: Start local service**

```powershell
cd agent-service
python -m uvicorn app.main:app --port 8090
```

- [ ] **Step 3: Health check**

```powershell
curl http://localhost:8090/health
```

Expected:

```json
{"service":"agent-service","status":"ok"}
```

- [ ] **Step 4: Integration check after Phase 1.1**

Use a real Spring Boot run id and call:

```powershell
curl -X POST http://localhost:8090/internal/v1/agent/runs/{runId}/execute
```

Expected:

```text
Agent Service accepts run, calls backend context, appends events, and completes or fails run.
```

---

## 24. Acceptance Criteria

Phase 1.2 is complete when:

- `agent-service/` exists and starts with FastAPI.
- `GET /health` returns ok.
- `POST /internal/v1/agent/runs/{runId}/execute` exists.
- Backend client signs requests using the same HMAC contract as Spring Boot.
- Intent router handles `general_chat`, `tool_use`, `needs_clarification`, and `unsupported`.
- Tool registry only selects Spring Boot-provided auto-callable tools.
- Universal LangGraph can complete general chat.
- Universal LangGraph can select and complete a backend tool call.
- Runtime fails runs through Spring Boot on unrecoverable errors.
- Mock model mode works without real API keys.
- Tests pass with `pytest -q`.
- Dockerfile builds.
- Compose service definition exists.

---

## 25. Handoff To Next Phase

After this document is implemented, the next development document should be:

```text
Phase 1.3：Next.js Agent Chat 前端开发文档
```

That document will consume:

- Spring Boot public session/message/run APIs from Phase 1.1.
- Run event stream or polling APIs from Phase 1.1 / Phase 1.5.
- Event types emitted by this Agent Service.

The frontend must not call Agent Service directly in Phase 1.
