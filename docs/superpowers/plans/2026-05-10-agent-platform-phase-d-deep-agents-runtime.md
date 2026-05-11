# Agent Platform Phase D Deep Agents Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a feature-flagged Deep Agents runtime for read-only workspace planning, file reasoning, and sub-agent delegation.

**Architecture:** Deep Agents runs inside FastAPI `agent-service` behind `AGENT_DEEP_AGENTS_ENABLED=false` by default. It uses backend-provided context, virtual file access, platform-approved tools, and budget-limited sub-agent traces. It never directly accesses host files, business tables, shell, browser, or external network.

**Tech Stack:** FastAPI, LangChain, LangGraph, Deep Agents, Spring Boot signed internal APIs.

---

## File Map

- Modify: `agent-service/requirements.txt`
- Modify: `agent-service/app/config.py`
- Create: `agent-service/app/runtime/deep_agents_engine.py`
- Modify: `agent-service/app/runtime/router.py`
- Create: `agent-service/app/runtime/virtual_workspace.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Modify: `agent-service/app/core/schemas.py`
- Create: `agent-service/tests/test_deep_agents_runtime.py`
- Create: `agent-service/tests/test_virtual_workspace.py`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentSubagentRun.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentSubagentRunMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceFileReadRequest.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceArtifactWriteRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentDeepRuntimeInternalApiTest.java`

## Task D1: Optional Dependency And Config

**Files:**
- Modify: `agent-service/requirements.txt`
- Modify: `agent-service/app/config.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`

- [ ] **Step 1: Write config default test**

Assert `Settings().agent_deep_agents_enabled is False` when `AGENT_DEEP_AGENTS_ENABLED` is not set.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_disabled_by_default -q`
Expected: FAIL because the setting is absent.

- [ ] **Step 2: Add config setting**

Add `agent_deep_agents_enabled: bool = False` to `Settings` with env alias `AGENT_DEEP_AGENTS_ENABLED`.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_disabled_by_default -q`
Expected: PASS.

- [ ] **Step 3: Add optional runtime dependency**

Add a pinned `deepagents` dependency to `requirements.txt`. Keep import usage inside `DeepAgentsRuntimeEngine` so disabled deployments can start even when the import fails during local development.

Run: `cd agent-service; python -m pip check`
Expected: dependency metadata is consistent in the current environment.

## Task D2: DeepAgentsRuntimeEngine Skeleton

**Files:**
- Create: `agent-service/app/runtime/deep_agents_engine.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`

- [ ] **Step 1: Write skeleton behavior test**

Test that `DeepAgentsRuntimeEngine(backend, model, enabled=False).run(context)` appends `run.failed` with `DEEP_AGENTS_DISABLED` and never imports deepagents.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_engine_refuses_when_disabled -q`
Expected: FAIL because the engine file is absent.

- [ ] **Step 2: Implement disabled-path skeleton**

Implement constructor dependencies `backend_client`, `model_client`, `settings`, `workspace`, and `budget_guard`. In disabled mode, fail the run through backend with a clear operational error.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_engine_refuses_when_disabled -q`
Expected: PASS.

## Task D3: Runtime Router Feature Flag

**Files:**
- Modify: `agent-service/app/runtime/router.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`

- [ ] **Step 1: Write router selection tests**

Assert requested runtime `deep_agents` returns `LangGraphRuntimeEngine` when disabled and `DeepAgentsRuntimeEngine` when enabled. Also assert plain chat still returns `LangGraphRuntimeEngine`.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_router_deep_agents_feature_flag -q`
Expected: FAIL until router can construct the Deep Agents engine.

- [ ] **Step 2: Implement feature-flag routing**

Pass settings into `RuntimeRouter`. Select Deep Agents only when requested runtime is `deep_agents`, feature flag is true, and run context marks the task as workspace-oriented.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_router_deep_agents_feature_flag -q`
Expected: PASS.

## Task D4: Virtual File Read Bridge

**Files:**
- Create: `agent-service/app/runtime/virtual_workspace.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceFileReadRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Test: `agent-service/tests/test_virtual_workspace.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentDeepRuntimeInternalApiTest.java`

- [ ] **Step 1: Write backend internal file read test**

Signed request to `POST /api/internal/v1/agent/workspaces/{workspaceId}/files/read` returns allowed file chunks for the workspace. Unsigned request receives `401`; cross-workspace file ID receives `403`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#readsWorkspaceFileThroughSignedApi" test`
Expected: FAIL because endpoint and DTO are absent.

- [ ] **Step 2: Implement backend file read endpoint**

Use existing file and chunk mappers. Return `fileId`, `originalFilename`, `chunks`, and `citationBase`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#readsWorkspaceFileThroughSignedApi" test`
Expected: PASS.

- [ ] **Step 3: Write agent-service virtual read test**

Assert `VirtualWorkspace.read_file(file_id=9)` calls backend client and returns content with citations. Assert any host path such as `C:\tmp\a.txt` raises `PermissionError`.

