# Phase 1.4 Agent Tool Registration And Auto-Call Implementation Plan

> Superseded: Phase 1 implementation should now follow `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`. This file is retained only as historical detail.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make existing AI Tool Market tools safely usable by the cloud Agent through stable tool descriptors, JSON Schema exposure, intent matching, natural-language argument extraction, controlled execution, and frontend result rendering.

**Architecture:** Spring Boot remains the source of truth for tool metadata, permissions, credit cost, and execution approval. FastAPI Agent Service consumes signed tool descriptors, selects only auto-callable tools, generates schema-valid arguments, and calls Spring Boot internal tool execution bridge. Next.js renders tool-call progress and results from run events.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, existing tool module, FastAPI, LangGraph, LangChain-style tool abstractions, Pydantic, JSON Schema, Next.js, TypeScript, shadcn/ui.

---

## 1. Scope

Phase 1.4 specializes the tool layer introduced in Phases 1.1-1.3.

It includes:

- Spring Boot tool descriptor quality improvements.
- Tool field to JSON Schema conversion.
- Agent auto-call policy.
- Agent-safe tool registry.
- Natural language to tool argument extraction.
- Tool argument validation.
- Tool execution bridge.
- Tool call event enrichment.
- Frontend tool-call rendering contract.
- Tests for descriptor conversion, tool selection, argument validation, and execution flow.

It does not include:

- New user-created custom tools.
- Arbitrary code execution.
- RAG tools.
- File tools.
- Browser tools.
- Payment or order-related sensitive tools.
- Multi-step workflow planner.
- RabbitMQ/Celery long task execution.

---

## 2. Current Tool Context

The current backend already has:

- `ai_tools`: tool metadata.
- `tool_field_schemas`: active field schema version.
- `tool_field_schema_items`: field definitions.
- `ToolDetailResponse`: tool detail plus fields.
- `ToolFieldResponse`: `fieldKey`, `fieldName`, `fieldType`, `placeholder`, `options`, `required`, `sortOrder`.
- `TaskService`: creates tool tasks from `toolCode` and params.
- Python `worker/tools/*`: prompt builders, parsers, examples, and tool-specific contracts.

Phase 1.4 should reuse the existing tool definitions instead of creating a separate tool registry table for the MVP.

---

## 3. Target Tool Flow

```text
Spring Boot
  -> converts online tools to Agent descriptors
  -> exposes descriptors in run context

Agent Service
  -> builds ToolRegistry from descriptors
  -> matches user intent to one tool
  -> extracts arguments from natural language
  -> validates arguments against JSON Schema
  -> asks Spring Boot to execute approved tool

Spring Boot
  -> validates tool availability and credits
  -> creates regular ai_task or synchronous tool execution result
  -> records agent_tool_calls
  -> returns tool result summary

Agent Service
  -> synthesizes final assistant answer
  -> completes Agent run

Next.js
  -> shows tool selection, arguments, progress, result
```

---

## 4. First Auto-Callable Tools

Initial allowlist:

```text
xiaohongshu_copywriting
moments_copywriting_generator
product_title_optimizer
wechat_longform_generator
```

Reasons:

- They are content-generation tools.
- They are relatively safe for automatic execution.
- They already have worker-side assets or seed data.
- Their output is textual and easy to render in chat.

Not auto-callable in Phase 1.4:

```text
payment tools
account mutation tools
admin tools
file deletion tools
external network/browser tools
tools with irreversible side effects
```

---

## 5. Tool Descriptor Contract

Spring Boot should expose descriptors to Agent Service in run context.

