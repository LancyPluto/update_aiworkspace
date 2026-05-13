# Agent Platform Phase C Workspace Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add workspace-scoped memory so Agent sessions can persist and retrieve project context across runs.

**Architecture:** Spring Boot owns workspace, memory persistence, permissions, and APIs. Agent Service requests workspace memory through signed backend APIs and treats returned memory as cited context. user-web exposes memory inspection and deletion inside the Agent workspace surface.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, MySQL/H2, FastAPI, LangGraph, Vue 3, Pinia.

---

## File Map

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspace.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspaceMember.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspaceMemoryItem.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMemberMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMemoryItemMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentWorkspaceService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentWorkspaceServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentWorkspaceController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentSession.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/CreateAgentSessionRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentSessionResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalAgentRunContextResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceApiTest.java`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceOwnershipTest.java`
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Create: `agent-service/tests/test_workspace_memory.py`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/pages/AgentHome/WorkspaceMemoryPanel.vue`

## Task C1: Backend Workspace Schema And Migration

**Files:**
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspace.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspaceMember.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMemberMapper.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceApiTest.java`

- [ ] **Step 1: Write failing schema-backed API test**

Add `createsDefaultWorkspaceForCurrentUser()` to `AgentWorkspaceApiTest`. The test logs in, calls `GET /api/v1/agent/workspaces`, and asserts one `PERSONAL` workspace with `role=OWNER`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#createsDefaultWorkspaceForCurrentUser" test`
Expected: FAIL because `/api/v1/agent/workspaces` is not registered.

- [ ] **Step 2: Add H2 schema tables**

In `schema-test.sql`, add `agent_workspaces` and `agent_workspace_members` with `id`, `owner_user_id`, `name`, `workspace_type`, `status`, `created_at`, `updated_at`, plus member `workspace_id`, `user_id`, `role`, `status`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#createsDefaultWorkspaceForCurrentUser" test`
Expected: FAIL because Java entities and service are not present.

- [ ] **Step 3: Add workspace entities and mappers**

Implement the entity classes and MyBatis mappers using the same annotations and ID style as `AgentSession` and `AgentSessionMapper`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#createsDefaultWorkspaceForCurrentUser" test`
Expected: FAIL because the service and controller are not present.

- [ ] **Step 4: Add workspace service and controller**

Implement `AgentWorkspaceService.getOrCreateDefaultWorkspace(userId)` and expose `GET /api/v1/agent/workspaces`. Response fields: `id`, `name`, `workspaceType`, `role`, `status`, `createdAt`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#createsDefaultWorkspaceForCurrentUser" test`
Expected: PASS.

## Task C2: Workspace Ownership Tests

**Files:**
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceOwnershipTest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentWorkspaceServiceImpl.java`

- [ ] **Step 1: Write cross-user denial tests**

Add tests proving user B receives `403` when reading user A workspace detail, listing user A memory, deleting user A memory, or creating a session in user A workspace.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceOwnershipTest" test`
Expected: FAIL with permission gaps.

- [ ] **Step 2: Centralize membership assertion**

Add `assertWorkspaceMember(workspaceId, userId)` and `assertWorkspaceOwner(workspaceId, userId)` in `AgentWorkspaceServiceImpl`. Use them from public workspace and memory operations.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceOwnershipTest" test`
Expected: PASS with `403` for cross-user access.

## Task C3: Session-To-Workspace Compatibility

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentSession.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/CreateAgentSessionRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentSessionResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentSessionServiceImpl.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentApiTest.java`

- [ ] **Step 1: Extend session tests**

Add assertions that `POST /api/v1/agent/sessions` without `workspaceId` assigns the default personal workspace and that providing an owned `workspaceId` preserves it.

Run: `mvn -f backend\pom.xml "-Dtest=AgentApiTest" test`
Expected: FAIL because sessions do not expose `workspaceId`.

- [ ] **Step 2: Add `workspace_id` to session schema and DTOs**

Add nullable `workspace_id` in the test schema, then add `workspaceId` to entity, create request, and response. Keep omitted request behavior compatible by assigning the personal workspace in `AgentSessionServiceImpl`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentApiTest" test`
Expected: PASS.

## Task C4: Workspace Memory CRUD APIs

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspaceMemoryItem.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentWorkspaceMemoryItemMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentWorkspaceMemoryItemResponse.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/CreateAgentWorkspaceMemoryRequest.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/UpdateAgentWorkspaceMemoryRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentWorkspaceController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceApiTest.java`

- [ ] **Step 1: Write CRUD tests**

Cover `GET /api/v1/agent/workspaces/{workspaceId}/memory`, `POST`, `PUT /memory/{memoryId}`, and `DELETE /memory/{memoryId}`. Assert list ordering by `updatedAt desc` and soft deletion.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest" test`
Expected: FAIL because memory endpoints are absent.

