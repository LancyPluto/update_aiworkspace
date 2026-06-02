package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.service.AgentRateLimitService;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.beans.factory.annotation.Autowired;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_workspace_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.agent.max-active-runs-per-user=100",
        "app.agent.max-messages-per-minute=100"
})
class AgentWorkspaceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @MockBean
    private InternalRequestSignatureVerifier internalRequestSignatureVerifier;

    @MockBean
    private AgentRateLimitService agentRateLimitService;

    @MockBean
    private AgentServiceClient agentServiceClient;

    @Test
    void createsDefaultWorkspaceForCurrentUser() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        register("workspace_default_user");
        String token = login("workspace_default_user");

        mockMvc.perform(get("/api/v1/agent/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").isNumber())
                .andExpect(jsonPath("$.data.list[0].name").value("Default Workspace"))
                .andExpect(jsonPath("$.data.list[0].workspaceType").value("PERSONAL"))
                .andExpect(jsonPath("$.data.list[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.list[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/v1/agent/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void managesWorkspaceMemoryItems() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        register("workspace_memory_user");
        String token = login("workspace_memory_user");
        long workspaceId = defaultWorkspaceId(token);

        long firstMemoryId = createMemory(token, workspaceId, "preference", "Tone", "Prefer concise Chinese answers.");
        long secondMemoryId = createMemory(token, workspaceId, "PROJECT", "Billing", "Project uses prepaid credits.", "workspace_fact");

        mockMvc.perform(get("/api/v1/agent/workspaces/{workspaceId}/memory", workspaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value(secondMemoryId))
                .andExpect(jsonPath("$.data.list[0].title").value("Billing"))
                .andExpect(jsonPath("$.data.list[0].memoryType").value("workspace_fact"))
                .andExpect(jsonPath("$.data.list[1].id").value(firstMemoryId));

        mockMvc.perform(put("/api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}", workspaceId, firstMemoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType": "preference",
                                  "title": "Tone updated",
                                  "content": "Prefer concise Chinese answers with concrete next steps."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstMemoryId))
                .andExpect(jsonPath("$.data.title").value("Tone updated"))
                .andExpect(jsonPath("$.data.content").value("Prefer concise Chinese answers with concrete next steps."));

        mockMvc.perform(get("/api/v1/agent/workspaces/{workspaceId}/memory", workspaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].id").value(firstMemoryId))
                .andExpect(jsonPath("$.data.list[0].title").value("Tone updated"));

        mockMvc.perform(put("/api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}/pin", workspaceId, firstMemoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinned\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pinned").value(true));

        mockMvc.perform(delete("/api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}", workspaceId, secondMemoryId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/agent/workspaces/{workspaceId}/memory", workspaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(firstMemoryId));
    }

    @Test
    void deniesWorkspaceMemoryAccessForNonMembers() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        register("workspace_memory_owner");
        String ownerToken = login("workspace_memory_owner");
        long ownerWorkspaceId = defaultWorkspaceId(ownerToken);
        long memoryId = createMemory(ownerToken, ownerWorkspaceId, "PROJECT", "Roadmap", "Workspace memory is private.", "workspace_fact");

        register("workspace_memory_intruder");
        String intruderToken = login("workspace_memory_intruder");

        mockMvc.perform(get("/api/v1/agent/workspaces/{workspaceId}/memory", ownerWorkspaceId)
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(put("/api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}", ownerWorkspaceId, memoryId)
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType": "workspace_fact",
                                  "title": "Stolen",
                                  "content": "Should not update."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/agent/workspaces/{workspaceId}/memory/{memoryId}", ownerWorkspaceId, memoryId)
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void retrievesWorkspaceMemoryForSignedInternalRequest() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        Mockito.when(internalRequestSignatureVerifier.verify(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(byte[].class)
                ))
                .thenReturn(true);
        register("workspace_memory_retrieve_user");
        String token = login("workspace_memory_retrieve_user");
        long workspaceId = defaultWorkspaceId(token);
        long titleMatchId = createMemory(token, workspaceId, "PROJECT", "Pricing policy", "Use prepaid credits.", "workspace_fact");
        long contentMatchId = createMemory(token, workspaceId, "PROJECT", "Billing notes", "Enterprise pricing uses annual invoices.", "workspace_fact");

        register("workspace_memory_other_user");
        String otherToken = login("workspace_memory_other_user");
        long otherWorkspaceId = defaultWorkspaceId(otherToken);
        createMemory(otherToken, otherWorkspaceId, "PROJECT", "Pricing policy", "This belongs to another workspace.", "workspace_fact");

        String body = """
                {
                  "query": "pricing",
                  "limit": 5
                }
                """;

        mockMvc.perform(post("/api/internal/v1/agent/workspaces/{workspaceId}/memory/retrieve", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(signed(post("/api/internal/v1/agent/workspaces/{workspaceId}/memory/retrieve", workspaceId), "POST",
                        "/api/internal/v1/agent/workspaces/%d/memory/retrieve".formatted(workspaceId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value(titleMatchId))
                .andExpect(jsonPath("$.data.list[0].score").value(2))
                .andExpect(jsonPath("$.data.list[1].id").value(contentMatchId))
                .andExpect(jsonPath("$.data.list[1].score").value(1));
    }

    @Test
    void retrievesHighValueMemoryContextPackWhenQueryHasNoLexicalMatch() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        Mockito.when(internalRequestSignatureVerifier.verify(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any(byte[].class)
                ))
                .thenReturn(true);
        register("workspace_memory_context_pack_user");
        String token = login("workspace_memory_context_pack_user");
        long workspaceId = defaultWorkspaceId(token);
        long profileId = createMemory(
                token,
                workspaceId,
                "user_profile",
                "User creative profile",
                "User enjoys absurd anime meme aesthetics and playful AI-generated visual ideas."
        );

        String body = """
                {
                  "query": "我是何人？",
                  "limit": 5,
                  "view": "chat"
                }
                """;

        mockMvc.perform(signed(post("/api/internal/v1/agent/workspaces/{workspaceId}/memory/retrieve", workspaceId), "POST",
                        "/api/internal/v1/agent/workspaces/%d/memory/retrieve".formatted(workspaceId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(profileId))
                .andExpect(jsonPath("$.data.list[0].reason").value("context_pack"));
    }

    @Test
    void createsMemoryCandidateForSignedInternalRequest() throws Exception {
        Mockito.when(tokenDenylistService.isDenied(anyString())).thenReturn(false);
        Mockito.when(internalRequestSignatureVerifier.verify(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any(byte[].class)
                ))
                .thenReturn(true);
        register("workspace_memory_candidate_user");
        String token = login("workspace_memory_candidate_user");
        long workspaceId = defaultWorkspaceId(token);

        String body = """
                {
                  "userId": 1,
                  "action": "candidate",
                  "memoryType": "workflow_recipe",
                  "title": "Image defaults",
                  "content": "Use square ratio for anime poster tests.",
                  "importance": 6,
                  "confidence": 0.66,
                  "reason": "workflow candidate"
                }
                """;

        mockMvc.perform(signed(post("/api/internal/v1/agent/workspaces/{workspaceId}/memory/candidates", workspaceId), "POST",
                        "/api/internal/v1/agent/workspaces/%d/memory/candidates".formatted(workspaceId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.memoryType").value("workflow_recipe"))
                .andExpect(jsonPath("$.data.importance").value(6));
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

    private String login(String account) throws Exception {
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
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }

    private long defaultWorkspaceId(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v1/agent/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        return root.path("data").path("list").get(0).path("id").asLong();
    }

    private long createMemory(String token, long workspaceId, String memoryType, String title, String content) throws Exception {
        return createMemory(token, workspaceId, memoryType, title, content, memoryType);
    }

    private long createMemory(String token, long workspaceId, String memoryType, String title, String content, String expectedMemoryType) throws Exception {
        String response = mockMvc.perform(post("/api/v1/agent/workspaces/{workspaceId}/memory", workspaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType": "%s",
                                  "title": "%s",
                                  "content": "%s"
                                }
                                """.formatted(memoryType, title, content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.data.memoryType").value(expectedMemoryType))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.content").value(content))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }
}
