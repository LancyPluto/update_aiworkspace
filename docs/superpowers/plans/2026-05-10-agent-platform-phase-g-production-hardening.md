# Agent Platform Phase G Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the Agent platform for long-running production use with checkpointing, resume, observability, audit export, and workspace sharing.

**Architecture:** Spring Boot coordinates durable state, audit, sharing, and operational APIs. Agent Service exposes runtime trace summaries and checkpoint-aware execution. Frontend surfaces long-task status, cancellation, and audit summaries without exposing hidden chain-of-thought.

**Tech Stack:** Spring Boot 3, Redis, MySQL, FastAPI, LangGraph checkpointing, Deep Agents runtime, Vue 3.

---

## File Map

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentRunCheckpoint.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentRunCheckpointMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentAuditExport.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentAuditExportMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentWorkspaceServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentRunController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentWorkspaceController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/InternalAgentController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`
- Modify: `agent-service/app/runtime/langgraph_engine.py`
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Create: `agent-service/app/runtime/checkpoints.py`
- Create: `agent-service/app/observability/metrics.py`
- Create: `agent-service/tests/test_production_hardening.py`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`

## Task G1: Checkpoint Storage Decision

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentRunCheckpoint.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentRunCheckpointMapper.java`
- Create: `agent-service/app/runtime/checkpoints.py`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`
- Test: `agent-service/tests/test_production_hardening.py`

- [ ] **Step 1: Write checkpoint contract tests**

Backend test asserts checkpoints are stored by `runId`, `runtime`, `checkpointKey`, `payloadJson`, and `sequenceNo`. Agent-service test asserts checkpoint client writes only through backend signed API.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#storesRunCheckpoint" test`
Expected: FAIL because checkpoint storage is absent.

- [ ] **Step 2: Implement database-backed checkpoints**

Use MySQL/H2 table `agent_run_checkpoints` as the durable checkpoint store for Phase G. Redis remains for transient locks and rate counters.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#storesRunCheckpoint" test`
Expected: PASS.

- [ ] **Step 3: Implement agent-service checkpoint client**

Add `CheckpointStore.save(run_id, runtime, key, payload)` and `load_latest(run_id, runtime)`.

Run: `cd agent-service; pytest tests/test_production_hardening.py::test_checkpoint_store_uses_backend_api -q`
Expected: PASS.

## Task G2: Run Resume API

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentRunController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `agent-service/app/runtime/langgraph_engine.py`
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`
- Test: `agent-service/tests/test_production_hardening.py`

- [ ] **Step 1: Write resume API tests**

Assert `POST /api/v1/agent/runs/{runId}/resume` requires run ownership, refuses terminal runs, creates `run.resumed` event, and calls agent-service with the original run ID.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#resumesInterruptedRun" test`
Expected: FAIL because resume endpoint is absent.

- [ ] **Step 2: Implement backend resume orchestration**

Allow resume only from `INTERRUPTED`, `WAITING_CONFIRMATION`, or `FAILED_RETRYABLE`. Preserve credit accounting and append audit events.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#resumesInterruptedRun" test`
Expected: PASS.

- [ ] **Step 3: Implement runtime resume path**

Agent-service loads latest checkpoint, restores runtime state, and continues through the selected runtime engine.

Run: `cd agent-service; pytest tests/test_production_hardening.py::test_runtime_resume_loads_latest_checkpoint -q`
Expected: PASS.

## Task G3: Audit Export

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentAuditExport.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/mapper/AgentAuditExportMapper.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentWorkspaceController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`

- [ ] **Step 1: Write audit export test**

Assert workspace owner can request `POST /api/v1/agent/workspaces/{workspaceId}/audit-exports`, then download a JSONL export containing run events, tool calls, file reads, memory changes, confirmations, and credit events.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#exportsWorkspaceAuditJsonl" test`
Expected: FAIL because audit export APIs are absent.

- [ ] **Step 2: Implement export generation**

Create export rows with `status`, `requestedByUserId`, `workspaceId`, `format`, `objectKey`, and timestamps. Generate JSONL with one event per line and mask secrets.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#exportsWorkspaceAuditJsonl" test`
Expected: PASS.

## Task G4: Metrics Emission

**Files:**
- Create: `agent-service/app/observability/metrics.py`
- Modify: `agent-service/app/core/runtime.py`
- Modify: `agent-service/app/runtime/langgraph_engine.py`
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Test: `agent-service/tests/test_production_hardening.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`

