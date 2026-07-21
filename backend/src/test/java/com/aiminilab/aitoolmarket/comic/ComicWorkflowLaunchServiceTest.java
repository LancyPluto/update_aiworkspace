package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowEpisodeStateService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowLaunchService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComicWorkflowLaunchServiceTest {
    private static final long USER_ID = 9L;
    private static final long PROJECT_ID = 5L;
    private static final long EPISODE_ID = 7L;
    private static final long TOOL_ID = 17L;

    private ComicWorkflowEpisodeStateService stateService;
    private ComicProjectService projectService;
    private ComicProjectMapper projectMapper;
    private WorkflowRunMapper workflowRunMapper;
    private ComicProjectWorkflowRunMapper projectWorkflowRunMapper;
    private ToolMapper toolMapper;
    private WorkflowRunApplicationService workflowService;
    private ObjectMapper objectMapper;
    private ComicWorkflowLaunchService service;

    @BeforeEach
    void setUp() {
        stateService = mock(ComicWorkflowEpisodeStateService.class);
        projectService = mock(ComicProjectService.class);
        projectMapper = mock(ComicProjectMapper.class);
        workflowRunMapper = mock(WorkflowRunMapper.class);
        projectWorkflowRunMapper = mock(ComicProjectWorkflowRunMapper.class);
        toolMapper = mock(ToolMapper.class);
        workflowService = mock(WorkflowRunApplicationService.class);
        objectMapper = new ObjectMapper();
        service = new ComicWorkflowLaunchService(
                stateService,
                projectService,
                projectMapper,
                workflowRunMapper,
                projectWorkflowRunMapper,
                toolMapper,
                workflowService,
                objectMapper
        );
        ComicProject project = project();
        when(projectMapper.selectOwnedForUpdate(PROJECT_ID, USER_ID)).thenReturn(project);
        AiTool tool = new AiTool();
        tool.setId(TOOL_ID);
        when(toolMapper.findAnyByCode("ai_comic_drama_agent")).thenReturn(Optional.of(tool));
    }

    @Test
    void scriptGenerationRunsOnlyScriptInTheCurrentTransaction() throws Exception {
        ComicDtos.GenerateEpisodeRequest request = new ComicDtos.GenerateEpisodeRequest(
                "雨夜来客", "一名少年发现古城秘密", 1, "script-request"
        );
        when(stateService.startScriptGeneration(USER_ID, PROJECT_ID, request))
                .thenReturn(snapshot("", "SCRIPT_GENERATING"));
        when(workflowService.createInCurrentTransaction(any()))
                .thenReturn(new WorkflowRunCreated(101L, 201L, 301L, "RUNNING"));
        when(workflowRunMapper.selectById(201L)).thenReturn(scriptRun(request));
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L)).thenReturn(binding(PROJECT_ID));

        service.generateScript(USER_ID, PROJECT_ID, request);

        ArgumentCaptor<CreateWorkflowRunCommand> command =
                ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().clientRequestId()).isEqualTo("comic-script-script-request");
        assertThat(command.getValue().input().path("sourceMode").asText()).isEqualTo("AI_CREATE");
        assertThat(command.getValue().input().has("scriptText")).isFalse();
        assertThat(command.getValue().input().path("operationHandlerKeys").get(0).asText())
                .isEqualTo("comic.script");
    }

    @Test
    void storyboardGenerationPersistsRevisionAndUsesOnlyStoryboard() throws Exception {
        ComicDtos.GenerateStoryboardRequest request =
                new ComicDtos.GenerateStoryboardRequest(4L, "storyboard-request");
        when(stateService.startStoryboardGeneration(USER_ID, PROJECT_ID, EPISODE_ID, request))
                .thenReturn(snapshot("完整剧本正文", "STORYBOARD_GENERATING"));
        when(workflowService.createInCurrentTransaction(any()))
                .thenReturn(new WorkflowRunCreated(102L, 202L, 302L, "RUNNING"));
        when(workflowRunMapper.selectById(202L)).thenReturn(storyboardRun(request));
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(202L))
                .thenReturn(binding(PROJECT_ID, 202L, 102L));

        service.generateStoryboard(USER_ID, PROJECT_ID, EPISODE_ID, request);

        ArgumentCaptor<CreateWorkflowRunCommand> command =
                ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().clientRequestId()).isEqualTo("comic-storyboard-storyboard-request");
        assertThat(command.getValue().input().path("scriptText").asText()).isEqualTo("完整剧本正文");
        assertThat(command.getValue().input().path("expectedRevision").asLong()).isEqualTo(4L);
        assertThat(command.getValue().input().path("operationHandlerKeys").get(0).asText())
                .isEqualTo("comic.storyboard");
    }

    @Test
    void identicalRequestKeyReturnsTheOriginallyBoundEpisodeWithoutChangingState() throws Exception {
        ComicDtos.GenerateEpisodeRequest request = new ComicDtos.GenerateEpisodeRequest(
                "雨夜来客", "一名少年发现古城秘密", 1, "script-request"
        );
        WorkflowRun existing = scriptRun(request);
        when(workflowRunMapper.selectByUserAndClientRequestId(
                USER_ID, "comic-script-script-request"
        )).thenReturn(existing);
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L)).thenReturn(binding(PROJECT_ID));

        service.generateScript(USER_ID, PROJECT_ID, request);

        verify(projectService).episodeDetail(USER_ID, PROJECT_ID, EPISODE_ID);
        verify(stateService, never()).startScriptGeneration(any(), any(), any());
        verify(workflowService, never()).createInCurrentTransaction(any());
    }

    @Test
    void requestKeyBoundToAnotherProjectOrInputIsRejected() throws Exception {
        ComicDtos.GenerateEpisodeRequest request = new ComicDtos.GenerateEpisodeRequest(
                "雨夜来客", "一名少年发现古城秘密", 1, "script-request"
        );
        WorkflowRun existing = scriptRun(request);
        when(workflowRunMapper.selectByUserAndClientRequestId(
                USER_ID, "comic-script-script-request"
        )).thenReturn(existing);
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L)).thenReturn(binding(6L));

        assertConflict(() -> service.generateScript(USER_ID, PROJECT_ID, request));

        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L)).thenReturn(binding(PROJECT_ID));
        ComicDtos.GenerateEpisodeRequest changedPrompt = new ComicDtos.GenerateEpisodeRequest(
                "雨夜来客", "完全不同的剧情要求", 1, "script-request"
        );
        assertConflict(() -> service.generateScript(USER_ID, PROJECT_ID, changedPrompt));
        verify(stateService, never()).startScriptGeneration(any(), any(), any());
    }

    @Test
    void conflictingRunReturnedAfterCreateIsRejectedSoTheOuterTransactionCanRollBack() throws Exception {
        ComicDtos.GenerateEpisodeRequest request = new ComicDtos.GenerateEpisodeRequest(
                "雨夜来客", "一名少年发现古城秘密", 1, "script-request"
        );
        when(stateService.startScriptGeneration(USER_ID, PROJECT_ID, request))
                .thenReturn(snapshot("", "SCRIPT_GENERATING"));
        when(workflowService.createInCurrentTransaction(any()))
                .thenReturn(new WorkflowRunCreated(101L, 201L, 301L, "RUNNING"));
        when(workflowRunMapper.selectById(201L)).thenReturn(scriptRun(request));
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L)).thenReturn(binding(6L));

        assertConflict(() -> service.generateScript(USER_ID, PROJECT_ID, request));

        assertThat(ComicWorkflowLaunchService.class
                .getMethod("generateScript", Long.class, Long.class, ComicDtos.GenerateEpisodeRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void generationRequestsRequireAClientRequestIdNoLongerThanNinetySixCharacters() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new ComicDtos.GenerateEpisodeRequest(
                "标题", "足够长的创作要求", 1, ""
        ))).anyMatch(violation -> violation.getPropertyPath().toString().equals("clientRequestId"));
        assertThat(validator.validate(new ComicDtos.GenerateStoryboardRequest(
                0L, "x".repeat(97)
        ))).anyMatch(violation -> violation.getPropertyPath().toString().equals("clientRequestId"));
        assertThat(validator.validate(new ComicDtos.GenerateStoryboardRequest(
                0L, "request-1"
        ))).isEmpty();
    }

    private WorkflowRun scriptRun(ComicDtos.GenerateEpisodeRequest request) throws Exception {
        ObjectNode input = commonInput("comic.script");
        input.put("sourceMode", "AI_CREATE");
        input.put("storyTheme", request.title().trim());
        input.put("title", request.title().trim());
        input.put("plotOutline", request.prompt().trim());
        input.put("prompt", request.prompt().trim());
        input.put("requestedEpisodeNo", request.episodeNo());
        return run(201L, 101L, "comic-script-" + request.clientRequestId(), input);
    }

    private WorkflowRun storyboardRun(ComicDtos.GenerateStoryboardRequest request) throws Exception {
        ObjectNode input = commonInput("comic.storyboard");
        input.put("sourceMode", "IMPORT");
        input.put("expectedRevision", request.expectedRevision());
        return run(202L, 102L, "comic-storyboard-" + request.clientRequestId(), input);
    }

    private ObjectNode commonInput(String handlerKey) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("comicProjectId", PROJECT_ID);
        input.put("projectId", PROJECT_ID);
        input.put("comicEpisodeId", EPISODE_ID);
        input.put("episodeId", EPISODE_ID);
        input.putArray("operationHandlerKeys").add(handlerKey);
        return input;
    }

    private WorkflowRun run(Long runId, Long rootTaskId, String clientRequestId,
                            ObjectNode input) throws Exception {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setUserId(USER_ID);
        run.setToolId(TOOL_ID);
        run.setRootTaskId(rootTaskId);
        run.setLaunchSource("COMIC_PROJECT");
        run.setClientRequestId(clientRequestId);
        run.setInputJson(objectMapper.writeValueAsString(input));
        return run;
    }

    private ComicProjectWorkflowRun binding(Long projectId) {
        return binding(projectId, 201L, 101L);
    }

    private ComicProjectWorkflowRun binding(Long projectId, Long workflowRunId, Long rootTaskId) {
        ComicProjectWorkflowRun binding = new ComicProjectWorkflowRun();
        binding.setUserId(USER_ID);
        binding.setProjectId(projectId);
        binding.setEpisodeId(EPISODE_ID);
        binding.setWorkflowRunId(workflowRunId);
        binding.setRootTaskId(rootTaskId);
        return binding;
    }

    private ComicWorkflowEpisodeStateService.GenerationSnapshot snapshot(String scriptText, String status) {
        ComicEpisode episode = new ComicEpisode();
        episode.setId(EPISODE_ID);
        episode.setProjectId(PROJECT_ID);
        episode.setEpisodeNo(1);
        episode.setTitle("雨夜来客");
        episode.setScriptText(scriptText);
        episode.setStatus(status);
        episode.setRevision(4L);
        return new ComicWorkflowEpisodeStateService.GenerationSnapshot(project(), episode);
    }

    private ComicProject project() {
        ComicProject project = new ComicProject();
        project.setId(PROJECT_ID);
        project.setTitle("古城秘密");
        project.setAspectRatio("16:9");
        project.setVisualStyle("国风动画");
        return project;
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }
}
