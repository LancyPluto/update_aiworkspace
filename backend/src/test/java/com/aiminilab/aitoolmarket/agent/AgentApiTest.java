package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseChunk;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseResult;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.mapper.AgentMessageMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileChunkMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.agent.max-active-runs-per-user=100",
        "app.agent.max-messages-per-minute=100"
})
class AgentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Autowired
    private CreditService creditService;

    @Autowired
    private AgentRunMapper agentRunMapper;

    @Autowired
    private AgentRunEventMapper agentRunEventMapper;

    @Autowired
    private AgentFileChunkMapper agentFileChunkMapper;

    @Autowired
    private AgentMessageMapper agentMessageMapper;

    @Autowired
    private AgentToolCallMapper agentToolCallMapper;

    @Autowired
    private AgentToolPreferenceMapper agentToolPreferenceMapper;

    @Test
    void userCanCreateSessionSendMessageAndListEvents() throws Exception {
        mockExternalAuthDependencies();
        register("agent_basic_user");
        String token = login("agent_basic_user");
        Long sessionId = createSession(token, "Agent Test");
        SendMessageResult sendResult = sendMessage(token, sessionId, "Please write a tool recommendation.");
        Long runId = sendResult.runId();

        Mockito.verify(agentServiceClient).executeRun(runId);
        assertThat(sendResult.sessionId()).isEqualTo(sessionId);
        assertThat(sendResult.messageId()).isNotNull();
        assertThat(sendResult.runStatus()).isEqualTo("RUNNING");
        assertThat(agentMessageMapper.findUserMessageByRunId(runId)).isNotNull();

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Agent Test"));

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].role").value("USER"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].eventType").value("run.started"));
    }

    @Test
    void userCanReplayEventsAfterEventIdAndResumeWaitingRunOnConfirmation() throws Exception {
        mockExternalAuthDependencies();
        register("agent_confirm_resume_user");
        LoginResult login = loginWithUser("agent_confirm_resume_user");
        String token = login.token();
        ensureOnlineTool("xiaohongshu_copywriting");
        Long sessionId = createSession(token, "Confirm Resume");
        Long runId = sendMessage(token, sessionId, "Please wait for tool confirmation.").runId();
        String confirmationEventBody = """
                {
                  "eventType": "tool.confirmation_required",
                  "eventText": "xiaohongshu_copywriting",
                  "eventJson": {
                    "toolCode": "xiaohongshu_copywriting"
                  }
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/events", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/events".formatted(runId), confirmationEventBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationEventBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventType").value("tool.confirmation_required"));
        assertThat(agentRunMapper.findById(runId).orElseThrow().getStatus()).isEqualTo("WAITING_USER_CONFIRMATION");

        mockMvc.perform(post("/api/v1/agent/runs/{runId}/tool-confirmations", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "approved": true,
                                  "autoCallEnabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post("/api/v1/agent/runs/{runId}/tool-confirmations", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "approved": true,
                                  "autoCallEnabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        Mockito.verify(agentServiceClient).confirmTool(runId, "xiaohongshu_copywriting");
        Mockito.verify(agentServiceClient, Mockito.times(1)).confirmTool(runId, "xiaohongshu_copywriting");
        assertThat(agentRunMapper.findById(runId).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(agentToolPreferenceMapper.findByUserIdAndToolCode(login.userId(), "xiaohongshu_copywriting"))
                .isNotNull()
                .extracting("autoCallEnabled")
                .isEqualTo(false);

        String startedResponse = mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/tool-calls", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/tool-calls".formatted(runId), """
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "argumentsJson": {
                                    "topic": "launch"
                                  }
                                }
                                """)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "argumentsJson": {
                                    "topic": "launch"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long toolCallId = objectMapper.readTree(startedResponse).path("data").path("id").asLong();
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/tool-calls", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/tool-calls".formatted(runId), """
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "argumentsJson": {
                                    "topic": "launch"
                                  }
                                }
                                """)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "xiaohongshu_copywriting",
                                  "argumentsJson": {
                                    "topic": "launch"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) toolCallId))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        String completeToolBody = """
                {
                  "resultJson": {
                    "postId": "draft-001"
                  }
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/tool-calls/{toolCallId}/complete", toolCallId), "POST",
                        "/api/internal/v1/agent/tool-calls/%d/complete".formatted(toolCallId), completeToolBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeToolBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mockMvc.perform(signed(post("/api/internal/v1/agent/tool-calls/{toolCallId}/complete", toolCallId), "POST",
                        "/api/internal/v1/agent/tool-calls/%d/complete".formatted(toolCallId), completeToolBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeToolBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        String failToolBody = """
                {
                  "errorCode": "RETRY_AFTER_SUCCESS",
                  "errorMessage": "Should remain successful"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/tool-calls/{toolCallId}/fail", toolCallId), "POST",
                        "/api/internal/v1/agent/tool-calls/%d/fail".formatted(toolCallId), failToolBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failToolBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        var calls = agentToolCallMapper.findByRunId(runId);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(calls.get(0).getResultJson()).contains("draft-001");

        String eventsResponse = mockMvc.perform(get("/api/v1/agent/runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("list");
        assertThat(events.size()).isEqualTo(5);
        long firstEventId = events.get(0).path("id").asLong();
        assertThat(events.get(0).path("eventType").asText()).isEqualTo("run.started");
        assertThat(events.get(1).path("eventType").asText()).isEqualTo("tool.confirmation_required");
        assertThat(events.get(2).path("eventType").asText()).isEqualTo("tool.confirmed");
        assertThat(events.get(3).path("eventType").asText()).isEqualTo("tool.started");
        assertThat(events.get(4).path("eventType").asText()).isEqualTo("tool.finished");

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + token)
                        .param("afterEventId", String.valueOf(firstEventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(4))
                .andExpect(jsonPath("$.data.list[0].eventType").value("tool.confirmation_required"))
                .andExpect(jsonPath("$.data.list[1].eventType").value("tool.confirmed"))
                .andExpect(jsonPath("$.data.list[2].eventType").value("tool.started"))
                .andExpect(jsonPath("$.data.list[3].eventType").value("tool.finished"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events/stream", runId)
                        .header("Authorization", "Bearer " + token)
                        .param("afterEventId", String.valueOf(firstEventId)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void sessionCreationAssignsWorkspace() throws Exception {
        mockExternalAuthDependencies();
        register("agent_workspace_session_user");
        String token = login("agent_workspace_session_user");
        Long defaultWorkspaceId = defaultWorkspaceId(token);

        mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Default Workspace Session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(defaultWorkspaceId));

        mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Explicit Workspace Session",
                                  "workspaceId": %d
                                }
                                """.formatted(defaultWorkspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(defaultWorkspaceId));
    }

    @Test
    void userCannotCreateSessionInAnotherUsersWorkspace() throws Exception {
        mockExternalAuthDependencies();
        register("agent_workspace_owner");
        String ownerToken = login("agent_workspace_owner");
        Long ownerWorkspaceId = defaultWorkspaceId(ownerToken);
        register("agent_workspace_other");
        String otherToken = login("agent_workspace_other");

        mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Cross Workspace Session",
                                  "workspaceId": %d
                                }
                                """.formatted(ownerWorkspaceId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void internalAgentRunContextIncludesWorkspaceId() throws Exception {
        mockExternalAuthDependencies();
        register("agent_workspace_context_user");
        String token = login("agent_workspace_context_user");
        Long workspaceId = defaultWorkspaceId(token);
        Long sessionId = createSession(token, "Workspace Context");
        Long runId = sendMessage(token, sessionId, "Use workspace memory later.").runId();

        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(workspaceId));
    }

    @Test
    void userCanUploadFileAndAgentContextIncludesParsedFileText() throws Exception {
        mockExternalAuthDependencies();
        register("agent_file_user");
        String token = login("agent_file_user");
        Long sessionId = createSession(token, "File Context");
        Mockito.when(agentServiceClient.parseFile(anyString(), anyString(), any(byte[].class)))
                .thenReturn(AgentFileParseResult.fromText("product.txt", "产品说明：标准版包含 3 个项目和团队协作。"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "product.txt",
                "text/plain",
                "产品说明：标准版包含 3 个项目和团队协作。".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/agent/sessions/{sessionId}/files", sessionId)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("product.txt"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.extractedText").value("产品说明：标准版包含 3 个项目和团队协作。"));

        Long runId = sendMessage(token, sessionId, "请基于上传文件总结标准版权益").runId();
        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentFiles[0].originalFilename").value("product.txt"))
                .andExpect(jsonPath("$.data.agentFiles[0].extractedText").value("产品说明：标准版包含 3 个项目和团队协作。"));
    }

    @Test
    void agentContextRetrievesRelevantChunksForUserQuestion() throws Exception {
        mockExternalAuthDependencies();
        register("agent_chunk_user");
        String token = login("agent_chunk_user");
        Long sessionId = createSession(token, "Chunk Context");
        Mockito.when(agentServiceClient.parseFile(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new AgentFileParseResult(
                        "product.txt",
                        "Pricing: Pro plan includes unlimited exports.\n\nSecurity: SSO and audit logs are included.",
                        java.util.List.of(
                                new AgentFileParseChunk(0, "Pricing: Pro plan includes unlimited exports.", java.util.Map.of("source", "product.txt")),
                                new AgentFileParseChunk(1, "Security: SSO and audit logs are included.", java.util.Map.of("source", "product.txt"))
                        )
                ));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "product.txt",
                "text/plain",
                "placeholder".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/agent/sessions/{sessionId}/files", sessionId)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        Long runId = sendMessage(token, sessionId, "What security features are included?").runId();
        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentFileChunks[0].originalFilename").value("product.txt"))
                .andExpect(jsonPath("$.data.agentFileChunks[0].contentText").value("Security: SSO and audit logs are included."))
                .andExpect(jsonPath("$.data.agentFileChunks[0].chunkIndex").value(1));
    }

    @Test
    void internalAgentCanCreateReadyArtifactForRun() throws Exception {
        mockExternalAuthDependencies();
        register("agent_artifact_user");
        LoginResult login = loginWithUser("agent_artifact_user");
        Long sessionId = createSession(login.token(), "Artifact Session");
        Long runId = sendMessage(login.token(), sessionId, "Create a long-running artifact.").runId();
        Mockito.clearInvocations(agentServiceClient);

        String artifactBody = """
                {
                  "filename": "../report.md",
                  "contentType": "text/markdown",
                  "content": "Long task result\\n\\nIncludes detailed recommendations."
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/artifacts", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/artifacts".formatted(runId), artifactBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(artifactBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.originalFilename").value("report.md"))
                .andExpect(jsonPath("$.data.contentType").value("text/markdown"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.extractedText").value("Long task result\n\nIncludes detailed recommendations."));

        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentFiles[0].originalFilename").value("report.md"))
                .andExpect(jsonPath("$.data.agentFileChunks[0].contentText").value("Long task result"));

        var chunks = agentFileChunkMapper.findReadyBySession(login.userId(), sessionId, 10);
        org.assertj.core.api.Assertions.assertThat(chunks)
                .extracting("contentText")
                .containsExactly("Long task result", "Includes detailed recommendations.");

        Mockito.verify(agentServiceClient, Mockito.never()).parseFile(anyString(), anyString(), any(byte[].class));
    }

    @Test
    void internalAgentArtifactRequiresContent() throws Exception {
        mockExternalAuthDependencies();
        register("agent_empty_artifact_user");
        String token = login("agent_empty_artifact_user");
        Long sessionId = createSession(token, "Empty Artifact Session");
        Long runId = sendMessage(token, sessionId, "Create an empty artifact.").runId();

        String artifactBody = """
                {
                  "filename": "empty.md",
                  "contentType": "text/markdown",
                  "content": ""
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/artifacts", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/artifacts".formatted(runId), artifactBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(artifactBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));
    }

    @Test
    void internalAgentArtifactRequiresExistingRun() throws Exception {
        mockExternalAuthDependencies();
        String artifactBody = """
                {
                  "filename": "missing-run.md",
                  "contentType": "text/markdown",
                  "content": "Content for a missing run."
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/artifacts", 999999L), "POST",
                        "/api/internal/v1/agent/runs/%d/artifacts".formatted(999999L), artifactBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(artifactBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
    }

    @Test
    void agentServiceNotificationFailureDoesNotFailSendMessageRequest() throws Exception {
        mockExternalAuthDependencies();
        register("agent_notify_failure_user");
        LoginResult login = loginWithUser("agent_notify_failure_user");
        Long sessionId = createSession(login.token(), "Agent Notify Failure");
        Mockito.doThrow(new IllegalStateException("agent-service rejected request"))
                .when(agentServiceClient)
                .executeRun(anyLong());

        Long runId = sendMessage(login.token(), sessionId, "Please handle notification failure.").runId();

        mockMvc.perform(get("/api/v1/agent/runs/{runId}", runId)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value("AGENT_SERVICE_NOTIFY_FAILED"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].eventType").value("run.started"))
                .andExpect(jsonPath("$.data.list[1].eventType").value("run.failed"));

        var account = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(account.balance()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(account.frozen()).isZero();
        org.assertj.core.api.Assertions.assertThat(account.available()).isEqualTo(100);
    }

    @Test
    void userCanSubscribeRunEventStream() throws Exception {
        mockExternalAuthDependencies();
        register("agent_stream_user");
        String token = login("agent_stream_user");
        Long sessionId = createSession(token, "Agent Stream");
        Long runId = sendMessage(token, sessionId, "Stream events please.").runId();

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events/stream", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void userCannotReadAnotherUsersAgentData() throws Exception {
        mockExternalAuthDependencies();
        register("agent_owner_user");
        String ownerToken = login("agent_owner_user");
        register("agent_other_user");
        String otherToken = login("agent_other_user");
        Long sessionId = createSession(ownerToken, "Private Session");
        Long runId = sendMessage(ownerToken, sessionId, "Private message").runId();

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_SESSION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}", runId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/agent/runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
    }

    @Test
    void internalAgentCallbacksRequireSignatureAndCanCompleteRun() throws Exception {
        mockExternalAuthDependencies();
        register("agent_callback_user");
        String token = login("agent_callback_user");
        Long sessionId = createSession(token, "Callbacks");
        Long runId = sendMessage(token, sessionId, "Callback message").runId();

        mockMvc.perform(post("/api/internal/v1/agent/runs/{runId}/events", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"intent.detected\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String eventBody = """
                {
                  "eventType": "intent.detected",
                  "eventText": "Intent detected"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/events", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/events".formatted(runId), eventBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.eventType").value("intent.detected"));

        String completeBody = """
                {
                  "finalAnswer": "This is the final answer.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 1
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/complete", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/complete".formatted(runId), completeBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.list[1].contentText").value("This is the final answer."));

        assertThat(agentMessageMapper.findUserMessageByRunId(runId)).isNotNull();
        assertThat(agentRunEventMapper.findEventsForAdmin(runId, 10))
                .extracting("eventType")
                .containsExactly("run.started", "intent.detected", "run.completed");
    }

    @Test
    void userCanManagePerToolAutoCallPreference() throws Exception {
        mockExternalAuthDependencies();
        register("agent_preference_user");
        String token = login("agent_preference_user");

        mockMvc.perform(get("/api/v1/agent/tool-preferences")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list.length()").value(0));

        mockMvc.perform(put("/api/v1/agent/tool-preferences/{toolCode}", "xiaohongshu_copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoCallEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.autoCallEnabled").value(true));

        mockMvc.perform(get("/api/v1/agent/tool-preferences")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.list[0].autoCallEnabled").value(true));

        mockMvc.perform(put("/api/v1/agent/tool-preferences/{toolCode}", "xiaohongshu_copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoCallEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoCallEnabled").value(false));

        mockMvc.perform(put("/api/v1/agent/tool-preferences/{toolCode}", "xiaohongshu_copywriting")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoCallEnabled\":true}"))
                .andExpect(status().isOk());

        Long sessionId = createSession(token, "Preference Context");
        Long runId = sendMessage(token, sessionId, "帮我写小红书笔记").runId();

        mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolPreferences[0].toolCode").value("xiaohongshu_copywriting"))
                .andExpect(jsonPath("$.data.toolPreferences[0].autoCallEnabled").value(true));
    }

    @Test
    void userCannotConfirmToolForFinishedRun() throws Exception {
        mockExternalAuthDependencies();
        register("agent_finished_user");
        String token = login("agent_finished_user");
        Long sessionId = createSession(token, "Finished Run");
        Long runId = sendMessage(token, sessionId, "Finish before confirm.").runId();

        String completeBody = """
                {
                  "finalAnswer": "Finished.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 1
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/complete", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/complete".formatted(runId), completeBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agent/runs/{runId}/tool-confirmations", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"xiaohongshu_copywriting\",\"approved\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_CANCELLABLE"));
    }

    @Test
    void userCannotStartAgentRunWhenCreditBudgetIsNotEnough() throws Exception {
        mockExternalAuthDependencies();
        String username = "agent_low_credit_user";
        register(username);
        LoginResult login = loginWithUser(username);
        creditService.manualDeduct(login.userId(), 100, "test low credit", 1L);
        Long sessionId = createSession(login.token(), "Low Credit");
        Mockito.clearInvocations(agentServiceClient);

        mockMvc.perform(post("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Help me with an agent task",
                                  "clientRequestId": "low-credit-agent-run"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_CREDIT_NOT_ENOUGH"));

        Mockito.verify(agentServiceClient, Mockito.never()).executeRun(anyLong());
    }

    @Test
    void userCannotStartAgentRunWhenModelConnectivityFails() throws Exception {
        mockExternalAuthDependencies();
        Mockito.when(agentServiceClient.testModelConfig(any()))
                .thenReturn(new AgentModelConfigTestResponse(
                        false,
                        "openai_compatible",
                        "gpt-test",
                        32L,
                        "connection refused",
                        ""
                ));
        String username = "agent_model_preflight_user";
        register(username);
        LoginResult login = loginWithUser(username);
        Long sessionId = createSession(login.token(), "Model Preflight");

        mockMvc.perform(post("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Start with invalid model",
                                  "clientRequestId": "model-preflight-failure"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_CALL_FAILED"))
                .andExpect(jsonPath("$.message").value("connection refused"));

        Mockito.verify(agentServiceClient, Mockito.never()).executeRun(anyLong());
        org.assertj.core.api.Assertions.assertThat(creditService.account(login.userId()).frozen()).isZero();

        var failedRuns = agentRunMapper.findForAdmin("FAILED", login.userId(), 10, 0);
        org.assertj.core.api.Assertions.assertThat(failedRuns).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(failedRuns.get(0).errorCode()).isEqualTo("MODEL_CALL_FAILED");
        org.assertj.core.api.Assertions.assertThat(failedRuns.get(0).errorMessage()).isEqualTo("connection refused");
        var events = agentRunEventMapper.findEventsForAdmin(failedRuns.get(0).id(), 10);
        org.assertj.core.api.Assertions.assertThat(events).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(events.get(0).getEventType()).isEqualTo("model.preflight_failed");
        org.assertj.core.api.Assertions.assertThat(events.get(0).getEventText()).isEqualTo("connection refused");
    }

    @Test
    void agentRunFreezesSettlesAndReleasesCredits() throws Exception {
        mockExternalAuthDependencies();
        String username = "agent_credit_success_user";
        register(username);
        LoginResult login = loginWithUser(username);
        Long sessionId = createSession(login.token(), "Agent Credit Success");

        Long runId = sendMessage(login.token(), sessionId, "Use agent credits.").runId();
        var frozen = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(frozen.balance()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(frozen.frozen()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(frozen.available()).isEqualTo(80);

        String completeBody = """
                {
                  "finalAnswer": "Finished with credits.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 7
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/complete", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/complete".formatted(runId), completeBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumedCredits").value(7));

        var settled = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(settled.balance()).isEqualTo(93);
        org.assertj.core.api.Assertions.assertThat(settled.frozen()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(settled.available()).isEqualTo(93);
    }

    @Test
    void agentRunCancelReleasesFrozenCredits() throws Exception {
        mockExternalAuthDependencies();
        String username = "agent_credit_cancel_user";
        register(username);
        LoginResult login = loginWithUser(username);
        Long sessionId = createSession(login.token(), "Agent Credit Cancel");

        Long runId = sendMessage(login.token(), sessionId, "Cancel agent credits.").runId();
        org.assertj.core.api.Assertions.assertThat(creditService.account(login.userId()).frozen()).isEqualTo(20);

        mockMvc.perform(post("/api/v1/agent/runs/{runId}/cancel", runId)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        var released = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(released.balance()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(released.frozen()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(released.available()).isEqualTo(100);
    }

    @Test
    void repeatedCompleteCallbackDoesNotDoubleChargeOrDuplicateAnswer() throws Exception {
        mockExternalAuthDependencies();
        String username = "agent_complete_idempotent_user";
        register(username);
        LoginResult login = loginWithUser(username);
        Long sessionId = createSession(login.token(), "Agent Complete Idempotent");

        Long runId = sendMessage(login.token(), sessionId, "Complete twice.").runId();
        String completeBody = """
                {
                  "finalAnswer": "Only one answer.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 7
                }
                """;

        completeRun(runId, completeBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        completeRun(runId, completeBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.consumedCredits").value(7));

        var settled = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(settled.balance()).isEqualTo(93);
        org.assertj.core.api.Assertions.assertThat(settled.frozen()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(settled.available()).isEqualTo(93);
        Integer billingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE source_type = 'AGENT_RUN' AND source_id = ? AND charged_credits = 7 AND prompt_tokens = 0 AND completion_tokens = 0",
                Integer.class,
                runId
        );
        org.assertj.core.api.Assertions.assertThat(billingCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[1].contentText").value("Only one answer."));
    }

    @Test
    void userCanRegenerateAfterRunCompletesAndAssistantListShowsLatestOnly() throws Exception {
        mockExternalAuthDependencies();
        register("agent_regenerate_user");
        String token = login("agent_regenerate_user");
        Long sessionId = createSession(token, "Regenerate");
        Long runId1 = sendMessage(token, sessionId, "First question.").runId();
        String complete1 = """
                {
                  "finalAnswer": "First answer.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 1
                }
                """;
        completeRun(runId1, complete1).andExpect(status().isOk());

        String regenResponse = mockMvc.perform(post("/api/v1/agent/runs/{runId}/regenerate", runId1)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode regenData = objectMapper.readTree(regenResponse).path("data");
        Long runId2 = regenData.path("runId").asLong();
        assertThat(runId2).isNotEqualTo(runId1);
        Mockito.verify(agentServiceClient).executeRun(runId1);
        Mockito.verify(agentServiceClient).executeRun(runId2);

        String complete2 = """
                {
                  "finalAnswer": "Second answer.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 1
                }
                """;
        completeRun(runId2, complete2).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[1].contentText").value("Second answer."));
    }

    @Test
    void regenerateRunContextExcludesSupersededAssistant() throws Exception {
        mockExternalAuthDependencies();
        register("agent_regen_context_user");
        String token = login("agent_regen_context_user");
        Long sessionId = createSession(token, "Regen Context");
        Long runId1 = sendMessage(token, sessionId, "Q1").runId();
        completeRun(runId1, """
                {
                  "finalAnswer": "OLD_ASSISTANT_TEXT",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 0
                }
                """).andExpect(status().isOk());

        String regenResponse = mockMvc.perform(post("/api/v1/agent/runs/{runId}/regenerate", runId1)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long runId2 = objectMapper.readTree(regenResponse).path("data").path("runId").asLong();

        String ctx = mockMvc.perform(signed(get("/api/internal/v1/agent/runs/{runId}/context", runId2), "GET",
                        "/api/internal/v1/agent/runs/%d/context".formatted(runId2), ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ctx).doesNotContain("OLD_ASSISTANT_TEXT");
        assertThat(objectMapper.readTree(ctx).path("data").path("history").size()).isEqualTo(0);
    }

    @Test
    void editRegenerateTruncatesLaterTurns() throws Exception {
        mockExternalAuthDependencies();
        register("agent_edit_truncate_user");
        String token = login("agent_edit_truncate_user");
        Long sessionId = createSession(token, "Edit Truncate");
        SendMessageResult first = sendMessage(token, sessionId, "Round one");
        completeRun(first.runId(), """
                {
                  "finalAnswer": "Answer one.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 0
                }
                """).andExpect(status().isOk());
        SendMessageResult second = sendMessage(token, sessionId, "Round two");
        completeRun(second.runId(), """
                {
                  "finalAnswer": "Answer two.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 0
                }
                """).andExpect(status().isOk());

        String editBody = """
                {
                  "content": "Round one edited",
                  "clientRequestId": null
                }
                """;
        String editResp = mockMvc.perform(post(
                        "/api/v1/agent/sessions/{sessionId}/messages/{messageId}/edit-regenerate",
                        sessionId, first.messageId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long runId3 = objectMapper.readTree(editResp).path("data").path("runId").asLong();
        completeRun(runId3, """
                {
                  "finalAnswer": "Answer one revised.",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 0
                }
                """).andExpect(status().isOk());

        String listJson = mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listJson).contains("Round one edited");
        assertThat(listJson).contains("Answer one revised.");
        assertThat(listJson).doesNotContain("Round two");
        assertThat(listJson).doesNotContain("Answer two.");
    }

    @Test
    void cannotRegenerateWhileRunIsNonTerminal() throws Exception {
        mockExternalAuthDependencies();
        register("agent_regen_running_user");
        String token = login("agent_regen_running_user");
        Long sessionId = createSession(token, "Running");
        Long runId = sendMessage(token, sessionId, "Still running.").runId();

        mockMvc.perform(post("/api/v1/agent/runs/{runId}/regenerate", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_REGENERATABLE"));
    }

    @Test
    void regenerateIsIdempotentWhenClientRequestIdRepeats() throws Exception {
        mockExternalAuthDependencies();
        register("agent_regen_idem_user");
        String token = login("agent_regen_idem_user");
        Long sessionId = createSession(token, "Idem");
        Long runId1 = sendMessage(token, sessionId, "Q").runId();
        completeRun(runId1, """
                {
                  "finalAnswer": "A",
                  "intent": "general_chat",
                  "modelProviderCode": "mock",
                  "modelName": "mock-chat",
                  "consumedCredits": 0
                }
                """).andExpect(status().isOk());

        String body = """
                { "clientRequestId": "regen-idem-key-1" }
                """;
        String r1 = mockMvc.perform(post("/api/v1/agent/runs/{runId}/regenerate", runId1)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String r2 = mockMvc.perform(post("/api/v1/agent/runs/{runId}/regenerate", runId1)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(r1).path("data").path("runId").asLong())
                .isEqualTo(objectMapper.readTree(r2).path("data").path("runId").asLong());
    }

    @Test
    void failCallbackAfterCancelKeepsRunCancelledAndDoesNotChangeCredits() throws Exception {
        mockExternalAuthDependencies();
        String username = "agent_cancel_fail_idempotent_user";
        register(username);
        LoginResult login = loginWithUser(username);
        Long sessionId = createSession(login.token(), "Agent Cancel Then Fail");

        Long runId = sendMessage(login.token(), sessionId, "Cancel then fail.").runId();
        mockMvc.perform(post("/api/v1/agent/runs/{runId}/cancel", runId)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        String failBody = """
                {
                  "errorCode": "WORKER_TIMEOUT",
                  "errorMessage": "Worker timed out after cancellation."
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/fail", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/fail".formatted(runId), failBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        var released = creditService.account(login.userId());
        org.assertj.core.api.Assertions.assertThat(released.balance()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(released.frozen()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(released.available()).isEqualTo(100);
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

    private Long defaultWorkspaceId(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v1/agent/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private SendMessageResult sendMessage(String token, Long sessionId, String content) throws Exception {
        return sendMessage(token, sessionId, content, java.util.UUID.randomUUID().toString());
    }

    private SendMessageResult sendMessage(String token, Long sessionId, String content, String clientRequestId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/agent/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "%s",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(content, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new SendMessageResult(
                data.path("sessionId").asLong(),
                data.path("messageId").asLong(),
                data.path("runId").asLong(),
                data.path("runStatus").asText()
        );
    }

    private org.springframework.test.web.servlet.ResultActions completeRun(Long runId, String completeBody) throws Exception {
        return mockMvc.perform(signed(post("/api/internal/v1/agent/runs/{runId}/complete", runId), "POST",
                        "/api/internal/v1/agent/runs/%d/complete".formatted(runId), completeBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody));
    }

    private String login(String account) throws Exception {
        return loginWithUser(account).token();
    }

    private LoginResult loginWithUser(String account) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
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
        String token = response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
        Long userId = Long.parseLong(response.replaceAll("(?s).*\\\"user\\\"\\s*:\\s*\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        return new LoginResult(token, userId);
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "123456",
                                  "nickname": "%s"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk());
    }

    private record LoginResult(String token, Long userId) {
    }

    private record SendMessageResult(Long sessionId, Long messageId, Long runId, String runStatus) {
    }

    private void ensureOnlineTool(String toolCode) {
        Long existingToolId = jdbcTemplate.query(
                "SELECT id FROM ai_tools WHERE tool_code = ? AND status = 'ONLINE' AND is_deleted = 0",
                rs -> rs.next() ? rs.getLong(1) : null,
                toolCode
        );
        if (existingToolId != null) {
            return;
        }

        Long categoryId = jdbcTemplate.query(
                "SELECT id FROM tool_categories WHERE category_code = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                "agent-test-category"
        );
        if (categoryId == null) {
            jdbcTemplate.update(
                    "INSERT INTO tool_categories(category_code, category_name, sort_order, status) VALUES (?, ?, ?, ?)",
                    "agent-test-category",
                    "Agent Test Category",
                    0,
                    "ACTIVE"
            );
            categoryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tool_categories WHERE category_code = ?",
                    Long.class,
                    "agent-test-category"
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO ai_tools(tool_code, tool_name, category_id, description, status, estimated_credit_cost, is_deleted)
                VALUES (?, ?, ?, ?, 'ONLINE', ?, 0)
                """,
                toolCode,
                "Agent Test Tool",
                categoryId,
                "Tool seeded for agent callback tests",
                5
        );
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                toolCode
        );
        jdbcTemplate.update(
                "INSERT INTO tool_field_schemas(tool_id, schema_version, status) VALUES (?, ?, 'ACTIVE')",
                toolId,
                "v1"
        );
        Long schemaId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_field_schemas WHERE tool_id = ? AND schema_version = ?",
                Long.class,
                toolId,
                "v1"
        );
        jdbcTemplate.update(
                """
                INSERT INTO tool_field_schema_items(schema_id, field_key, field_name, field_type, placeholder, required, sort_order, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """,
                schemaId,
                "topic",
                "Topic",
                "text",
                "Provide a topic",
                1,
                1
        );
    }

}