- [ ] **Step 1: Write metrics tests**

Agent-service test asserts counters and timers are emitted for runtime selected, run completed, run failed, model call latency, tool success, tool failure, memory hit, and Deep Agents fallback. Backend test asserts run lifecycle metrics are emitted with workspace and runtime tags.

Run: `cd agent-service; pytest tests/test_production_hardening.py::test_runtime_metrics_are_emitted -q`
Expected: FAIL because metrics wrapper is absent.

- [ ] **Step 2: Implement metrics facade**

Add a lightweight metrics facade that can run without a metrics backend in tests. Use stable metric names and low-cardinality tags.

Run: `cd agent-service; pytest tests/test_production_hardening.py::test_runtime_metrics_are_emitted -q`
Expected: PASS.

- [ ] **Step 3: Verify backend metrics**

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#emitsRunLifecycleMetrics" test`
Expected: PASS.

## Task G5: Workspace Sharing Permissions

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/entity/AgentWorkspaceMember.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentWorkspaceServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/controller/AgentWorkspaceController.java`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`

- [ ] **Step 1: Write sharing permission tests**

Assert owner can invite a member as `READER` or `EDITOR`, editor can create sessions and memory, reader can view but cannot mutate, and removed member loses all workspace access.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#enforcesWorkspaceSharingRoles" test`
Expected: FAIL because sharing endpoints and role checks are incomplete.

- [ ] **Step 2: Implement sharing APIs**

Add `POST /api/v1/agent/workspaces/{workspaceId}/members`, `PUT /members/{memberId}`, and `DELETE /members/{memberId}`. Apply role checks across sessions, files, memory, artifacts, and knowledge retrieval.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#enforcesWorkspaceSharingRoles" test`
Expected: PASS.

- [ ] **Step 3: Add frontend API wrappers**

Add typed wrappers for listing, adding, updating, and removing workspace members. Keep UI wiring limited to existing workspace settings surface if present; otherwise expose API helpers only in Phase G.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: PASS.

## Task G6: Long-Task Cancellation Regression

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `agent-service/app/core/runtime.py`
- Modify: `agent-service/app/runtime/deep_agents_engine.py`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentProductionHardeningTest.java`
- Test: `agent-service/tests/test_production_hardening.py`

- [ ] **Step 1: Write cancellation regression tests**

Backend test asserts cancel releases reserved credits, appends `run.cancelled`, rejects duplicate cancellation cleanly, and blocks resume. Agent-service test asserts runtime checks cancellation between model calls, file reads, tool calls, and sub-agent starts.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#cancelLongTaskReleasesCreditsAndBlocksResume" test`
Expected: FAIL until cancellation state is enforced throughout long-task paths.

- [ ] **Step 2: Implement backend cancellation state machine**

Make cancellation idempotent. Transition active or interrupted runs to `CANCELLED`, release unused credits, and publish SSE event.

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest#cancelLongTaskReleasesCreditsAndBlocksResume" test`
Expected: PASS.

- [ ] **Step 3: Implement runtime cancellation checks**

Poll backend cancellation state at bounded intervals and before side-effectful operations. Stop execution with a clean cancelled result.

Run: `cd agent-service; pytest tests/test_production_hardening.py::test_runtime_stops_when_run_cancelled -q`
Expected: PASS.

- [ ] **Step 4: Verify frontend cancel control**

Ensure Agent page cancel button disables after click, shows cancelled state from SSE, and does not offer resume for cancelled runs.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: PASS.

## Task G7: Phase G Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run backend hardening tests**

Run: `mvn -f backend\pom.xml "-Dtest=AgentProductionHardeningTest,AgentApiTest,RedisAgentRateLimitServiceTest" test`
Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 2: Run agent-service hardening tests**

Run: `cd agent-service; pytest tests/test_production_hardening.py tests/test_deep_agents_runtime.py tests/test_runtime_router.py -q`
Expected: all selected tests pass.

- [ ] **Step 3: Run user-web checks**

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit; npm run build`
Expected: typecheck and Vite build complete successfully.

- [ ] **Step 4: Run cancellation smoke**

Run a local long-task request, cancel it from user-web, and verify backend event stream includes `run.cancelled` and no later `run.completed` event for the same run ID.

Expected: cancellation is terminal, credits are released, and UI remains on the cancelled state.

- [ ] **Step 5: Commit Phase G**

Run: `git add backend agent-service user-web && git commit -m "feat: harden agent platform production runtime"`
Expected: commit records only Phase G implementation files.