### 5.1 Descriptor shape

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "name": "AI 小红书文案生成器",
  "description": "根据产品、目标人群和风格生成小红书种草文案",
  "creditCost": 5,
  "autoCallable": true,
  "requiresConfirmation": false,
  "inputSchema": {
    "type": "object",
    "properties": {
      "productName": {
        "type": "string",
        "title": "产品名称",
        "description": "要推广的产品或服务"
      }
    },
    "required": ["productName"]
  },
  "agentHints": {
    "keywords": ["小红书", "种草", "笔记", "爆款笔记"],
    "useCases": ["生成小红书文案", "生成种草笔记"],
    "argumentGuidance": "从用户消息中提取产品、目标人群、风格和卖点。缺少产品名称时先追问。"
  }
}
```

### 5.2 New DTOs

Spring Boot:

```text
AgentToolDescriptorResponse
AgentToolFieldDescriptorResponse
AgentToolHintsResponse
AgentToolExecuteRequest
AgentToolExecuteResponse
```

Agent Service:

```text
ToolDescriptor
ToolInputSchema
ToolHints
ToolExecutionRequest
ToolExecutionResult
```

---

## 6. Field To JSON Schema Conversion

Spring Boot converts `ToolFieldResponse` into JSON Schema.

### 6.1 Field type mapping

```text
text     -> string
textarea -> string
select   -> string with enum
number   -> number
integer  -> integer
boolean  -> boolean
```

Unknown field type:

```text
fallback to string
autoCallable = false if field type is unsupported and required
```

### 6.2 Select options

Current `options` can be JSON.

Supported option shapes:

```json
["自然", "专业", "活泼"]
```

or:

```json
[
  { "label": "自然", "value": "natural" },
  { "label": "专业", "value": "professional" }
]
```

Converted schema:

```json
{
  "type": "string",
  "enum": ["natural", "professional"],
  "enumNames": ["自然", "专业"]
}
```

If options cannot be parsed:

- keep `type: string`.
- include original options in descriptor metadata.
- mark warning in logs.

### 6.3 Required fields

If `required = true`, include field key in schema `required`.

### 6.4 Field descriptions

Use:

```text
fieldName as title
placeholder as description
```

If placeholder is blank, use field name as fallback description.

---

## 7. Agent Auto-Call Policy

### 7.1 Policy fields

For Phase 1.4, policy can be code/config driven:

```yaml
app:
  agent:
    auto-callable-tools:
      - xiaohongshu_copywriting
      - moments_copywriting_generator
      - product_title_optimizer
      - wechat_longform_generator
```

Each descriptor should include:

```text
autoCallable
requiresConfirmation
maxAutoCallsPerRun
```

Defaults:

```text
autoCallable = false
requiresConfirmation = true
maxAutoCallsPerRun = 1
```

For initial allowlist:

```text
autoCallable = true
requiresConfirmation = false
maxAutoCallsPerRun = 1
```

### 7.2 Deny rules

Agent must not auto-call a tool if:

- tool is not `ONLINE`.
- tool is not in descriptor list.
- `autoCallable=false`.
- required arguments cannot be inferred.
- credit budget is insufficient.
- tool schema is invalid.
- tool has side-effect category.
- run already reached max tool calls.

### 7.3 Clarification behavior

If a tool matches but required fields are missing:

```text
Do not call tool.
Return assistant clarification question.
```

Example:

```text
“可以，我需要知道你想推广的产品名称或服务内容。”
```

---

## 8. Tool Intent Matching

### 8.1 Matching inputs

Agent Service uses:

- user message.
- tool name.
- tool description.
- `agentHints.keywords`.
- `agentHints.useCases`.
- active conversation context.

### 8.2 Phase 1.4 strategy

Use deterministic scoring first.

Score examples:

```text
keyword exact match: +5
tool name partial match: +4
description keyword: +2
useCase phrase match: +3
conflicting tool keyword: -3
```

Threshold:

```text
score >= 5 -> selected
2 <= score < 5 -> needs_clarification
score < 2 -> general_chat
```

If multiple tools exceed threshold:

- choose highest score if difference >= 3.
- otherwise ask user to choose.

### 8.3 Future upgrade

Later phases can replace scoring with LLM classifier or embedding retrieval.

The public interface should stay:

```python
select_tool(message: str, tools: list[ToolDescriptor]) -> ToolSelectionResult
```

---

## 9. Argument Extraction

### 9.1 Phase 1.4 extraction strategy

Use a hybrid approach:

1. Deterministic extraction for common fields.
2. LLM JSON extraction for remaining fields.
3. Schema validation.
4. Clarification if required fields remain missing.

### 9.2 Common deterministic extraction

Fields:

```text
productName
targetCustomer
style
sellingPoints
platform
topic
keywords
```

Examples:

```text
“产品是五一护肤套装”
-> productName = "五一护肤套装"