- [ ] **Step 2: Add memory schema and mapper**

Add `agent_workspace_memory_items` columns: `id`, `workspace_id`, `user_id`, `memory_type`, `title`, `content`, `source_run_id`, `status`, `created_at`, `updated_at`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest" test`
Expected: FAIL until service and controller are implemented.

- [ ] **Step 3: Implement memory service methods**

Add create, list, update, and delete operations. Enforce workspace membership before every operation and set deleted rows to `status=DELETED`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest" test`
Expected: PASS.

## Task C5: Internal Memory Retrieval API

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceMemoryRetrieveRequest.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceMemoryItemResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalAgentRunContextResponse.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentWorkspaceApiTest.java`

- [ ] **Step 1: Write signed internal retrieval test**

Use existing internal signature test support to call `POST /api/internal/v1/agent/workspaces/{workspaceId}/memory/retrieve` with `query` and `limit=5`. Assert unsigned requests receive `401` and signed requests receive matching memory from the same workspace only.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#retrievesWorkspaceMemoryForSignedInternalRequest" test`
Expected: FAIL because the endpoint is absent.

- [ ] **Step 2: Implement retrieval endpoint**

Return active memory items from the requested workspace. Use simple score ordering for Phase C: title match first, content match second, newer items third.

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest#retrievesWorkspaceMemoryForSignedInternalRequest" test`
Expected: PASS.

## Task C6: Agent-Service Memory Retrieval Client

**Files:**
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Test: `agent-service/tests/test_workspace_memory.py`

- [ ] **Step 1: Write backend client test**

Create a fake HTTP transport test asserting `BackendClient.retrieve_workspace_memory(workspace_id=7, query="pricing", limit=4)` signs `POST /api/internal/v1/agent/workspaces/7/memory/retrieve` and parses `id`, `title`, `content`, `memoryType`, and `score`.

Run: `cd agent-service; pytest tests/test_workspace_memory.py -q`
Expected: FAIL because schemas and client method are absent.

- [ ] **Step 2: Add Pydantic schema and client method**

Add `WorkspaceMemoryItem` to `schemas.py` and implement `retrieve_workspace_memory` in `backend_client.py` using existing internal request signing flow.

Run: `cd agent-service; pytest tests/test_workspace_memory.py -q`
Expected: PASS.

## Task C7: LangGraph Context Injection

**Files:**
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Test: `agent-service/tests/test_workspace_memory.py`

- [ ] **Step 1: Write graph context test**

Add a test where `RunContext.workspaceId=7` and fake backend returns memory. Assert the final model prompt contains a `Workspace memory` section with the memory title and content, plus citation labels.

Run: `cd agent-service; pytest tests/test_workspace_memory.py::test_langgraph_injects_workspace_memory -q`
Expected: FAIL because graph prompts do not include workspace memory.

- [ ] **Step 2: Add workspace fields and prompt injection**

Add `workspaceId: int | None` and `workspaceMemory: list[WorkspaceMemoryItem]` to `RunContext`. In `UniversalAgentGraph`, retrieve memory before answer synthesis when `workspaceId` is present, cap to five items, and include citation labels `memory:<id>`.

Run: `cd agent-service; pytest tests/test_workspace_memory.py -q`
Expected: PASS.

## Task C8: user-web Memory Panel

**Files:**
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/pages/AgentHome/WorkspaceMemoryPanel.vue`

- [ ] **Step 1: Add API and component tests through typecheck**

Add TypeScript types `AgentWorkspace` and `AgentWorkspaceMemoryItem`; add API wrappers for list, update, and delete memory. The panel props are `workspaceId`, `token`, and `refreshKey`.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: FAIL until the component and imports compile.

- [ ] **Step 2: Build memory panel**

Render active memory as a compact right-side panel in `AgentHome/Page.vue`. Include title, type, content preview, update action, and delete action. Empty state text: `No workspace memory yet`.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: PASS.

## Task C9: Phase C Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run backend phase tests**

Run: `mvn -f backend\pom.xml "-Dtest=AgentWorkspaceApiTest,AgentWorkspaceOwnershipTest,AgentApiTest" test`
Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 2: Run agent-service phase tests**

Run: `cd agent-service; pytest tests/test_workspace_memory.py tests/test_universal_graph.py -q`
Expected: all selected tests pass.

- [ ] **Step 3: Run user-web checks**

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit; npm run build`
Expected: typecheck and Vite build complete successfully.

- [ ] **Step 4: Commit Phase C**

Run: `git add backend agent-service user-web && git commit -m "feat: add workspace memory mvp"`
Expected: commit records only Phase C implementation files.