Run: `cd agent-service; pytest tests/test_virtual_workspace.py::test_virtual_workspace_reads_only_backend_files -q`
Expected: FAIL because the bridge is absent.

- [ ] **Step 4: Implement virtual read bridge**

Expose only `read_file(file_id: int)` and `list_readable_files()`. Reject string paths, relative paths, and path separators.

Run: `cd agent-service; pytest tests/test_virtual_workspace.py::test_virtual_workspace_reads_only_backend_files -q`
Expected: PASS.

## Task D5: Artifact Write Bridge

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/InternalWorkspaceArtifactWriteRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Modify: `agent-service/app/runtime/virtual_workspace.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentDeepRuntimeInternalApiTest.java`
- Test: `agent-service/tests/test_virtual_workspace.py`

- [ ] **Step 1: Write artifact write tests**

Backend test posts `filename`, `contentType`, `content`, and `sourceRunId` to `/api/internal/v1/agent/workspaces/{workspaceId}/artifacts`; response includes `artifactId` and `version`. Agent-service test asserts `write_artifact` calls that endpoint and returns the backend response.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#createsWorkspaceArtifactThroughSignedApi" test`
Expected: FAIL because artifact endpoint is absent.

- [ ] **Step 2: Implement write bridge**

Persist generated artifacts through Spring Boot only. Record run event `artifact.created` with workspace ID, artifact ID, filename, and byte length.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#createsWorkspaceArtifactThroughSignedApi" test`
Expected: PASS.

- [ ] **Step 3: Verify Python bridge**

Run: `cd agent-service; pytest tests/test_virtual_workspace.py::test_virtual_workspace_writes_artifact_via_backend -q`
Expected: PASS.

## Task D6: Sub-Agent Trace Model

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentSubagentRun.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentSubagentRunMapper.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Modify: `agent-service/app/core/schemas.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentDeepRuntimeInternalApiTest.java`

- [ ] **Step 1: Write trace persistence test**

Assert internal API can append sub-agent trace fields `parentRunId`, `role`, `status`, `inputSummary`, `outputSummary`, `startedAt`, and `completedAt`, and user-visible run events contain summaries only.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#recordsSubagentTraceSummary" test`
Expected: FAIL until table and endpoint exist.

- [ ] **Step 2: Implement trace persistence**

Add `agent_subagent_runs` schema and mapper. Expose a signed internal endpoint under `/api/internal/v1/agent/runs/{runId}/subagents`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest#recordsSubagentTraceSummary" test`
Expected: PASS.

## Task D7: Budget And Permission Enforcement

**Files:**
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Modify: `agent-service/app/core/budget_guard.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`

- [ ] **Step 1: Write budget and permission tests**

Assert Deep Agents denies shell, browser, direct network, account/payment, and admin tools. Assert max sub-agent count, max model calls, and max file reads stop execution with a backend `run.failed` event.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_enforces_budget_and_permissions -q`
Expected: FAIL because enforcement hooks are not wired.

- [ ] **Step 2: Implement runtime policy gate**

Before creating the Deep Agent graph, filter tools by descriptor side-effect level and permissions. During execution, decrement budget counters for model calls, file reads, sub-agent starts, and artifact writes.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_enforces_budget_and_permissions -q`
Expected: PASS.

## Task D8: Read-Only Workspace Smoke

**Files:**
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Test: `agent-service/tests/test_deep_agents_runtime.py`

- [ ] **Step 1: Write read-only smoke test**

With feature flag enabled, fake backend workspace files, and fake model responses, run a request asking for a plan from existing files. Assert final answer includes file citations and no artifact write occurs.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_read_only_workspace_smoke -q`
Expected: FAIL until enabled path creates the Deep Agents executor.

- [ ] **Step 2: Implement enabled read-only path**

Create the Deep Agents runtime with only virtual read tools and bounded sub-agent tools. Return concise final answer through the existing backend completion API.

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py::test_deep_agents_read_only_workspace_smoke -q`
Expected: PASS.

## Task D9: Phase D Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run agent-service runtime tests**

Run: `cd agent-service; pytest tests/test_deep_agents_runtime.py tests/test_virtual_workspace.py tests/test_runtime_router.py -q`
Expected: all selected tests pass.

- [ ] **Step 2: Run backend internal API tests**

Run: `mvn -f backend\pom.xml "-Dtest=AgentDeepRuntimeInternalApiTest,AgentApiTest" test`
Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 3: Run safety grep**

Run: `rg -n "subprocess|os\\.system|open\\(|requests\\.get|httpx\\.get" agent-service/app/runtime`
Expected: no matches in Deep Agents runtime code except backend client calls routed through `BackendClient`.

- [ ] **Step 4: Commit Phase D**

Run: `git add agent-service backend && git commit -m "feat: add feature flagged deep agents runtime"`
Expected: commit records only Phase D implementation files.