“目标人群是 25-35 岁女性”
-> targetCustomer = "25-35 岁女性"

“风格自然一点”
-> style = "自然"
```

### 9.3 LLM JSON extraction prompt

Agent Service sends model:

```text
你是工具参数抽取器。请根据用户消息和工具 JSON Schema 提取参数。
只输出 JSON，不要输出解释。
缺失且无法推断的字段不要编造。
```

Input:

```json
{
  "userMessage": "帮我给这款护肤套装写一篇小红书种草文案，风格自然一点，目标人群是 25-35 岁女性。",
  "toolCode": "xiaohongshu_copywriting",
  "schema": {}
}
```

Output:

```json
{
  "productName": "护肤套装",
  "targetCustomer": "25-35 岁女性",
  "style": "自然"
}
```

### 9.4 Validation

Use Python `jsonschema` or Pydantic model generated from schema.

Recommendation for Phase 1.4:

```text
Use jsonschema package for direct JSON Schema validation.
```

Validation outcomes:

```text
valid -> call tool
missing required -> clarification
invalid enum -> map to closest enum if confidence high, otherwise clarification
invalid type -> coerce simple strings/numbers, otherwise clarification
```

---

## 10. Tool Execution Bridge

### 10.1 Spring Boot internal endpoint

Add or finalize:

```text
POST /api/internal/v1/agent/runs/{runId}/tools/{toolCode}/execute
```

Request:

```json
{
  "toolCallId": 50001,
  "argumentsJson": {
    "productName": "护肤套装",
    "targetCustomer": "25-35 岁女性",
    "style": "自然"
  }
}
```

Response:

```json
{
  "toolCallId": 50001,
  "toolCode": "xiaohongshu_copywriting",
  "status": "SUCCESS",
  "resourceType": "MARKDOWN",
  "contentText": "生成结果...",
  "contentJson": null,
  "taskId": 90001,
  "consumedCredits": 5
}
```

### 10.2 Execution mode

Phase 1.4 can choose one of two modes.

Recommended for MVP:

```text
sync_mock_or_direct
```

Behavior:

- Create agent tool call.
- Validate tool.
- Use existing worker tool assets if callable in-process, or return a deterministic mock in local mode.
- Record result.

Alternative:

```text
create_ai_task_async
```

Behavior:

- Create normal `ai_tasks`.
- Push Redis queue.
- Agent waits/polls for task completion.

This is more realistic but slower and belongs better to Phase 1.5/1.6 unless needed immediately.

### 10.3 Recommended Phase 1.4 decision

Use:

```text
sync_direct for text tools where backend can invoke existing prompt/model path
mock fallback for local MODEL_PROVIDER=mock
```

Keep endpoint response shape compatible with future async task mode.

---

## 11. Backend Changes

### 11.1 Spring Boot files

Modify or create:

```text
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentToolDescriptorService.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentToolExecutionService.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolExecutionServiceImpl.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentToolController.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolDescriptorResponse.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolHintsResponse.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolExecuteRequest.java
backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolExecuteResponse.java
```

### 11.2 Descriptor service behavior

`AgentToolDescriptorServiceImpl`:

- Query online tools.
- Query active fields.
- Convert fields to JSON Schema.
- Apply auto-call allowlist config.
- Add keyword hints for seeded tools.
- Exclude invalid descriptors from auto-call.

### 11.3 Tool execution service behavior

`AgentToolExecutionServiceImpl`:

- Verify run exists.
- Verify tool exists and is online.
- Verify tool is auto-callable or requires confirmation was satisfied.
- Validate arguments against descriptor schema.
- Record tool call status.
- Execute tool through selected mode.
- Return normalized result.

---

## 12. Agent Service Changes

### 12.1 Files

Modify or create:

```text
agent-service/app/tools/registry.py
agent-service/app/tools/backend_tool.py
agent-service/app/tools/argument_extractor.py
agent-service/app/tools/schema_validator.py
agent-service/app/tools/selection.py
agent-service/app/tools/schemas.py
agent-service/tests/test_tool_selection.py
agent-service/tests/test_argument_extractor.py
agent-service/tests/test_schema_validator.py
agent-service/tests/test_backend_tool_execution.py
```

### 12.2 Tool selection result

```python
class ToolSelectionResult(BaseModel):
    selected: bool
    toolCode: str | None = None
    confidence: float
    score: int
    reason: str
    needsClarification: bool = False
    clarificationQuestion: str | None = None
