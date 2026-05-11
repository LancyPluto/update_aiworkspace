# Agent Platform Phase E Tool Policy Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand marketplace tools into policy-controlled Agent tools with side-effect levels, schema validation, argument extraction, and confirmation rules.

**Architecture:** Spring Boot emits signed tool descriptors and enforces final execution policy. Agent Service selects, extracts, and validates arguments but can only call backend-approved tools. user-web renders side-effect-aware confirmation cards.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, FastAPI, LangChain structured output, JSON Schema, Vue 3.

---

## File Map

- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/enums/AgentToolSideEffectLevel.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/entity/AiTool.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolDescriptorResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolFieldDescriptorResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Create: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolPolicyTest.java`
- Modify: `agent-service/app/core/schemas.py`
- Modify: `agent-service/app/tools/registry.py`
- Create: `agent-service/app/tools/schema_converter.py`
- Create: `agent-service/app/tools/tool_selector.py`
- Create: `agent-service/app/tools/argument_extractor.py`
- Create: `agent-service/app/tools/schema_validator.py`
- Create: `agent-service/tests/test_tool_policy.py`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`

## Task E1: Tool Side-Effect Enum

**Files:**
- Create: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/enums/AgentToolSideEffectLevel.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/tool/entity/AiTool.java`
- Modify: `backend/src/test/resources/schema-test.sql`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolPolicyTest.java`

- [ ] **Step 1: Write descriptor side-effect test**

Create a test asserting every Agent tool descriptor includes one of `READ_ONLY`, `GENERATE_CONTENT`, `MUTATE_WORKSPACE`, `EXTERNAL_ACTION`, `ACCOUNT_OR_PAYMENT`, or `ADMIN_ACTION`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#agentToolDescriptorsIncludeSideEffectLevel" test`
Expected: FAIL because descriptors do not expose the field.

- [ ] **Step 2: Add enum and persistence field**

Add enum `AgentToolSideEffectLevel`. Add `side_effect_level` to tool schema and map it in `AiTool`, defaulting existing tools to `GENERATE_CONTENT`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#agentToolDescriptorsIncludeSideEffectLevel" test`
Expected: PASS.

## Task E2: Descriptor Schema Upgrade

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolDescriptorResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/AgentToolFieldDescriptorResponse.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolPolicyTest.java`

- [ ] **Step 1: Write descriptor contract test**

Assert descriptor fields include `toolCode`, `toolName`, `description`, `category`, `inputSchema`, `estimatedCreditCost`, `autoCallable`, `requiresConfirmation`, `sideEffectLevel`, `permissions`, `agentHints`, and `version`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#agentToolDescriptorUsesExpandedContract" test`
Expected: FAIL until DTO and service are upgraded.

- [ ] **Step 2: Implement expanded descriptor**

Map category from existing tool category, derive `requiresConfirmation` from side-effect policy, emit `permissions` as a string list, emit `agentHints` from existing prompt or descriptor metadata, and set `version=1`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#agentToolDescriptorUsesExpandedContract" test`
Expected: PASS.

## Task E3: JSON Schema Conversion Tests

**Files:**
- Create: `agent-service/app/tools/schema_converter.py`
- Modify: `agent-service/app/core/schemas.py`
- Test: `agent-service/tests/test_tool_policy.py`

- [ ] **Step 1: Write conversion tests**

Test conversion of backend field descriptors into JSON Schema object properties for text, textarea, number, select, checkbox, and file inputs. Assert required fields are preserved.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_backend_fields_convert_to_json_schema -q`
Expected: FAIL because converter is absent.

- [ ] **Step 2: Implement converter**

Create `to_json_schema(tool_descriptor)` that returns a draft-compatible object schema with `type`, `properties`, `required`, and `additionalProperties=false`.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_backend_fields_convert_to_json_schema -q`
Expected: PASS.

## Task E4: Tool Selection Scoring

**Files:**
- Create: `agent-service/app/tools/tool_selector.py`
- Modify: `agent-service/app/tools/registry.py`
- Test: `agent-service/tests/test_tool_policy.py`

- [ ] **Step 1: Write scoring tests**

Assert exact tool code match scores highest, category match scores above generic description match, disabled tools receive no score, and unsafe side-effect levels are excluded for autonomous selection.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_tool_selection_scoring_prefers_relevant_safe_tools -q`
Expected: FAIL because selector is absent.

- [ ] **Step 2: Implement selector**

Score by tool code, name, category, description, and agent hints. Return a sorted list with `score`, `reason`, and the original descriptor.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_tool_selection_scoring_prefers_relevant_safe_tools -q`
Expected: PASS.

