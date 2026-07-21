package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.entity.ComicScene;
import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneVersionMapper;
import com.aiminilab.aitoolmarket.comic.service.ComicAssetGenerationService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectApplicationService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComicAssetGenerationServiceTest {
    private ComicProjectMapper projectMapper;
    private ComicCharacterMapper characterMapper;
    private ComicCharacterVersionMapper characterVersionMapper;
    private ComicSceneMapper sceneMapper;
    private ComicSceneVersionMapper sceneVersionMapper;
    private WorkflowRunMapper workflowRunMapper;
    private ComicProjectWorkflowRunMapper projectWorkflowRunMapper;
    private ToolMapper toolMapper;
    private WorkflowRunApplicationService workflowService;
    private ComicAssetGenerationService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ComicProjectMapper.class);
        characterMapper = mock(ComicCharacterMapper.class);
        characterVersionMapper = mock(ComicCharacterVersionMapper.class);
        sceneMapper = mock(ComicSceneMapper.class);
        sceneVersionMapper = mock(ComicSceneVersionMapper.class);
        workflowRunMapper = mock(WorkflowRunMapper.class);
        projectWorkflowRunMapper = mock(ComicProjectWorkflowRunMapper.class);
        toolMapper = mock(ToolMapper.class);
        workflowService = mock(WorkflowRunApplicationService.class);
        service = new ComicAssetGenerationService(
                projectMapper, characterMapper, characterVersionMapper, sceneMapper, sceneVersionMapper,
                workflowRunMapper, projectWorkflowRunMapper, toolMapper, workflowService, new ObjectMapper()
        );
        ComicProject project = new ComicProject();
        project.setId(5L);
        project.setTitle("古城秘密");
        project.setAspectRatio("16:9");
        project.setVisualStyle("国风动画");
        when(projectMapper.selectOwnedForUpdate(5L, 9L)).thenReturn(project);
        AiTool tool = new AiTool();
        tool.setId(71L);
        when(toolMapper.findAnyByCode(ComicProjectApplicationService.COMIC_TOOL_CODE))
                .thenReturn(Optional.of(tool));
        when(projectWorkflowRunMapper.selectByWorkflowRunInternal(201L))
                .thenReturn(binding(201L, 101L, 5L));
        when(workflowService.createInCurrentTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkflowRunCreated(101L, 201L, 301L, "RUNNING"));
    }

    @Test
    void characterGenerationMapsVisualPromptToWorkerAppearanceAndUsesOneHandler() {
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(5L);
        character.setName("林澈");
        character.setDescription("银发少年");
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setId(31L);
        version.setCharacterId(41L);
        version.setVisualPrompt("蓝色长衣，神情坚定");
        version.setStatus("DRAFT");
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.selectByIdForUpdate(31L)).thenReturn(version);
        when(characterVersionMapper.markGenerating(31L)).thenReturn(1);
        when(workflowRunMapper.selectById(201L))
                .thenReturn(assetRun(201L, 101L, 5L, "CHARACTER", 41L, 31L,
                        "comic.character_reference", "comic-asset-request-31"));

        ComicDtos.AssetGenerationDetail result = service.generateCharacter(
                9L, 5L, 41L, 31L, new ComicDtos.GenerateAssetVersionRequest("request-31")
        );

        ArgumentCaptor<CreateWorkflowRunCommand> command = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().input().path("character").path("appearance").asText())
                .contains("银发少年", "蓝色长衣");
        assertThat(command.getValue().input().path("operationHandlerKeys").size()).isEqualTo(1);
        assertThat(command.getValue().input().path("operationHandlerKeys").get(0).asText())
                .isEqualTo("comic.character_reference");
        assertThat(command.getValue().input().path("comicAssetVersionId").asLong()).isEqualTo(31L);
        assertThat(result.status()).isEqualTo("GENERATING");
    }

    @Test
    void sceneGenerationMapsVisualPromptToWorkerDescription() {
        ComicScene scene = new ComicScene();
        scene.setId(42L);
        scene.setProjectId(5L);
        scene.setName("天台");
        scene.setDescription("白色穹顶");
        ComicSceneVersion version = new ComicSceneVersion();
        version.setId(32L);
        version.setSceneId(42L);
        version.setVisualPrompt("黄昏暖光");
        version.setStatus("FAILED");
        when(sceneMapper.selectById(42L)).thenReturn(scene);
        when(sceneVersionMapper.selectByIdForUpdate(32L)).thenReturn(version);
        when(sceneVersionMapper.markGenerating(32L)).thenReturn(1);
        when(workflowRunMapper.selectById(201L))
                .thenReturn(assetRun(201L, 101L, 5L, "SCENE", 42L, 32L,
                        "comic.scene_reference", "comic-asset-request-32"));

        service.generateScene(
                9L, 5L, 42L, 32L, new ComicDtos.GenerateAssetVersionRequest("request-32")
        );

        ArgumentCaptor<CreateWorkflowRunCommand> command = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().input().path("scene").path("description").asText())
                .contains("白色穹顶", "黄昏暖光");
        assertThat(command.getValue().input().path("operationHandlerKeys").get(0).asText())
                .isEqualTo("comic.scene_reference");
    }

    @Test
    void repeatedClientRequestReturnsTheExistingAssetRunWithoutStartingAnother() {
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(5L);
        character.setName("林澈");
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setId(31L);
        version.setCharacterId(41L);
        version.setVisualPrompt("蓝色长衣");
        version.setStatus("GENERATING");
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.selectByIdForUpdate(31L)).thenReturn(version);
        WorkflowRun existing = assetRun(
                201L, 101L, 5L, "CHARACTER", 41L, 31L,
                "comic.character_reference", "comic-asset-request-31"
        );
        when(workflowRunMapper.selectByUserAndClientRequestId(9L, "comic-asset-request-31"))
                .thenReturn(existing);

        ComicDtos.AssetGenerationDetail result = service.generateCharacter(
                9L, 5L, 41L, 31L, new ComicDtos.GenerateAssetVersionRequest("request-31")
        );

        assertThat(result.workflowRunId()).isEqualTo(201L);
        assertThat(result.rootTaskId()).isEqualTo(101L);
        verify(characterVersionMapper, never()).markGenerating(31L);
        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedClientRequestRejectsAnotherAssetOrHandler() {
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(5L);
        character.setName("林澈");
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setId(31L);
        version.setCharacterId(41L);
        version.setVisualPrompt("蓝色长衣");
        version.setStatus("GENERATING");
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.selectByIdForUpdate(31L)).thenReturn(version);
        when(workflowRunMapper.selectByUserAndClientRequestId(9L, "comic-asset-request-31"))
                .thenReturn(assetRun(
                        201L, 101L, 5L, "CHARACTER", 99L, 31L,
                        "comic.scene_reference", "comic-asset-request-31"
                ));

        assertThatThrownBy(() -> service.generateCharacter(
                9L, 5L, 41L, 31L, new ComicDtos.GenerateAssetVersionRequest("request-31")
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));

        verify(characterVersionMapper, never()).markGenerating(31L);
        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedClientRequestRejectsAnotherLaunchSource() {
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(5L);
        character.setName("林澈");
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setId(31L);
        version.setCharacterId(41L);
        version.setVisualPrompt("蓝色长衣");
        version.setStatus("GENERATING");
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.selectByIdForUpdate(31L)).thenReturn(version);
        WorkflowRun existing = assetRun(
                201L, 101L, 5L, "CHARACTER", 41L, 31L,
                "comic.character_reference", "comic-asset-request-31"
        );
        existing.setLaunchSource("AGENT_CHAT");
        when(workflowRunMapper.selectByUserAndClientRequestId(9L, "comic-asset-request-31"))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.generateCharacter(
                9L, 5L, 41L, 31L, new ComicDtos.GenerateAssetVersionRequest("request-31")
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));

        verify(characterVersionMapper, never()).markGenerating(31L);
        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentRequestKeyBoundToAnotherProjectIsRejectedAfterCreate() {
        ComicCharacter character = new ComicCharacter();
        character.setId(41L);
        character.setProjectId(5L);
        character.setName("林澈");
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setId(31L);
        version.setCharacterId(41L);
        version.setVisualPrompt("蓝色长衣");
        version.setStatus("DRAFT");
        when(characterMapper.selectById(41L)).thenReturn(character);
        when(characterVersionMapper.selectByIdForUpdate(31L)).thenReturn(version);
        when(characterVersionMapper.markGenerating(31L)).thenReturn(1);
        when(workflowRunMapper.selectById(201L))
                .thenReturn(assetRun(201L, 101L, 6L, "CHARACTER", 41L, 99L,
                        "comic.character_reference", "comic-asset-shared-request"));

        assertThatThrownBy(() -> service.generateCharacter(
                9L, 5L, 41L, 31L, new ComicDtos.GenerateAssetVersionRequest("shared-request")
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));

        verify(characterVersionMapper).markGenerating(31L);
        verify(workflowService).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    private WorkflowRun assetRun(Long runId, Long rootTaskId, Long projectId,
                                 String assetType, Long assetId, Long versionId,
                                 String handlerKey, String clientRequestId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setRootTaskId(rootTaskId);
        run.setUserId(9L);
        run.setToolId(71L);
        run.setClientRequestId(clientRequestId);
        run.setLaunchSource("COMIC_PROJECT");
        String assetField = "CHARACTER".equals(assetType) ? "character" : "scene";
        run.setInputJson("{\"comicProjectId\":" + projectId
                + ",\"projectId\":" + projectId
                + ",\"comicAssetType\":\"" + assetType
                + "\",\"comicAssetVersionId\":" + versionId
                + ",\"launchSource\":\"COMIC_PROJECT\""
                + ",\"operationHandlerKeys\":[\"" + handlerKey + "\"]"
                + ",\"" + assetField + "\":{\"assetId\":\"" + assetId
                + "\",\"assetVersionId\":\"" + versionId + "\"}}");
        return run;
    }

    private ComicProjectWorkflowRun binding(Long runId, Long rootTaskId, Long projectId) {
        ComicProjectWorkflowRun binding = new ComicProjectWorkflowRun();
        binding.setUserId(9L);
        binding.setProjectId(projectId);
        binding.setWorkflowRunId(runId);
        binding.setRootTaskId(rootTaskId);
        binding.setLaunchSource("COMIC_PROJECT");
        return binding;
    }
}