```

### 12.3 Argument extraction result

```python
class ArgumentExtractionResult(BaseModel):
    arguments: dict[str, Any]
    missingRequired: list[str] = []
    invalidFields: list[str] = []
    needsClarification: bool = False
    clarificationQuestion: str | None = None
```

### 12.4 Backend tool execution

`BackendToolExecutor.execute(...)`:

```python
async def execute(
    self,
    run_id: int,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
) -> ToolExecutionResult:
    ...
```

Responsibilities:

- create tool call.
- call internal execute endpoint.
- complete/fail tool call if needed.
- return normalized result.

---

## 13. Event Contract Enhancements

### 13.1 tool.selected

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "toolName": "AI 小红书文案生成器",
  "confidence": 0.91,
  "reason": "用户明确提到小红书种草文案"
}
```

### 13.2 tool.started

```json
{
  "toolCallId": 50001,
  "toolCode": "xiaohongshu_copywriting",
  "toolName": "AI 小红书文案生成器",
  "argumentsPreview": {
    "productName": "护肤套装",
    "targetCustomer": "25-35 岁女性",
    "style": "自然"
  }
}
```

### 13.3 tool.finished

```json
{
  "toolCallId": 50001,
  "toolCode": "xiaohongshu_copywriting",
  "status": "SUCCESS",
  "resourceType": "MARKDOWN",
  "resultPreview": "生成结果前 200 字...",
  "consumedCredits": 5
}
```

### 13.4 clarification

Use `message.delta` and `message.completed` for clarification answer.

Do not call tool if clarification is needed.

---

## 14. Frontend Rendering Updates

Modify:

```text
agent-web/components/agent/tool-call-card.tsx
agent-web/components/agent/run-event-list.tsx
agent-web/lib/types/agent.ts
```

Tool card should display:

- tool name.
- status.
- arguments preview.
- credit cost or consumed credits.
- result preview.
- failure reason.

Argument preview must not render huge JSON.

Rules:

- Show max 4 fields.
- Truncate long values at 80 characters.
- Hide internal keys.

---

## 15. Safety Requirements

### 15.1 Tool allowlist

Agent can only auto-call tools returned by Spring Boot with:

```text
autoCallable=true
requiresConfirmation=false
```

### 15.2 Schema validation

No tool call can execute unless arguments validate.

### 15.3 Budget control

Before execution:

- confirm tool credit cost <= remaining run budget.
- if not, produce graceful failure or clarification.

### 15.4 No arbitrary tools

The model cannot invent a tool name.

If model output contains unknown tool:

```text
ignore and ask clarification or answer generally
```

### 15.5 No sensitive side effects

Phase 1.4 does not auto-call tools that mutate accounts, payments, admin settings, files, or external systems.

---

## 16. Testing Strategy

### 16.1 Spring Boot tests

Add or update:

```text
AgentToolDescriptorApiTest.java
AgentToolExecutionApiTest.java
```

Cases:

```text
online tool exports descriptor
offline tool is not exported
field schema converts to JSON Schema
select options convert to enum
allowlisted tool is autoCallable
non-allowlisted tool is not autoCallable
internal execute rejects unavailable tool
internal execute rejects invalid arguments
internal execute records tool call result
```

### 16.2 Agent Service tests

Add:

```text
test_tool_selection.py
test_argument_extractor.py
test_schema_validator.py
test_backend_tool_execution.py
```

Cases:

```text
小红书 request selects xiaohongshu tool
商品标题 request selects product title tool
ambiguous request asks clarification
required field missing asks clarification
valid args pass schema validation
invalid enum asks clarification
unknown selected tool is rejected
backend tool executor emits expected calls
```

### 16.3 Frontend tests/manual checks

Manual verification:

```text
tool.selected renders tool card
tool.started shows argument preview
tool.finished shows result preview
tool failure shows error state
long argument values do not break layout
mobile card layout does not overflow
```

