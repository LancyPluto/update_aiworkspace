package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRouteDebugResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_agent_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.agent.max-active-runs-per-user=100",
        "app.agent.max-messages-per-minute=100"
})
class AdminAgentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @MockBean
    private InternalRequestSignatureVerifier internalRequestSignatureVerifier;

    @MockBean
    private AgentRateLimitService agentRateLimitService;

    @MockBean
    private AgentServiceClient agentServiceClient;

    @Test
    void adminCanObserveAgentRunsEventsToolCallsAndCancelActiveRun() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "xiaohongshu_copywriting");
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long sessionId = createSession(userToken, "Admin Observability");
        Long runId = sendMessage(userToken, sessionId, "Recommend a writing tool.");
        Long toolCallId = createToolCall(runId);
        bindToolCallTask(toolCallId, 9101L);

        mockMvc.perform(get("/api/admin/v1/agent/runs")
                        .param("status", "RUNNING")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(runId.intValue()))
                .andExpect(jsonPath("$.data.list[0].userId").value(2))
                .andExpect(jsonPath("$.data.list[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.list[0].eventCount").value(2))
                .andExpect(jsonPath("$.data.list[0].toolCallCount").value(1));

        mockMvc.perform(get("/api/admin/v1/agent/runs")
                        .param("taskId", "9101")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(runId.intValue()));

        mockMvc.perform(get("/api/admin/v1/agent/runs/stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRuns").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.activeRuns").value(1))
                .andExpect(jsonPath("$.data.toolCalls").value(1));

        mockMvc.perform(get("/api/admin/v1/agent/runs/{runId}", runId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.id").value(runId.intValue()))
                .andExpect(jsonPath("$.data.events[0].eventType").value("run.started"))
                .andExpect(jsonPath("$.data.toolCalls[0].id").value(toolCallId.intValue()))
                .andExpect(jsonPath("$.data.toolCalls[0].taskId").value(9101))
                .andExpect(jsonPath("$.data.toolCalls[0].toolCode").value("xiaohongshu_copywriting"));

        mockMvc.perform(post("/api/admin/v1/agent/runs/{runId}/cancel", runId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void userTokenCannotAccessAdminAgentApi() throws Exception {
        mockExternalAuthDependencies();
        String userToken = login("/api/v1/auth/login", "user1");

        mockMvc.perform(get("/api/admin/v1/agent/runs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
    }

    @Test
    void adminCanConfigureAgentRuntimeSettingsAndManageMemoryCandidates() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        String userToken = login("/api/v1/auth/login", "user1");
        Long sessionId = createSession(userToken, "Runtime Settings");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "agent.runtime.max_model_calls": "9",
                                    "agent.runtime.max_tool_calls": "4",
                                    "agent.runtime.max_history_messages": "7",
                                    "agent.memory.retrieval_limit": "3",
                                    "agent.memory.consolidation_llm_enabled": "false",
                                    "agent.memory.consolidation_token_threshold": "1234",
                                    "agent.memory.consolidation_recent_tool_threshold": "2",
                                    "agent.memory.consolidation_max_context_messages": "18",
                                    "agent.memory.consolidation_prompt": "只输出 JSON"
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        Long runId = sendMessage(userToken, sessionId, "Check runtime settings.");
        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimeSettings.maxModelCalls").value(9))
                .andExpect(jsonPath("$.data.runtimeSettings.maxToolCalls").value(4))
                .andExpect(jsonPath("$.data.runtimeSettings.maxHistoryMessages").value(7))
                .andExpect(jsonPath("$.data.memorySettings.retrievalLimit").value(3))
                .andExpect(jsonPath("$.data.memorySettings.consolidationLlmEnabled").value(false))
                .andExpect(jsonPath("$.data.memorySettings.consolidationTokenThreshold").value(1234))
                .andExpect(jsonPath("$.data.memorySettings.consolidationRecentToolThreshold").value(2))
                .andExpect(jsonPath("$.data.memorySettings.consolidationMaxContextMessages").value(18))
                .andExpect(jsonPath("$.data.memorySettings.consolidationPrompt").value("只输出 JSON"));
        mockMvc.perform(post("/api/admin/v1/agent/runs/{runId}/cancel", runId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String candidateBody = """
                {
                  "userId": 2,
                  "action": "candidate",
                  "memoryType": "project_knowledge",
                  "title": "Admin candidate",
                  "content": "Use concise Chinese copy for enterprise users.",
                  "importance": 6,
                  "confidence": 0.66
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/workspaces/{workspaceId}/memory/candidates", 1L), "POST",
                        "/api/internal/v1/agent/workspaces/1/memory/candidates", candidateBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(candidateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memoryType").value("workspace_fact"))
                .andExpect(jsonPath("$.data.status").value("CANDIDATE"));

        String listResponse = mockMvc.perform(get("/api/admin/v1/agent/memory")
                        .param("status", "CANDIDATE")
                        .param("keyword", "Admin")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long memoryId = Long.parseLong(listResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/agent/memory/{id}/approve", memoryId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType": "preference",
                                  "title": "Admin updated",
                                  "content": "Prefer concise Chinese copy.",
                                  "importance": 8,
                                  "confidence": 0.9,
                                  "pinned": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memoryType").value("preference"))
                .andExpect(jsonPath("$.data.pinned").value(true));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/v1/agent/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanDisableAgentToolAccessAndRuntimeContextFiltersIt() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        String toolCode = "agent_access_toggle";
        Long toolId = createTool(adminToken, toolCode);
        publishTool(adminToken, toolId);

        String listResponse = mockMvc.perform(get("/api/admin/v1/agent/tools")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listResponse).contains("\"toolCode\":\"agent_access_toggle\"");
        assertThat(listResponse).contains("\"agentEnabled\":true");
        assertThat(listResponse).contains("\"executionMode\":\"DIRECT\"");

        String userToken = login("/api/v1/auth/login", "user1");
        Long sessionId = createSession(userToken, "Agent Tool Access");
        Long enabledRunId = sendMessage(userToken, sessionId, "Find a tool.");
        String enabledContext = mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", enabledRunId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(enabledRunId), ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(enabledContext).contains("\"toolCode\":\"agent_access_toggle\"");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/tools/{toolCode}", toolCode)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value(toolCode))
                .andExpect(jsonPath("$.data.agentEnabled").value(false));

        Mockito.clearInvocations(agentServiceClient);
        Long disabledRunId = sendMessage(userToken, sessionId, "Find a tool again.");
        String disabledContext = mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", disabledRunId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(disabledRunId), ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(disabledContext).doesNotContain("\"toolCode\":\"agent_access_toggle\"");

        String body = """
                {
                  "toolCode": "agent_access_toggle",
                  "argumentsJson": "{}"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/tool-calls", disabledRunId), "POST",
                        "/api/internal/v1/agent/runs/%d/tool-calls".formatted(disabledRunId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_TOOL_NOT_AVAILABLE"));
    }

    @Test
    void adminAgentModelToggleControlsUserSelectableModelsWithKlingCredentials() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        String userToken = login("/api/v1/auth/login", "user1");

        String createBody = """
                {
                  "displayName": "Kling Agent Selectable",
                  "configCode": "kling-agent-selectable",
                  "provider": "kling_video",
                  "modelName": "kling-v3",
                  "baseUrl": "https://api-beijing.klingai.com",
                  "extraAuthJson": "{\\"accessKey\\":\\"test-access-key\\",\\"secretKey\\":\\"test-secret-key\\"}",
                  "timeoutSeconds": 60,
                  "billingUnit": "PER_CALL",
                  "unitPrice": 3,
                  "enabled": true,
                  "agentEnabled": true,
                  "isDefault": false
                }
                """;
        String created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentEnabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1");

        String enabledList = mockMvc.perform(get("/api/v1/agent/model-configs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(enabledList).contains("\"configCode\":\"kling-agent-selectable\"");

        String disableBody = createBody.replace("\"agentEnabled\": true", "\"agentEnabled\": false");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disableBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentEnabled").value(false));

        String disabledList = mockMvc.perform(get("/api/v1/agent/model-configs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(disabledList).doesNotContain("\"configCode\":\"kling-agent-selectable\"");
    }

    @Test
    void adminCanBulkUpdateAgentToolAccessAndDebugRoute() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long imageToolId = createTool(adminToken, "debug_image_tool");
        Long videoToolId = createTool(adminToken, "debug_video_tool");
        publishTool(adminToken, imageToolId);
        publishTool(adminToken, videoToolId);
        Mockito.when(agentServiceClient.debugRoute(any())).thenReturn(new AdminAgentRouteDebugResponse(
                "tool_use",
                0.95,
                "debug_image_tool",
                List.of("debug_image_tool"),
                null,
                "llm_router",
                "image request",
                "image",
                1,
                List.of(new AdminAgentRouteDebugResponse.RouteDebugToolResponse("debug_image_tool", "debug_image_tool", true)),
                List.of(),
                "ready",
                true,
                1,
                5,
                12000,
                List.of(),
                true,
                true,
                true
        ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/tools/bulk-access")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCodes": ["debug_video_tool"],
                                  "agentEnabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedTools[0].toolCode").value("debug_video_tool"))
                .andExpect(jsonPath("$.data.updatedTools[0].agentEnabled").value(false))
                .andExpect(jsonPath("$.data.failedToolCodes.length()").value(0));

        mockMvc.perform(post("/api/admin/v1/agent/tools/route-debug")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"生成一张老照片\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedToolCode").value("debug_image_tool"))
                .andExpect(jsonPath("$.data.visibleTools[0].toolCode").value("debug_image_tool"))
                .andExpect(jsonPath("$.data.filteredTools[?(@.toolCode=='debug_video_tool')].reason").isNotEmpty());
    }

    @Test
    void adminCanSaveModelConfigAndInternalApiReturnsActiveConfig() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        String body = """
                {
                  "provider": "minimax",
                  "modelName": "MiniMax-M2.7",
                  "baseUrl": "https://api.minimax.io/v1",
                  "apiKey": "secret-key",
                  "minimaxGroupId": "group-123",
                  "timeoutSeconds": 45,
                  "enabled": true
                }
                """;
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("minimax"))
                .andExpect(jsonPath("$.data.modelName").value("MiniMax-M2.7"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("se***ey"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        mockMvc.perform(get("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("minimax"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("se***ey"));

        mockMvc.perform(signed(get("/api/internal/v1/agent/model-config"), "GET",
                        "/api/internal/v1/agent/model-config", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("minimax"))
                .andExpect(jsonPath("$.data.apiKey").value("secret-key"))
                .andExpect(jsonPath("$.data.minimaxGroupId").value("group-123"));
    }

    @Test
    void sunoVendorAccountTestUsesSunoProviderAcceptOnlyStrategy() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "suno_music",
                                  "accountName": "Suno test",
                                  "baseUrl": "https://api.sunoapi.org",
                                  "apiKey": "suno-secret",
                                  "balanceQueryMode": "MANUAL",
                                  "balanceAmount": 50,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("suno_music"))
                .andExpect(jsonPath("$.data.modelName").value("V5"))
                .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));

        Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(argThat(request ->
                request != null && "openai_compatible".equals(request.provider())));
    }

    @Test
    void openaiGatewayVendorAccountTestUsesMediaGatewayProbeInsteadOfAgentChat() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "openai_gateway",
                                  "accountName": "oFox gateway test",
                                  "baseUrl": "https://api.ofox.ai/v1",
                                  "apiKey": "sk-test-ofox-key",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("openai_images_gateway"))
                .andExpect(jsonPath("$.data.modelName").value("openai/gpt-image-2"))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("unsupported model provider"))));

        Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(argThat(request ->
                request != null && "openai_images_gateway".equals(request.provider())));
    }

    @Test
    void vendorAccountTestDoesNotDependOnLinkedOpenAiCompatibleModelForMoonshot() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        String accountResponse = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "moonshot",
                                  "accountName": "Moonshot test",
                                  "baseUrl": "https://api.moonshot.cn/v1",
                                  "apiKey": "sk-moonshot-test",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(accountResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorAccountId": %d,
                                  "displayName": "Kimi linked test",
                                  "configCode": "kimi_linked_test",
                                  "provider": "openai_compatible",
                                  "modelName": "kimi-k2.6",
                                  "baseUrl": "https://api.moonshot.cn/v1",
                                  "timeoutSeconds": 60,
                                  "enabled": false,
                                  "agentEnabled": false,
                                  "isDefault": false,
                                  "capabilities": ["TEXT_GENERATION"]
                                }
                                """.formatted(accountId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("openai_compatible"))
                .andExpect(jsonPath("$.data.modelName").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));

        Mockito.verify(agentServiceClient).testModelConfig(argThat(request ->
                request != null
                        && "openai_compatible".equals(request.provider())
                        && "gpt-4o-mini".equals(request.modelName())
                        && "https://api.moonshot.cn/v1".equals(request.baseUrl())
                        && "sk-moonshot-test".equals(request.apiKey())));
    }

    @Test
    void happyHorseCanSwitchBetweenBailianAccountsButNotToAnotherVendor() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long firstBailianAccount = createVendorAccount(
                adminToken, "qwen", "HappyHorse switch account A", "https://dashscope.aliyuncs.com"
        );
        Long secondBailianAccount = createVendorAccount(
                adminToken, "qwen", "HappyHorse switch account B", "https://dashscope.aliyuncs.com"
        );
        Long klingAccount = createVendorAccount(
                adminToken, "kling", "HappyHorse cross vendor account", "https://api-beijing.klingai.com"
        );

        String createBody = happyHorseModelBody(firstBailianAccount);
        String created = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("bailian_happyhorse"))
                .andExpect(jsonPath("$.data.capabilities[0]").value("VIDEO_GENERATION"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long modelId = Long.parseLong(created.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/v1/agent/model-config/{id}", modelId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyHorseModelBody(secondBailianAccount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorAccountId").value(secondBailianAccount.intValue()))
                .andExpect(jsonPath("$.data.provider").value("bailian_happyhorse"))
                .andExpect(jsonPath("$.data.capabilities[0]").value("VIDEO_GENERATION"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/v1/agent/model-config/{id}", modelId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyHorseModelBody(klingAccount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("does not belong")));
    }

    @Test
    void emptyApiKeyUpdateKeepsExistingModelSecret() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "first-model",
                                  "baseUrl": "https://first.example/v1",
                                  "apiKey": "first-secret",
                                  "timeoutSeconds": 30,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "second-model",
                                  "baseUrl": "https://second.example/v1",
                                  "apiKey": "",
                                  "timeoutSeconds": 60,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("second-model"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("fi***et"));

        mockMvc.perform(signed(get("/api/internal/v1/agent/model-config"), "GET",
                        "/api/internal/v1/agent/model-config", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").value("first-secret"))
                .andExpect(jsonPath("$.data.modelName").value("second-model"));
    }

    @Test
    void adminCanConfigureGatewayTimeoutsWithoutOverwritingExtraAuthSecrets() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "openai/gpt-image-2",
                                  "baseUrl": "https://api.ofox.ai/v1",
                                  "apiKey": "image-secret",
                                  "extraAuthJson": "{\\"proxyUrl\\":\\"http://127.0.0.1:7890\\"}",
                                  "timeoutSeconds": 300,
                                  "connectTimeoutSeconds": 30,
                                  "readTimeoutSeconds": 600,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectTimeoutSeconds").value(30))
                .andExpect(jsonPath("$.data.readTimeoutSeconds").value(600));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "openai/gpt-image-2",
                                  "baseUrl": "https://api.ofox.ai/v1",
                                  "apiKey": "",
                                  "extraAuthJson": "",
                                  "timeoutSeconds": 300,
                                  "connectTimeoutSeconds": 45,
                                  "readTimeoutSeconds": 900,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectTimeoutSeconds").value(45))
                .andExpect(jsonPath("$.data.readTimeoutSeconds").value(900));

        mockMvc.perform(signed(get("/api/internal/v1/agent/model-config"), "GET",
                        "/api/internal/v1/agent/model-config", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").value("image-secret"))
                .andExpect(jsonPath("$.data.extraAuthJson").value(org.hamcrest.Matchers.containsString("\"proxyUrl\":\"http://127.0.0.1:7890\"")))
                .andExpect(jsonPath("$.data.extraAuthJson").value(org.hamcrest.Matchers.containsString("\"connectTimeoutSeconds\":45")))
                .andExpect(jsonPath("$.data.extraAuthJson").value(org.hamcrest.Matchers.containsString("\"readTimeoutSeconds\":900")));
    }

    @Test
    void adminCanTestModelConfigBeforeSaving() throws Exception {
        mockExternalAuthDependencies();
        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenReturn(new com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse(
                        true,
                        "mock",
                        "mock",
                        12L,
                        "ok",
                        "pong"
                ));
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "mock",
                                  "modelName": "mock",
                                  "timeoutSeconds": 30,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("mock"))
                .andExpect(jsonPath("$.data.sample").value("pong"));

        Mockito.verify(agentServiceClient).testModelConfig(any());
    }

    @Test
    void emptyApiKeyTestReusesSavedModelSecret() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "saved-model",
                                  "baseUrl": "https://saved.example/v1",
                                  "apiKey": "saved-secret",
                                  "timeoutSeconds": 30,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk());

        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenAnswer(invocation -> {
                    var forwarded = invocation.getArgument(0, com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest.class);
                    assertThat(forwarded.apiKey()).isEqualTo("saved-secret");
                    assertThat(forwarded.modelName()).isEqualTo("saved-model");
                    return new AgentModelConfigTestResponse(true, forwarded.provider(), forwarded.modelName(), 9L, "ok", "pong");
                });

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "saved-model",
                                  "baseUrl": "https://saved.example/v1",
                                  "apiKey": "",
                                  "timeoutSeconds": 30,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.modelName").value("saved-model"));
    }

    @Test
    void modelConfigTestInheritsVendorAccountCredentials() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        String accountResponse = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "openai",
                                  "accountName": "Relay account",
                                  "baseUrl": "https://relay.example/v1",
                                  "apiKey": "relay-secret",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long accountId = Long.parseLong(accountResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));

        jdbcTemplate.update("UPDATE model_vendor_accounts SET health_status='OK' WHERE id=?", accountId);

        String configResponse = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorAccountId": %d,
                                  "displayName": "Relay GPT",
                                  "configCode": "relay_gpt",
                                  "provider": "openai_compatible",
                                  "modelName": "gpt-relay",
                                  "baseUrl": "",
                                  "apiKey": "",
                                  "timeoutSeconds": 60,
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "capabilities": ["TEXT_GENERATION"]
                                }
                                """.formatted(accountId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long configId = Long.parseLong(configResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        jdbcTemplate.update("""
                INSERT INTO account_model_route_state(
                    vendor_account_id, model_config_id, circuit_status,
                    consecutive_failures, cooldown_until
                ) VALUES (?, ?, 'OPEN', 2, DATEADD('MINUTE', 5, CURRENT_TIMESTAMP))
                """, accountId, configId);

        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenAnswer(invocation -> {
                    var forwarded = invocation.getArgument(0, com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest.class);
                    assertThat(forwarded.vendorAccountId()).isEqualTo(accountId);
                    assertThat(forwarded.baseUrl()).isEqualTo("https://relay.example/v1");
                    assertThat(forwarded.apiKey()).isEqualTo("relay-secret");
                    return new AgentModelConfigTestResponse(true, forwarded.provider(), forwarded.modelName(), 9L, "ok", "pong");
                });

        mockMvc.perform(post("/api/admin/v1/agent/model-config/{id}/test", configId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.modelName").value("gpt-relay"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT circuit_status FROM account_model_route_state WHERE model_config_id = ?",
                String.class,
                configId
        )).isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM account_model_route_state WHERE model_config_id = ?",
                Integer.class,
                configId
        )).isZero();
    }

    @Test
    void volcengineRootBaseUrlIsNormalizedBeforeAgentServiceTest() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenAnswer(invocation -> {
                    var forwarded = invocation.getArgument(0, com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest.class);
                    assertThat(forwarded.baseUrl()).isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
                    return new AgentModelConfigTestResponse(true, forwarded.provider(), forwarded.modelName(), 9L, "ok", "pong");
                });

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "openai_compatible",
                                  "modelName": "doubao-seed-2.0-lite",
                                  "baseUrl": "https://ark.cn-beijing.volces.com",
                                  "apiKey": "ark-test",
                                  "timeoutSeconds": 30,
                                  "enabled": true,
                                  "capabilities": ["TEXT_GENERATION"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.modelName").value("doubao-seed-2.0-lite"));
    }

    @Test
    void siliconflowImageModelConfigTestDoesNotCallAgentService() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("siliconflow_images"))
                .andExpect(jsonPath("$.data.modelName").value("Tongyi-MAI/Z-Image-Turbo"));

        Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(argThat(request ->
                "siliconflow_images".equals(request.provider())));
    }

    @Test
    void openAiImageModelConfigTestFailsWhenGenerationCapabilityIsDisabled() throws Exception {
        mockExternalAuthDependencies();
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"gpt-image-2\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/images/generations", exchange -> {
            byte[] body = """
                    {"error":{"message":"Image generation is not enabled for this group","type":"permission_error"}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            mockMvc.perform(post("/api/admin/v1/agent/model-config/test")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "provider": "ofox_openai_images",
                                      "modelName": "gpt-image-2",
                                      "baseUrl": "%s",
                                      "apiKey": "test-key",
                                      "timeoutSeconds": 30,
                                      "enabled": true,
                                      "capabilities": ["IMAGE_GENERATION"]
                                    }
                                    """.formatted(baseUrl)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(false))
                    .andExpect(jsonPath("$.data.message").value(containsString("Image generation is not enabled")));

            Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(any());
        } finally {
            server.stop(0);
        }
    }

    private void mockExternalAuthDependencies() {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenReturn(new AgentModelConfigTestResponse(true, "mock", "mock", 1L, "ok", "pong"));
        Mockito.when(internalRequestSignatureVerifier.verify(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(byte[].class)
                ))
                .thenReturn(true);
    }

    private String login(String path, String account) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long createSession(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createVendorAccount(String adminToken, String vendorCode, String accountName, String baseUrl) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/model-vendor-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vendorCode": "%s",
                                  "accountName": "%s",
                                  "baseUrl": "%s",
                                  "apiKey": "test-account-secret",
                                  "balanceQueryMode": "MANUAL",
                                  "enabled": true
                                }
                                """.formatted(vendorCode, accountName, baseUrl)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private String happyHorseModelBody(Long vendorAccountId) {
        return """
                {
                  "vendorAccountId": %d,
                  "displayName": "HappyHorse account switch test",
                  "configCode": "happyhorse_account_switch_test",
                  "provider": "bailian_happyhorse",
                  "modelName": "happyhorse-1.1-t2v",
                  "baseUrl": "",
                  "timeoutSeconds": 60,
                  "billingUnit": "PER_SECOND",
                  "unitPrice": 0.9,
                  "enabled": false,
                  "agentEnabled": false,
                  "isDefault": false,
                  "capabilities": ["VIDEO_GENERATION"]
                }
                """.formatted(vendorAccountId);
    }

    private Long createTool(String adminToken, String toolCode) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": 1,
                                  "description": "Agent admin test tool",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 1
                                }
                                """.formatted(toolCode, toolCode)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void publishTool(String adminToken, Long toolId) throws Exception {
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private Long sendMessage(String token, Long sessionId, String content) throws Exception {
        String response = mockMvc.perform(post("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "%s",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(content, java.util.UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Mockito.verify(agentServiceClient).executeRun(anyLong());
        return Long.parseLong(response.replaceAll("(?s).*\\\"runId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createToolCall(Long runId) throws Exception {
        String body = """
                {
                  "toolCode": "xiaohongshu_copywriting",
                  "argumentsJson": "{\\"topic\\":\\"coffee\\"}"
                }
                """;
        String response = mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/tool-calls", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/tool-calls".formatted(runId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void bindToolCallTask(Long toolCallId, Long taskId) throws Exception {
        String body = """
                {
                  "taskId": %d
                }
                """.formatted(taskId);
        mockMvc.perform(signed(post("/api/internal/v1/agent/tool-calls/{toolCallId}/task", toolCallId), "POST",
                        "/api/internal/v1/agent/tool-calls/%d/task".formatted(toolCallId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
