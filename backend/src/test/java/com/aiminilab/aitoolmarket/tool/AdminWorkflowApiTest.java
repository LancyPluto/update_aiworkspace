package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作流画布管理链路：保存（保持状态）→ 校验 → 发布 → 再保存不掉发布状态 → 下线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_workflow_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminWorkflowApiTest {

    private static final String VALID_NODES = """
            [
              {"id":"start","data":{"nodeDefType":"start","title":"开始"}},
              {"id":"output","data":{"nodeDefType":"video_output","title":"成片输出"}}
            ]
            """;
    private static final String VALID_EDGES = """
            [{"id":"e1","source":"start","target":"output"}]
            """;
    private static final String INVALID_NODES = """
            [{"id":"start","data":{"nodeDefType":"start","title":"开始"}}]
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ToolWorkflowVersionMapper versionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void staleDraftRevisionReturnsConflictWithoutOverwritingLatestDraft() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_draft_revision_tool");

        MvcResult created = saveWorkflow(adminToken, toolId, INVALID_NODES, "[]", 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftRevision").value(1))
                .andReturn();
        long revision = responseData(created).path("draftRevision").asLong();

        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES, revision)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftRevision").value(2));

        saveWorkflow(adminToken, toolId, INVALID_NODES, "[]", revision)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.data.latestDraftRevision").value(2));

        MvcResult latest = mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftRevision").value(2))
                .andReturn();
        assertThat(objectMapper.readTree(responseData(latest).path("nodesJson").asText()))
                .isEqualTo(objectMapper.readTree(VALID_NODES));
    }

    @Test
    void publishCreatesImmutableVersionAndLaterDraftSaveKeepsPublishedSnapshot() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_immutable_publish_tool");

        MvcResult saved = saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES, 0L)
                .andExpect(status().isOk())
                .andReturn();
        long revision = responseData(saved).path("draftRevision").asLong();

        MvcResult published = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedVersionId").isNumber())
                .andExpect(jsonPath("$.data.executionEnabled").value(false))
                .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(false))
                .andReturn();
        long versionId = responseData(published).path("publishedVersionId").asLong();

        saveWorkflow(adminToken, toolId, INVALID_NODES, "[]", revision)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedVersionId").value(versionId))
                .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(true));

        ToolWorkflowVersion immutableVersion = versionMapper.selectById(versionId);
        assertThat(objectMapper.readTree(immutableVersion.getNodesJson()))
                .isEqualTo(objectMapper.readTree(VALID_NODES));
        assertThat(objectMapper.readTree(immutableVersion.getEdgesJson()))
                .isEqualTo(objectMapper.readTree(VALID_EDGES));
        assertThat(versionMapper.countByWorkflowId(immutableVersion.getWorkflowId())).isEqualTo(1);
    }

    @Test
    void toolLifecyclePublishesWorkflowAndFreezesCurrentInputSchema() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_tool_lifecycle");
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES, 0L)
                .andExpect(status().isOk());

        MvcResult firstPublish = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.executionMode").value("WORKFLOW"))
                .andExpect(jsonPath("$.data.billingMode").value("WORKFLOW_STEP"))
                .andExpect(jsonPath("$.data.agentSurfaceEnabled").value(true))
                .andExpect(jsonPath("$.data.workflowConfigured").value(true))
                .andExpect(jsonPath("$.data.workflowExecutionEnabled").value(true))
                .andExpect(jsonPath("$.data.workflowUsable").value(true))
                .andReturn();
        long firstVersionId = responseData(firstPublish).path("publishedWorkflowVersionId").asLong();
        ToolWorkflowVersion firstVersion = versionMapper.selectById(firstVersionId);
        JsonNode firstSchema = objectMapper.readTree(firstVersion.getInputSchemaSnapshotJson());
        assertThat(firstSchema.path("type").asText()).isEqualTo("object");
        assertThat(firstSchema.path("properties").has("productName")).isTrue();
        assertThat(firstSchema.path("required").toString()).contains("productName");

        mockMvc.perform(get("/api/v1/agents/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "wf_tool_lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].toolCode").value("wf_tool_lifecycle"));
        mockMvc.perform(get("/api/v1/agent/tools")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].toolCode").value(hasItem("wf_tool_lifecycle")));

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}/fields", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": [{
                                    "fieldKey": "story",
                                    "fieldName": "Story",
                                    "fieldType": "textarea",
                                    "placeholder": "Describe the story",
                                    "required": true,
                                    "executionRequired": true,
                                    "userRequired": true,
                                    "agentFillStrategy": "ask_user",
                                    "riskLevel": "LOW",
                                    "sortOrder": 1
                                  }]
                                }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(true));
        assertThat(objectMapper.readTree(versionMapper.selectById(firstVersionId).getInputSchemaSnapshotJson()))
                .isEqualTo(firstSchema);

        MvcResult secondPublish = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        long secondVersionId = responseData(secondPublish).path("publishedWorkflowVersionId").asLong();
        assertThat(secondVersionId).isNotEqualTo(firstVersionId);
        JsonNode secondSchema = objectMapper.readTree(versionMapper.selectById(secondVersionId).getInputSchemaSnapshotJson());
        assertThat(secondSchema.path("properties").has("story")).isTrue();
        assertThat(secondSchema.path("properties").has("productName")).isFalse();
        assertThat(versionMapper.selectById(secondVersionId).getDslHash())
                .isNotEqualTo(firstVersion.getDslHash());

        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, groups_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, 'alternate', ?, ?, NULL, '{}', 1, 'PUBLISHED', 1, 1,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, VALID_NODES, VALID_EDGES);

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/offline", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"))
                .andExpect(jsonPath("$.data.agentSurfaceEnabled").value(false))
                .andExpect(jsonPath("$.data.workflowExecutionEnabled").value(false))
                .andExpect(jsonPath("$.data.publishedWorkflowVersionId").value(secondVersionId))
                .andExpect(jsonPath("$.data.workflowUsable").value(false));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'default'",
                String.class, toolId))
                .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tool_workflows WHERE tool_id = ? AND execution_enabled = 1",
                Integer.class, toolId)).isZero();

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedVersionId").value(secondVersionId))
                .andExpect(jsonPath("$.data.executionEnabled").value(false));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedWorkflowVersionId").value(secondVersionId))
                .andExpect(jsonPath("$.data.workflowUsable").value(true));
        assertThat(versionMapper.countByWorkflowId(firstVersion.getWorkflowId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tool_workflows WHERE tool_id = ? AND execution_enabled = 1",
                Integer.class, toolId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT workflow_name FROM tool_workflows WHERE tool_id = ? AND execution_enabled = 1",
                String.class, toolId)).isEqualTo("default");
    }

    @Test
    void unboundWorkflowLifecycleDoesNotRequireToolLevelModelDuringPublishAndOnlineEditing() throws Exception {
        String adminToken = login();
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "wf_ignores_direct_model_gate",
                                  "toolName": "Workflow Model Gate",
                                  "categoryId": 1,
                                  "description": "workflow owns its node dependencies",
                                  "toolType": "IMAGE_TO_IMAGE",
                                  "inputModality": "IMAGE",
                                  "outputModality": "IMAGE",
                                  "estimatedCreditCost": 3,
                                  "executionHandler": "IMAGE_GENERATION"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long toolId = Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES, 0L)
                .andExpect(status().isOk());

        MvcResult published = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.workflowExecutionEnabled").value(true))
                .andReturn();
        long publishedVersionId = responseData(published).path("publishedWorkflowVersionId").asLong();

        mockMvc.perform(put("/api/admin/v1/tools/{toolId}", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "wf_ignores_direct_model_gate",
                                  "toolName": "Workflow Model Gate Updated",
                                  "categoryId": 1,
                                  "description": "online workflow edit",
                                  "toolType": "IMAGE_TO_IMAGE",
                                  "inputModality": "IMAGE",
                                  "outputModality": "IMAGE",
                                  "estimatedCreditCost": 3,
                                  "executionHandler": "IMAGE_GENERATION"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/apply-template", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "templateCode": "image_generation_default",
                                  "applyMetadata": true,
                                  "overwritePrompt": true
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedVersionId").value(publishedVersionId))
                .andExpect(jsonPath("$.data.executionEnabled").value(true))
                .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(true));
    }

    @Test
    void toolPublishRejectsInvalidWorkflowWithoutPartialActivation() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_invalid_tool_publish");
        saveWorkflow(adminToken, toolId, INVALID_NODES, "[]", 0L)
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tools WHERE id = ?", String.class, toolId)).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_mode FROM ai_tools WHERE id = ?", String.class, toolId)).isEqualTo("DIRECT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT billing_mode FROM ai_tools WHERE id = ?", String.class, toolId)).isEqualTo("FIXED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT agent_surface_enabled FROM ai_tools WHERE id = ?", Integer.class, toolId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM tool_workflows WHERE tool_id = ?", Integer.class, toolId)).isZero();
    }

    @Test
    void republishSnapshotsNewPricingWithoutChangingOldVersion() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_pricing_snapshot_tool");
        long modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE enabled = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                Long.class
        );
        var originalModel = jdbcTemplate.queryForMap("""
                SELECT billing_unit, unit_price,
                       input_token_price_per_1k, output_token_price_per_1k,
                       input_token_price_per_1m, output_token_price_per_1m
                FROM agent_model_configs WHERE id = ?
                """, modelId);
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                    "parameters":{"modelConfigId":%d,"maxCreditCost":9999,"maxProviderCostCny":9999,"duration":5}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """.formatted(modelId);
        String edges = """
                [
                  {"id":"e1","source":"start","target":"worker"},
                  {"id":"e2","source":"worker","target":"output"}
                ]
                """;
        try {
            jdbcTemplate.update("""
                    UPDATE agent_model_configs
                    SET billing_unit = 'PER_CALL', unit_price = 0.10
                    WHERE id = ?
                    """, modelId);
            jdbcTemplate.update("""
                    INSERT INTO pricing_margins(
                      scope_type, scope_ref, markup_ratio, min_credits, enabled, remark
                    ) VALUES ('MODEL', ?, 1.5, 0, 1, 'version one')
                    """, modelId);
            jdbcTemplate.update("""
                    INSERT INTO pricing_rules(
                      scope_type, scope_ref, param_key, rule_type, match_op, match_value,
                      factor, extra_credits, priority, enabled, remark
                    ) VALUES ('TOOL', ?, 'duration', 'MULTIPLIER', 'VALUE', NULL,
                              2.0, 0, 1, 1, 'version one')
                    """, toolId);

            MvcResult saved = saveWorkflow(adminToken, toolId, nodes, edges, 0L)
                    .andExpect(status().isOk())
                    .andReturn();
            long revision = responseData(saved).path("draftRevision").asLong();
            MvcResult firstPublished = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();
            long firstVersionId = responseData(firstPublished).path("publishedVersionId").asLong();
            JsonNode firstPolicy = billingPolicy(firstVersionId).path("nodePolicies").path("worker");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT minimum_required_credits FROM ai_tools WHERE id = ?", Integer.class, toolId))
                    .isEqualTo(150);
            mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.publishedVersionId").value(firstVersionId));
            assertThat(versionMapper.countByWorkflowId(
                    versionMapper.selectById(firstVersionId).getWorkflowId())).isEqualTo(1);

            jdbcTemplate.update("""
                    UPDATE agent_model_configs SET unit_price = 0.90 WHERE id = ?
                    """, modelId);
            jdbcTemplate.update("""
                    UPDATE pricing_margins SET markup_ratio = 3.0
                    WHERE scope_type = 'MODEL' AND scope_ref = ?
                    """, modelId);
            jdbcTemplate.update("""
                    UPDATE pricing_rules SET factor = 4.0
                    WHERE scope_type = 'TOOL' AND scope_ref = ?
                    """, toolId);
            jdbcTemplate.update(
                    "UPDATE ai_tools SET minimum_required_credits = 1 WHERE id = ?", toolId);
            mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasUnpublishedChanges").value(true));
            MvcResult secondPublished = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();
            long secondVersionId = responseData(secondPublished).path("publishedVersionId").asLong();
            JsonNode secondPolicy = billingPolicy(secondVersionId).path("nodePolicies").path("worker");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT minimum_required_credits FROM ai_tools WHERE id = ?", Integer.class, toolId))
                    .isEqualTo(5400);

            assertThat(firstVersionId).isNotEqualTo(secondVersionId);
            assertThat(versionMapper.selectById(firstVersionId).getDslHash())
                    .isNotEqualTo(versionMapper.selectById(secondVersionId).getDslHash());
            assertThat(firstPolicy.path("modelPricingSnapshot").path("unitPrice").decimalValue())
                    .isEqualByComparingTo("0.10");
            assertThat(firstPolicy.path("pricingPolicy").path("markupRatio").decimalValue())
                    .isEqualByComparingTo("1.5");
            assertThat(firstPolicy.path("pricingPolicy").path("rules").path(0).path("factor").decimalValue())
                    .isEqualByComparingTo("2.0");
            assertThat(firstPolicy.path("staticParams").path("duration").asInt()).isEqualTo(5);
            assertThat(firstPolicy.path("staticParams").has("maxCreditCost")).isFalse();
            assertThat(firstPolicy.path("maxCreditCost").asInt()).isEqualTo(150);
            assertThat(firstPolicy.path("maxProviderCostCny").decimalValue())
                    .isEqualByComparingTo("1.000000");
            assertThat(secondPolicy.path("modelPricingSnapshot").path("unitPrice").decimalValue())
                    .isEqualByComparingTo("0.90");
            assertThat(secondPolicy.path("pricingPolicy").path("markupRatio").decimalValue())
                    .isEqualByComparingTo("3.0");
            assertThat(secondPolicy.path("pricingPolicy").path("rules").path(0).path("factor").decimalValue())
                    .isEqualByComparingTo("4.0");
            assertThat(secondPolicy.path("maxCreditCost").asInt()).isEqualTo(5400);
            assertThat(secondPolicy.path("maxProviderCostCny").decimalValue())
                    .isEqualByComparingTo("18.000000");
            assertThat(billingPolicy(firstVersionId)
                    .path("nodePolicies").path("worker")
                    .path("modelPricingSnapshot").path("unitPrice").decimalValue())
                    .isEqualByComparingTo("0.10");
        } finally {
            jdbcTemplate.update("DELETE FROM pricing_rules WHERE scope_type = 'TOOL' AND scope_ref = ?", toolId);
            jdbcTemplate.update("DELETE FROM pricing_margins WHERE scope_type = 'MODEL' AND scope_ref = ?", modelId);
            jdbcTemplate.update("""
                    UPDATE agent_model_configs
                    SET billing_unit = ?, unit_price = ?,
                        input_token_price_per_1k = ?, output_token_price_per_1k = ?,
                        input_token_price_per_1m = ?, output_token_price_per_1m = ?
                    WHERE id = ?
                    """,
                    originalModel.get("billing_unit"), originalModel.get("unit_price"),
                    originalModel.get("input_token_price_per_1k"), originalModel.get("output_token_price_per_1k"),
                    originalModel.get("input_token_price_per_1m"), originalModel.get("output_token_price_per_1m"),
                    modelId);
        }
    }

    @Test
    void publishRejectsTokenPricedModelWithoutTokenEstimate() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_missing_token_estimate");
        long modelId = createTokenPricedModel("wf_missing_token_estimate_model");
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                    "parameters":{"modelConfigId":%d}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """.formatted(modelId);
        String edges = """
                [
                  {"id":"e1","source":"start","target":"worker"},
                  {"id":"e2","source":"worker","target":"output"}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L)
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("automatic pricing estimate")));
    }

    @Test
    void publishIgnoresLegacyCostFieldsAndUsesToolFallbackForModelLessWorker() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_legacy_cost_fields_ignored");
        String edges = """
                [
                  {"id":"e1","source":"start","target":"worker"},
                  {"id":"e2","source":"worker","target":"output"}
                ]
                """;
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"worker","data":{"nodeDefType":"subtitle","title":"Worker",
                    "parameters":{"handlerKey":"report.export","maxCreditCost":"not-a-number",
                      "maxProviderCostCny":9999,"providerCostMode":"NONE"}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L).andExpect(status().isOk());

        MvcResult published = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode policy = billingPolicy(responseData(published).path("publishedVersionId").asLong())
                .path("nodePolicies").path("worker");
        assertThat(policy.path("pricingSource").asText()).isEqualTo("TOOL_FALLBACK");
        assertThat(policy.path("maxCreditCost").asInt()).isEqualTo(1);
        assertThat(policy.path("maxProviderCostCny").decimalValue()).isEqualByComparingTo("0.000000");
        assertThat(policy.path("modelPricingSnapshot").isNull()).isTrue();
        assertThat(policy.path("staticParams").path("handlerKey").asText()).isEqualTo("report.export");
        assertThat(policy.path("staticParams").has("maxCreditCost")).isFalse();
        assertThat(policy.path("staticParams").has("maxProviderCostCny")).isFalse();
        assertThat(policy.path("staticParams").has("providerCostMode")).isFalse();
    }

    @Test
    void publishSnapshotsComicComposeAsAutomaticLocalZeroCost() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_local_provider_cost_none");
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"compose","data":{"nodeDefType":"subtitle","title":"Compose",
                    "parameters":{"handlerKey":"comic.compose","maxCreditCost":5,
                      "providerCostMode":"NONE","maxProviderCostCny":0}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        String edges = """
                [
                  {"id":"e1","source":"start","target":"compose"},
                  {"id":"e2","source":"compose","target":"output"}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L).andExpect(status().isOk());

        MvcResult published = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode composePolicy = billingPolicy(responseData(published).path("publishedVersionId").asLong())
                .path("nodePolicies").path("compose");
        assertThat(composePolicy.path("pricingSource").asText()).isEqualTo("LOCAL_ZERO_COST");
        assertThat(composePolicy.path("maxProviderCostCny").decimalValue()).isEqualByComparingTo("0.000000");
        assertThat(composePolicy.path("maxCreditCost").asInt()).isZero();
        assertThat(composePolicy.path("fallbackChargeCredits").asInt()).isZero();
        assertThat(composePolicy.path("staticParams").path("handlerKey").asText()).isEqualTo("comic.compose");
        assertThat(composePolicy.path("staticParams").has("maxCreditCost")).isFalse();
        assertThat(composePolicy.path("staticParams").has("maxProviderCostCny")).isFalse();
        assertThat(composePolicy.path("staticParams").has("providerCostMode")).isFalse();
    }

    @Test
    void publishUsesConfiguredTokenEstimateAndIgnoresLegacyCaps() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_token_estimate_publish");
        long modelId = createTokenPricedModel("wf_token_estimate_publish_model");
        jdbcTemplate.update("""
                INSERT INTO pricing_margins(
                  scope_type, scope_ref, markup_ratio, min_credits,
                  token_estimate_input_tokens, token_estimate_output_tokens, enabled, remark
                ) VALUES ('MODEL', ?, 1.5, 0, 1000, 500, 1, 'workflow token estimate')
                """, modelId);
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                    "parameters":{"modelConfigId":%d,"maxCreditCost":9999,
                      "providerCostMode":"NONE","maxProviderCostCny":9999}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """.formatted(modelId);
        String edges = """
                [
                  {"id":"e1","source":"start","target":"worker"},
                  {"id":"e2","source":"worker","target":"output"}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L).andExpect(status().isOk());

        MvcResult published = mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode policy = billingPolicy(responseData(published).path("publishedVersionId").asLong())
                .path("nodePolicies").path("worker");
        assertThat(policy.path("pricingSource").asText()).isEqualTo("MODEL_PRICING");
        assertThat(policy.path("maxCreditCost").asInt()).isEqualTo(2);
        assertThat(policy.path("maxProviderCostCny").decimalValue()).isEqualByComparingTo("0.004000");
        assertThat(policy.path("pricingPolicy").path("tokenEstimateInputTokens").asInt()).isEqualTo(1000);
        assertThat(policy.path("pricingPolicy").path("tokenEstimateOutputTokens").asInt()).isEqualTo(500);
        assertThat(policy.path("staticParams").has("maxCreditCost")).isFalse();
        assertThat(policy.path("staticParams").has("maxProviderCostCny")).isFalse();
        assertThat(policy.path("staticParams").has("providerCostMode")).isFalse();
    }

    @Test
    void publishRejectsComicComposeWhenItReferencesModel() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_local_provider_cost_none_with_model");
        long modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE enabled = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                Long.class
        );
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"compose","data":{"nodeDefType":"subtitle","title":"Compose",
                    "parameters":{"handlerKey":"comic.compose","modelConfigId":%d}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """.formatted(modelId);
        String edges = """
                [
                  {"id":"e1","source":"start","target":"compose"},
                  {"id":"e2","source":"compose","target":"output"}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("cannot reference a model configuration")));
    }

    @Test
    void publishRequiresValidDagAndSaveKeepsPublishedStatus() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_publish_tool");

        // 1. 保存非法 DAG（缺 video_output）：保存成功但校验不通过、发布被拒绝
        saveWorkflow(adminToken, toolId, INVALID_NODES, "[]");

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/validate", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("video_output")));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        // 2. 保存合法 DAG 并发布
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES);

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/validate", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 3. 再次保存内容（不带 status）：版本 +1 且发布状态保持，不会被打回 DRAFT
        saveWorkflow(adminToken, toolId, VALID_NODES, VALID_EDGES);

        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        Long publishedVersionId = jdbcTemplate.queryForObject(
                "SELECT published_version_id FROM tool_workflows WHERE tool_id = ?", Long.class, toolId);

        // 4. 兼容入口复用工具下线，但保留正式版本
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/unpublish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.executionEnabled").value(false))
                .andExpect(jsonPath("$.data.publishedVersionId").value(publishedVersionId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tools WHERE id = ?", String.class, toolId)).isEqualTo("OFFLINE");

        // 5. 历史版本可列出
        mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow/versions", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").exists());
    }

    @Test
    void publishRejectsWorkerWithInactiveModelConfig() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_inactive_model_tool");
        String nodes = """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                    "parameters":{"modelConfigId":999999,"maxCreditCost":100,"maxProviderCostCny":1.00}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """;
        String edges = """
                [
                  {"id":"e1","source":"start","target":"worker"},
                  {"id":"e2","source":"worker","target":"output"}
                ]
                """;
        saveWorkflow(adminToken, toolId, nodes, edges, 0L)
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("inactive model configuration")));
    }

    @Test
    void publishRejectsDisabledModelConfig() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_disabled_tts_model");
        long modelId = createWorkflowModel(
                "wf_disabled_tts_model_config",
                "minimax_speech",
                "TEXT_TO_SPEECH",
                false
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("tts_model", "shot-audio", modelId),
                singleModelEdges("shot-audio"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("模型已停用")));
    }

    @Test
    void publishRejectsModelWithoutNodeCapability() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_tts_model_capability_mismatch");
        long modelId = createWorkflowModel(
                "wf_tts_model_capability_mismatch_config",
                "minimax_speech",
                "TEXT_GENERATION",
                true
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("tts_model", "shot-audio", modelId),
                singleModelEdges("shot-audio"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("TEXT_TO_SPEECH")));
    }

    @Test
    void publishRejectsProviderWithoutNodeCapability() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_tts_provider_capability_mismatch");
        long modelId = createWorkflowModel(
                "wf_tts_provider_capability_mismatch_config",
                "minimax",
                "TEXT_TO_SPEECH",
                true
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("tts_model", "shot-audio", modelId),
                singleModelEdges("shot-audio"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("供应商 minimax 未声明能力 TEXT_TO_SPEECH")));
    }

    @Test
    void publishRejectsProviderWhoseWorkerIsNotReady() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_video_provider_worker_not_ready");
        long modelId = createWorkflowModel(
                "wf_video_provider_worker_not_ready_config",
                "vidu_video",
                "VIDEO_GENERATION",
                true
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("video_model", "shot-video", modelId),
                singleModelEdges("shot-video"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("Worker 执行器尚未就绪")));
    }

    @Test
    void publishAcceptsMusicSfxModelWithMusicCapability() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_music_sfx_capability");
        long modelId = createWorkflowModel(
                "wf_music_sfx_capability_config",
                "suno_music",
                "MUSIC_GENERATION",
                true
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("music_sfx", "background-music", modelId),
                singleModelEdges("background-music"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.publishedVersionId").isNumber());
    }

    @Test
    void publishUsesProviderCapabilityWhenLegacyModelCapabilitiesAreEmpty() throws Exception {
        String adminToken = login();
        Long toolId = createTool(adminToken, "wf_tts_provider_capability_fallback");
        long modelId = createWorkflowModel(
                "wf_tts_provider_capability_fallback_config",
                "dashscope_qwen_tts",
                "TEXT_TO_SPEECH",
                true
        );
        jdbcTemplate.update(
                "UPDATE agent_model_configs SET capabilities = '[]', billing_unit = 'PER_CHARACTER', unit_price = 0.01 WHERE id = ?",
                modelId
        );
        saveWorkflow(adminToken, toolId, singleModelNodes("tts_model", "shot-audio", modelId),
                singleModelEdges("shot-audio"), 0L).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/workflow/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.publishedVersionId").isNumber());
    }

    private void saveWorkflow(String adminToken, Long toolId, String nodesJson, String edgesJson) throws Exception {
        long revision = currentDraftRevision(adminToken, toolId);
        saveWorkflow(adminToken, toolId, nodesJson, edgesJson, revision)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    private org.springframework.test.web.servlet.ResultActions saveWorkflow(String adminToken,
                                                                             Long toolId,
                                                                             String nodesJson,
                                                                             String edgesJson,
                                                                             long expectedDraftRevision) throws Exception {
        String body = """
                {
                  "workflowName": "default",
                  "nodesJson": %s,
                  "edgesJson": %s,
                  "expectedDraftRevision": %d
                }
                """.formatted(jsonString(nodesJson), jsonString(edgesJson), expectedDraftRevision);
        return mockMvc.perform(put("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long currentDraftRevision(String adminToken, Long toolId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/v1/tools/{toolId}/workflow", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = responseData(result);
        return data.isMissingNode() || data.isNull() ? 0L : data.path("draftRevision").asLong(0L);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode billingPolicy(long versionId) throws Exception {
        return objectMapper.readTree(versionMapper.selectById(versionId).getBillingPolicyJson());
    }

    private static String jsonString(String raw) {
        return "\"" + raw.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\"";
    }

    private Long createTool(String adminToken, String toolCode) throws Exception {
        String body = """
                {
                  "toolCode": "%s",
                  "toolName": "Workflow Tool",
                  "categoryId": 1,
                  "description": "workflow publish test",
                  "estimatedCreditCost": 1
                }
                """.formatted(toolCode);
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createTokenPricedModel(String configCode) {
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                  display_name, config_code, provider, model_name, base_url, timeout_seconds,
                  input_token_price_per_1m, output_token_price_per_1m,
                  billing_unit, unit_price, capabilities,
                  enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, ?, 'mock', 'token-priced-model', 'https://example.invalid/v1', 60,
                          2.0, 4.0, 'TOKEN_PER_M', 0, '["TEXT_GENERATION"]', 1, 1, 0, 0)
                """, "Workflow token priced model", configCode);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code = ?",
                Long.class,
                configCode
        );
    }

    private long createWorkflowModel(String configCode,
                                     String provider,
                                     String capability,
                                     boolean enabled) {
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                  display_name, config_code, provider, model_name, base_url, timeout_seconds,
                  billing_unit, unit_price, capabilities,
                  enabled, agent_enabled, is_default, is_deleted
                ) VALUES (?, ?, ?, 'workflow-test-model', 'https://example.invalid', 60,
                          'PER_CALL', 0.02, ?, ?, 0, 0, 0)
                """, "Workflow model " + configCode, configCode, provider,
                "[\"" + capability + "\"]", enabled ? 1 : 0);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code = ?",
                Long.class,
                configCode
        );
    }

    private String singleModelNodes(String nodeType, String nodeId, long modelId) {
        return """
                [
                  {"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                  {"id":"%s","data":{"nodeDefType":"%s","title":"Model",
                    "parameters":{"modelConfigId":%d}}},
                  {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}
                ]
                """.formatted(nodeId, nodeType, modelId);
    }

    private String singleModelEdges(String nodeId) {
        return """
                [
                  {"id":"e1","source":"start","target":"%s"},
                  {"id":"e2","source":"%s","target":"output"}
                ]
                """.formatted(nodeId, nodeId);
    }

    private Long createTextOnlyModelConfig(String adminToken) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Workflow Text Only Model",
                                  "configCode": "workflow_text_only_publish_guard",
                                  "provider": "minimax",
                                  "modelName": "MiniMax-M2.7",
                                  "baseUrl": "https://api.minimaxi.com/v1",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "TOKEN_PER_M",
                                  "unitPrice": 0,
                                  "capabilities": ["TEXT_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "isDefault": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private String login() throws Exception {
        var result = mockMvc.perform(post("/api/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "admin",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return AuthTestTokens.adminJwtFrom(result);
    }
}
