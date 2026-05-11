# Agent Platform Phase F Knowledge Base Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add business knowledge as a permissioned, cited, pluggable context source separate from workspace memory.

**Architecture:** Spring Boot owns knowledge bases, document permissions, ingestion metadata, and retrieval APIs. Agent Service requests cited snippets through signed APIs and labels them separately from workspace memory and uploaded files.

**Tech Stack:** Spring Boot 3, MySQL/H2, FastAPI, LangChain text splitting/retrieval interfaces, Vue 3.

---

## File Map

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeBase.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeDocument.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeDocumentChunk.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgePermission.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/mapper/KnowledgeBaseMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/mapper/KnowledgeDocumentMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/mapper/KnowledgeDocumentChunkMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/mapper/KnowledgePermissionMapper.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/service/KnowledgeBaseService.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/controller/KnowledgeBaseController.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/controller/InternalKnowledgeController.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`
- Create: `agent-service/app/knowledge/context_provider.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Create: `agent-service/tests/test_knowledge_context.py`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/pages/AgentHome/KnowledgeSourcesPanel.vue`

## Task F1: Knowledge Base Tables

**Files:**
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeBase.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeDocument.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgeDocumentChunk.java`
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/entity/KnowledgePermission.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`

- [ ] **Step 1: Write create/list API test**

Assert authenticated user can create a knowledge base with `name`, `description`, and `visibility`, then list it.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#createsAndListsKnowledgeBase" test`
Expected: FAIL because knowledge package and endpoints are absent.

- [ ] **Step 2: Add schema and entities**

Add tables `knowledge_bases`, `knowledge_documents`, `knowledge_document_chunks`, and `knowledge_permissions`. Use `status`, `created_at`, and `updated_at` columns on all tables.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#createsAndListsKnowledgeBase" test`
Expected: FAIL until service and controller are implemented.

- [ ] **Step 3: Implement create/list service and controller**

Add `POST /api/v1/agent/knowledge-bases` and `GET /api/v1/agent/knowledge-bases`. Creator receives `OWNER` permission.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#createsAndListsKnowledgeBase" test`
Expected: PASS.

## Task F2: Document Ingestion Metadata

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/controller/KnowledgeBaseController.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`

- [ ] **Step 1: Write ingestion metadata test**

Assert `POST /api/v1/agent/knowledge-bases/{baseId}/documents` stores `originalFilename`, `contentType`, `byteSize`, `checksum`, `parserStatus`, and `sourceVersion`.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#storesDocumentIngestionMetadata" test`
Expected: FAIL because document upload metadata API is absent.

- [ ] **Step 2: Implement metadata persistence**

Accept multipart upload, store metadata, parse text with the existing Agent file parser service when possible, and mark parser status as `PARSED` or `FAILED` with a safe error message.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#storesDocumentIngestionMetadata" test`
Expected: PASS.

## Task F3: Permission Checks

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`

- [ ] **Step 1: Write permission denial tests**

Assert non-member cannot list documents, upload documents, retrieve chunks, or grant permissions. Assert `READER` can retrieve chunks but cannot upload.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#enforcesKnowledgeBasePermissions" test`
Expected: FAIL because membership checks are incomplete.

- [ ] **Step 2: Implement permission guard**

Add role checks for `OWNER`, `EDITOR`, and `READER`. Apply guards to every public and internal knowledge operation.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#enforcesKnowledgeBasePermissions" test`
Expected: PASS.

## Task F4: Chunk Retrieval

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/controller/InternalKnowledgeController.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`

- [ ] **Step 1: Write signed retrieval test**

Signed request to `POST /api/internal/v1/agent/knowledge/retrieve` with `workspaceId`, `query`, and `limit` returns matching chunks from knowledge bases visible to the workspace user. Unsigned request receives `401`.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#retrievesKnowledgeChunksForSignedInternalRequest" test`
Expected: FAIL because internal retrieval endpoint is absent.

- [ ] **Step 2: Implement retrieval query**

Use simple text matching over chunk text and title for Phase F. Return `chunkId`, `documentId`, `baseId`, `title`, `snippet`, `score`, and `sourceVersion`.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#retrievesKnowledgeChunksForSignedInternalRequest" test`
Expected: PASS.

## Task F5: Citation Payloads

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/knowledge/controller/InternalKnowledgeController.java`
- Modify: `agent-service/app/core/schemas.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/knowledge/KnowledgeBaseApiTest.java`

- [ ] **Step 1: Write citation contract test**

Assert every retrieved chunk includes citation fields `sourceType=KNOWLEDGE_BASE`, `sourceLabel`, `documentTitle`, `chunkId`, `sourceVersion`, and `retrievedAt`.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#knowledgeRetrievalIncludesCitationPayload" test`
Expected: FAIL until response contract is expanded.

- [ ] **Step 2: Implement citation fields**

Build citations in the backend response. In agent-service, add `KnowledgeCitation` and `KnowledgeContextItem` Pydantic models.

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest#knowledgeRetrievalIncludesCitationPayload" test`
Expected: PASS.

## Task F6: Agent-Service Context Injection

**Files:**
- Create: `agent-service/app/knowledge/context_provider.py`
- Modify: `agent-service/app/clients/backend_client.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Test: `agent-service/tests/test_knowledge_context.py`

- [ ] **Step 1: Write context provider test**

Assert context provider calls backend retrieval with run `workspaceId` and user message, caps results to five, and formats a `Knowledge base context` section with citations.

Run: `cd agent-service; pytest tests/test_knowledge_context.py::test_knowledge_context_provider_formats_cited_snippets -q`
Expected: FAIL because provider is absent.

- [ ] **Step 2: Implement provider and graph injection**

Retrieve knowledge after workspace memory and before file chunks. Label citations as `kb:<chunkId>` and keep them in the final answer source metadata.

Run: `cd agent-service; pytest tests/test_knowledge_context.py -q`
Expected: PASS.

## Task F7: Frontend Source Display

**Files:**
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/api/agentApi.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Create: `user-web/src/pages/AgentHome/KnowledgeSourcesPanel.vue`

- [ ] **Step 1: Add source display types**

Add `AgentCitation` with `sourceType`, `sourceLabel`, `documentTitle`, `chunkId`, `sourceVersion`, and `retrievedAt`. Parse citations from run events and final message metadata.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: FAIL until the component is added and imports compile.

- [ ] **Step 2: Render source panel**

Show knowledge sources separately from uploaded files and workspace memory. Each source row displays document title, knowledge base label, version, and retrieval time.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: PASS.

## Task F8: Phase F Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run backend knowledge tests**

Run: `mvn -f backend\pom.xml "-Dtest=KnowledgeBaseApiTest,AgentApiTest" test`
Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 2: Run agent-service knowledge tests**

Run: `cd agent-service; pytest tests/test_knowledge_context.py tests/test_workspace_memory.py -q`
Expected: all selected tests pass.

- [ ] **Step 3: Run user-web checks**

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit; npm run build`
Expected: typecheck and Vite build complete successfully.

- [ ] **Step 4: Commit Phase F**

Run: `git add backend agent-service user-web && git commit -m "feat: add cited knowledge base context"`
Expected: commit records only Phase F implementation files.
