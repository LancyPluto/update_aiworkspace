package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.config.AuthInterceptor;
import com.aiminilab.aitoolmarket.task.controller.InternalTaskController;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerInternalApiSecurityTest {

    private static final String SECRET = "test-internal-secret";

    private InternalRequestSignatureVerifier verifier;
    private InternalTaskService internalTaskService;
    private TaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        verifier = mock(InternalRequestSignatureVerifier.class);
        internalTaskService = mock(InternalTaskService.class);
        taskService = mock(TaskService.class);
        when(internalTaskService.markProcessing(eq(1L), any()))
                .thenReturn(new TaskStatusResponse(1L, "TASK-1", "PROCESSING", 35, "AI is generating"));

        AuthInterceptor authInterceptor = new AuthInterceptor(
                mock(JwtTokenProvider.class),
                new ObjectMapper(),
                verifier
        );
        authInterceptor.init(new MockFilterConfig());

        mockMvc = MockMvcBuilders.standaloneSetup(new InternalTaskController(internalTaskService, taskService))
                .addFilters(authInterceptor)
                .build();
    }

    @Test
    void validSignaturePasses() throws Exception {
        String body = """
                {
                  "progress": 35,
                  "progressMessage": "AI is generating"
                }
                """;
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = "nonce-valid";
        String signature = signature("POST", "/api/internal/v1/tasks/1/processing", timestamp, nonce, body);
        when(verifier.verify(
                eq("POST"),
                eq("/api/internal/v1/tasks/1/processing"),
                eq(timestamp),
                eq(nonce),
                eq(signature),
                eq(body.getBytes(StandardCharsets.UTF_8))
        )).thenReturn(true);

        mockMvc.perform(post("/api/internal/v1/tasks/1/processing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Timestamp", timestamp)
                        .header("X-Internal-Nonce", nonce)
                        .header("X-Internal-Signature", signature)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void missingSignatureFails() throws Exception {
        mockMvc.perform(post("/api/internal/v1/tasks/1/processing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String signature(String method, String path, String timestamp, String nonce, String body) {
        return method + ":" + path + ":" + timestamp + ":" + nonce + ":" + body.hashCode() + ":" + SECRET;
    }
}