## Task E5: Argument Extraction

**Files:**
- Create: `agent-service/app/tools/argument_extractor.py`
- Modify: `agent-service/app/graphs/universal_agent_graph.py`
- Test: `agent-service/tests/test_tool_policy.py`

- [ ] **Step 1: Write deterministic extraction tests**

Given a selected tool with schema fields `topic`, `platform`, and `tone`, assert extraction returns arguments from the user message and asks for clarification when a required argument is missing.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_argument_extraction_returns_schema_shaped_arguments -q`
Expected: FAIL because extractor is absent.

- [ ] **Step 2: Implement extractor interface**

Implement `extract_arguments(message, tool, model_client)` returning `arguments`, `missingRequired`, and `confidence`. Use LangChain structured output when model client is enabled and deterministic field matching in mock mode.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_argument_extraction_returns_schema_shaped_arguments -q`
Expected: PASS.

## Task E6: Schema Validation

**Files:**
- Create: `agent-service/app/tools/schema_validator.py`
- Modify: `agent-service/app/tools/backend_tool.py`
- Test: `agent-service/tests/test_tool_policy.py`

- [ ] **Step 1: Write validation tests**

Assert valid arguments pass, missing required fields fail with field names, unknown fields fail, and wrong primitive types fail before backend execution.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_tool_arguments_validate_against_json_schema -q`
Expected: FAIL because validator is absent.

- [ ] **Step 2: Implement validator**

Use the project-approved JSON Schema validator package or Python `jsonschema` if already available. Return a structured validation result instead of raising raw library exceptions.

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_tool_arguments_validate_against_json_schema -q`
Expected: PASS.

## Task E7: Confirmation Card Payload

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/dto/CreateAgentToolCallRequest.java`
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `user-web/src/api/types.ts`
- Modify: `user-web/src/pages/AgentHome/Page.vue`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolPolicyTest.java`

- [ ] **Step 1: Write confirmation payload test**

Assert `tool.confirmation_required` event JSON includes `toolCode`, `toolName`, `sideEffectLevel`, `argumentPreview`, `estimatedCreditCost`, `riskText`, and `scope`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#confirmationEventIncludesPolicyPayload" test`
Expected: FAIL because event JSON is incomplete.

- [ ] **Step 2: Implement backend payload**

Populate confirmation event JSON from the expanded descriptor and validated arguments. Keep sensitive values masked in `argumentPreview`.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#confirmationEventIncludesPolicyPayload" test`
Expected: PASS.

- [ ] **Step 3: Update frontend rendering**

Render the side-effect level, scope, cost, and masked argument preview in the existing confirmation card area.

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
Expected: PASS.

## Task E8: Unsafe Tool Denial

**Files:**
- Modify: `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentRunServiceImpl.java`
- Modify: `agent-service/app/tools/tool_selector.py`
- Test: `backend/src/test/java/com/aiminilab/aitoolmarket/agent/AgentToolPolicyTest.java`
- Test: `agent-service/tests/test_tool_policy.py`

- [ ] **Step 1: Write denial tests**

Backend test asserts `ACCOUNT_OR_PAYMENT` and `ADMIN_ACTION` tool calls from Agent runtime are rejected with `403`. Agent-service test asserts selector never auto-selects those levels.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#unsafeToolsAreDeniedForAgentExecution" test`
Expected: FAIL until policy denial is enforced.

- [ ] **Step 2: Enforce final backend denial**

Add final policy check before any tool call execution. Return a user-safe refusal event and fail the tool call without invoking provider execution.

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest#unsafeToolsAreDeniedForAgentExecution" test`
Expected: PASS.

- [ ] **Step 3: Verify agent-service exclusion**

Run: `cd agent-service; pytest tests/test_tool_policy.py::test_tool_selection_never_autoselects_denied_side_effect_levels -q`
Expected: PASS.

## Task E9: Phase E Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run backend tool policy tests**

Run: `mvn -f backend\pom.xml "-Dtest=AgentToolPolicyTest,AgentApiTest" test`
Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 2: Run agent-service tool tests**

Run: `cd agent-service; pytest tests/test_tool_policy.py tests/test_tool_registry.py -q`
Expected: all selected tests pass.

- [ ] **Step 3: Run user-web checks**

Run: `cd user-web; node node_modules\vue-tsc\bin\vue-tsc.js --noEmit; npm run build`
Expected: typecheck and Vite build complete successfully.

- [ ] **Step 4: Commit Phase E**

Run: `git add backend agent-service user-web && git commit -m "feat: expand agent tool policy"`
Expected: commit records only Phase E implementation files.
