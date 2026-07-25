package com.aiminilab.aitoolmarket.ppt.engine.banana;

import com.aiminilab.aitoolmarket.ppt.domain.PptJobType;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineJobState;
import com.aiminilab.aitoolmarket.ppt.engine.EngineProjectRequest;
import com.aiminilab.aitoolmarket.ppt.service.PptEngineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BananaPptEngineAdapterTest {

    @Mock
    private PptEngineClient client;

    private ObjectMapper objectMapper;
    private BananaPptEngineAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new BananaPptEngineAdapter(client, objectMapper);
    }

    @Test
    void createsEngineProjectWithoutLeakingBananaFieldsToCaller() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("project_id", "external-123");
        response.put("status", "draft");
        when(client.createProject(any())).thenReturn(response);

        var project = adapter.createProject(new EngineProjectRequest(
                "季度复盘", "复盘增长与成本", "idea", "zh-CN", "16:9", 12));

        assertThat(project.externalProjectId()).isEqualTo("external-123");
        assertThat(project.status()).isEqualTo("draft");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).createProject(payload.capture());
        assertThat(payload.getValue())
                .containsEntry("creation_type", "idea")
                .containsEntry("idea_prompt", "复盘增长与成本")
                .containsEntry("image_aspect_ratio", "16:9")
                .containsEntry("page_count", 12);
    }

    @Test
    void mapsPlatformAiGeneratedCreationTypeToBananaIdeaContract() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("project_id", "external-ai");
        when(client.createProject(any())).thenReturn(response);

        adapter.createProject(new EngineProjectRequest(
                "产品发布", "介绍新产品", "AI_GENERATED", "zh-CN", "16:9", 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).createProject(payload.capture());
        assertThat(payload.getValue()).containsEntry("creation_type", "idea");
    }

    @Test
    void mapsAsyncImageGenerationToSubmittedPlatformState() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("task_id", "task-9");
        response.put("status", "processing");
        response.put("progress", 15);
        when(client.postProjectAction(eq("project-7"), eq("/generate/images"), any()))
                .thenReturn(response);

        var submission = adapter.submit(new EngineJobRequest(
                PptJobType.GENERATE_IMAGES,
                "project-7",
                objectMapper.createObjectNode().put("style", "minimal"),
                17L,
                19L,
                "ppt-job:19:generate-images",
                "http://backend:8080",
                "task-scoped-token"
        ));

        assertThat(submission.state()).isEqualTo(EngineJobState.RUNNING);
        assertThat(submission.externalJobId()).isEqualTo("task-9");
        assertThat(submission.progress()).isEqualTo(15);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).postProjectAction(eq("project-7"), eq("/generate/images"), payload.capture());
        assertThat(payload.getValue()).containsKey("platform_execution");
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) payload.getValue().get("platform_execution");
        assertThat(execution)
                .containsEntry("provider", "KCD_PLATFORM")
                .containsEntry("project_id", 17L)
                .containsEntry("job_id", 19L)
                .containsEntry("execution_token", "task-scoped-token");
    }

    @Test
    void mapsFailedEngineTaskToRetryableSnapshot() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "failed");
        response.put("error_message", "provider timeout");
        when(client.getProjectAction("project-7", "/tasks/task-9")).thenReturn(response);

        var snapshot = adapter.query("project-7", "task-9");

        assertThat(snapshot.state()).isEqualTo(EngineJobState.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("PPT_ENGINE_JOB_FAILED");
        assertThat(snapshot.errorMessage()).isEqualTo("provider timeout");
        assertThat(snapshot.retryable()).isTrue();
    }
}