---

## 17. Implementation Tasks

### Task 1: Improve Spring Boot tool descriptors

**Files:**

- Modify: `AgentToolDescriptorService.java`
- Modify: `AgentToolDescriptorServiceImpl.java`
- Modify: `AgentToolDescriptorResponse.java`
- Create: `AgentToolHintsResponse.java`

- [ ] **Step 1: Add descriptor fields**

Add `requiresConfirmation`, `agentHints`, and full `inputSchema`.

- [ ] **Step 2: Add auto-call allowlist config**

Add `app.agent.auto-callable-tools`.

- [ ] **Step 3: Add seeded tool hints**

Map known tool codes to keywords and use cases.

- [ ] **Step 4: Compile backend**

```powershell
cd backend
mvn -q -DskipTests compile
```

Expected:

```text
Compilation succeeds
```

### Task 2: Convert fields to JSON Schema

**Files:**

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentToolSchemaService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolSchemaServiceImpl.java`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolSchemaServiceTest.java`

- [ ] **Step 1: Write schema conversion tests**

Cover text, textarea, select, required, unknown field type.

- [ ] **Step 2: Implement field type mapping**

Use Section 6.

- [ ] **Step 3: Implement option enum conversion**

Support array of strings and array of `{label,value}`.

- [ ] **Step 4: Run tests**

```powershell
cd backend
mvn test -Dtest=AgentToolSchemaServiceTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 3: Add internal tool execution endpoint

**Files:**

- Create: `AgentToolExecutionService.java`
- Create: `AgentToolExecutionServiceImpl.java`
- Create: `InternalAgentToolController.java`
- Create: `AgentToolExecuteRequest.java`
- Create: `AgentToolExecuteResponse.java`
- Create: `AgentToolExecutionApiTest.java`

- [ ] **Step 1: Add failing API tests**

Test valid execution, invalid arguments, offline tool rejection.

- [ ] **Step 2: Implement service validation**

Validate run, tool, auto-call policy, schema, budget.

- [ ] **Step 3: Implement MVP execution result**

Return deterministic mock/local result if real tool bridge is not ready.

- [ ] **Step 4: Record tool call events**

Insert `tool.started` and `tool.finished`.

- [ ] **Step 5: Run tests**

```powershell
cd backend
mvn test -Dtest=AgentToolExecutionApiTest
```

Expected:

```text
BUILD SUCCESS
```

### Task 4: Implement Agent Service tool selection

**Files:**

- Create: `agent-service/app/tools/selection.py`
- Modify: `agent-service/app/tools/registry.py`
- Create: `agent-service/tests/test_tool_selection.py`

- [ ] **Step 1: Write selection tests**

Cover known tools and ambiguity.

- [ ] **Step 2: Implement scoring**

Use keyword/name/description/useCase scoring.

- [ ] **Step 3: Integrate with intent router**

Tool selection result should enrich `tool_use` intent.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_tool_selection.py -q
```

Expected:

```text
All tests pass
```

### Task 5: Implement argument extraction

**Files:**

- Create: `agent-service/app/tools/argument_extractor.py`
- Create: `agent-service/tests/test_argument_extractor.py`

- [ ] **Step 1: Write extraction tests**

Cover productName, targetCustomer, style, missing required fields.

- [ ] **Step 2: Implement deterministic extraction**

Use regex and field-name matching.

- [ ] **Step 3: Add model-based JSON extraction fallback**

Use model client in mock-safe way.

- [ ] **Step 4: Return clarification when required fields missing**

Create a clear user-facing question.

- [ ] **Step 5: Run tests**

```powershell
cd agent-service
pytest tests/test_argument_extractor.py -q
```

Expected:

```text
All tests pass
```

### Task 6: Implement schema validation

**Files:**

- Create: `agent-service/app/tools/schema_validator.py`
- Create: `agent-service/tests/test_schema_validator.py`
- Modify: `agent-service/requirements.txt`

- [ ] **Step 1: Add jsonschema dependency**

Add:

```text
jsonschema>=4.22.0
```

- [ ] **Step 2: Write validation tests**

Cover valid object, missing required, invalid enum, simple coercion.

- [ ] **Step 3: Implement validator**

Return structured validation result.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_schema_validator.py -q
```

Expected:

```text
All tests pass
```

### Task 7: Implement backend tool executor

**Files:**

- Modify: `agent-service/app/tools/backend_tool.py`
- Create: `agent-service/tests/test_backend_tool_execution.py`
- Modify: `agent-service/app/clients/backend_client.py`

- [ ] **Step 1: Add backend client method**

Add `execute_tool(run_id, tool_code, tool_call_id, arguments)`.

- [ ] **Step 2: Write executor tests**

Mock backend execute endpoint.

- [ ] **Step 3: Implement executor**

Create tool call, execute tool, normalize result.

- [ ] **Step 4: Emit expected events**

Ensure `tool.started` and `tool.finished` payloads match Section 13.

- [ ] **Step 5: Run tests**

```powershell
cd agent-service
pytest tests/test_backend_tool_execution.py -q
```

Expected:

```text
All tests pass
```

### Task 8: Integrate into LangGraph

**Files:**

- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Modify: `agent-service/tests/test_universal_graph.py`

- [ ] **Step 1: Update graph tests**

Assert selection, extraction, validation, execution order.

- [ ] **Step 2: Replace simple tool bridge**

Use selector, extractor, validator, executor.

- [ ] **Step 3: Handle clarification route**

If missing required fields, complete run with clarification message.

- [ ] **Step 4: Run tests**

```powershell
cd agent-service
pytest tests/test_universal_graph.py -q
```

Expected:

```text
All tests pass
```

### Task 9: Update frontend tool card rendering

**Files:**

- Modify: `agent-web/lib/types/agent.ts`
- Modify: `agent-web/components/agent/tool-call-card.tsx`
- Modify: `agent-web/components/agent/run-event-list.tsx`

- [ ] **Step 1: Add event payload types**

Define tool selected/started/finished payloads.

- [ ] **Step 2: Render argument preview**

Show max 4 fields and truncate values.

- [ ] **Step 3: Render result preview**

Show result status and consumed credits.

- [ ] **Step 4: Check mobile layout**

Verify no overflow on 390px width.

### Task 10: End-to-end verification

- [ ] **Step 1: Backend tests**

```powershell
cd backend
mvn test -Dtest=AgentToolSchemaServiceTest,AgentToolExecutionApiTest
```

- [ ] **Step 2: Agent Service tests**

```powershell
cd agent-service
pytest tests/test_tool_selection.py tests/test_argument_extractor.py tests/test_schema_validator.py tests/test_backend_tool_execution.py tests/test_universal_graph.py -q
```

- [ ] **Step 3: Frontend typecheck**

```powershell
cd agent-web
npm run typecheck
```

- [ ] **Step 4: Manual scenario**

Run:

```text
用户输入：帮我给护肤套装写一篇小红书种草文案，目标人群是 25-35 岁女性，风格自然一点
```

Expected:

```text
Agent selects xiaohongshu_copywriting
Agent extracts productName/targetCustomer/style
Tool call is recorded
Tool card renders
Assistant returns final answer
Run completes successfully
```

---

## 18. Acceptance Criteria

Phase 1.4 is complete when:

- Spring Boot exports Agent-safe tool descriptors.
- Tool fields are converted to valid JSON Schema.
- Initial allowlisted tools are marked auto-callable.
- Non-allowlisted or offline tools cannot be auto-called.
- Agent Service can select a tool using deterministic scoring.
- Agent Service can extract common arguments from natural language.
- Agent Service validates arguments before calling tools.
- Missing required arguments produce clarification instead of bad tool calls.
- Backend internal tool execute endpoint exists.
- Tool execution records tool call status and events.
- Frontend renders selected, started, finished, and failed tool states.
- End-to-end text generation tool scenario works in mock/local mode.

---

## 19. Handoff To Next Phase

After this document is implemented, the next development document should be:

```text
Phase 1.5：SSE 流式输出与运行事件开发文档
```

That document should harden:

- real-time run event delivery.
- Spring Boot SSE endpoint.
- frontend event stream replacement for polling.
- reconnection and event replay.
- long-running run visibility.
