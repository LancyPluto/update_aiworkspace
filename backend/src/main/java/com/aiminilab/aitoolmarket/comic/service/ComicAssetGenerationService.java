package com.aiminilab.aitoolmarket.comic.service;

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
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ComicAssetGenerationService {
    private static final String CHARACTER = "CHARACTER";
    private static final String SCENE = "SCENE";
    private static final String LAUNCH_SOURCE = "COMIC_PROJECT";
    private static final String CHARACTER_HANDLER = "comic.character_reference";
    private static final String SCENE_HANDLER = "comic.scene_reference";

    private final ComicProjectMapper projectMapper;
    private final ComicCharacterMapper characterMapper;
    private final ComicCharacterVersionMapper characterVersionMapper;
    private final ComicSceneMapper sceneMapper;
    private final ComicSceneVersionMapper sceneVersionMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ComicProjectWorkflowRunMapper projectWorkflowRunMapper;
    private final ToolMapper toolMapper;
    private final WorkflowRunApplicationService workflowRunApplicationService;
    private final ObjectMapper objectMapper;

    public ComicAssetGenerationService(ComicProjectMapper projectMapper,
                                       ComicCharacterMapper characterMapper,
                                       ComicCharacterVersionMapper characterVersionMapper,
                                       ComicSceneMapper sceneMapper,
                                       ComicSceneVersionMapper sceneVersionMapper,
                                       WorkflowRunMapper workflowRunMapper,
                                       ComicProjectWorkflowRunMapper projectWorkflowRunMapper,
                                       ToolMapper toolMapper,
                                       WorkflowRunApplicationService workflowRunApplicationService,
                                       ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.characterMapper = characterMapper;
        this.characterVersionMapper = characterVersionMapper;
        this.sceneMapper = sceneMapper;
        this.sceneVersionMapper = sceneVersionMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.projectWorkflowRunMapper = projectWorkflowRunMapper;
        this.toolMapper = toolMapper;
        this.workflowRunApplicationService = workflowRunApplicationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComicDtos.AssetGenerationDetail generateCharacter(Long userId, Long projectId, Long characterId,
                                                              Long versionId,
                                                              ComicDtos.GenerateAssetVersionRequest request) {
        ComicProject project = requireProject(userId, projectId);
        ComicCharacter character = characterMapper.selectById(characterId);
        ComicCharacterVersion version = characterVersionMapper.selectByIdForUpdate(versionId);
        if (character == null || !projectId.equals(character.getProjectId())
                || version == null || !characterId.equals(version.getCharacterId())) {
            throw notFound("角色版本不存在");
        }
        ObjectNode input = baseInput(project, CHARACTER, versionId, CHARACTER_HANDLER);
        ObjectNode asset = input.putObject("character");
        asset.put("assetId", String.valueOf(characterId));
        asset.put("assetVersionId", String.valueOf(versionId));
        asset.put("name", character.getName());
        asset.put("appearance", joinDescription(character.getDescription(), version.getVisualPrompt()));
        return launch(userId, projectId, characterId, versionId, version.getStatus(), CHARACTER,
                CHARACTER_HANDLER, request, input,
                () -> characterVersionMapper.markGenerating(versionId));
    }

    @Transactional
    public ComicDtos.AssetGenerationDetail generateScene(Long userId, Long projectId, Long sceneId,
                                                          Long versionId,
                                                          ComicDtos.GenerateAssetVersionRequest request) {
        ComicProject project = requireProject(userId, projectId);
        ComicScene scene = sceneMapper.selectById(sceneId);
        ComicSceneVersion version = sceneVersionMapper.selectByIdForUpdate(versionId);
        if (scene == null || !projectId.equals(scene.getProjectId())
                || version == null || !sceneId.equals(version.getSceneId())) {
            throw notFound("场景版本不存在");
        }
        ObjectNode input = baseInput(project, SCENE, versionId, SCENE_HANDLER);
        ObjectNode asset = input.putObject("scene");
        asset.put("assetId", String.valueOf(sceneId));
        asset.put("assetVersionId", String.valueOf(versionId));
        asset.put("name", scene.getName());
        asset.put("description", joinDescription(scene.getDescription(), version.getVisualPrompt()));
        return launch(userId, projectId, sceneId, versionId, version.getStatus(), SCENE,
                SCENE_HANDLER, request, input,
                () -> sceneVersionMapper.markGenerating(versionId));
    }

    private ComicDtos.AssetGenerationDetail launch(Long userId, Long projectId, Long assetId, Long versionId,
                                                    String currentStatus,
                                                    String assetType,
                                                    String handlerKey,
                                                    ComicDtos.GenerateAssetVersionRequest request,
                                                    ObjectNode input,
                                                    StatusTransition transition) {
        String clientRequestId = "comic-asset-" + request.clientRequestId().trim();
        WorkflowRun existing = workflowRunMapper.selectByUserAndClientRequestId(userId, clientRequestId);
        if (existing != null) {
            requireSameAsset(existing, userId, projectId, assetType, assetId, versionId,
                    handlerKey, clientRequestId);
            return new ComicDtos.AssetGenerationDetail(
                    versionId, currentStatus, existing.getId(), existing.getRootTaskId()
            );
        }
        if (!"DRAFT".equals(currentStatus) && !"FAILED".equals(currentStatus)) {
            throw conflict("该素材版本已在生成或已经可用");
        }
        if (transition.markGenerating() != 1) {
            throw conflict("素材版本状态已发生变化，请刷新后重试");
        }
        WorkflowRunCreated created = workflowRunApplicationService.createInCurrentTransaction(
                new CreateWorkflowRunCommand(
                        userId,
                        ComicProjectApplicationService.COMIC_TOOL_CODE,
                        input,
                        clientRequestId,
                        "COMIC_PROJECT",
                        null
                )
        );
        WorkflowRun persisted = workflowRunMapper.selectById(created.runId());
        if (persisted == null) {
            throw conflict("工作流运行创建后无法读取");
        }
        requireSameAsset(persisted, userId, projectId, assetType, assetId, versionId,
                handlerKey, clientRequestId);
        return new ComicDtos.AssetGenerationDetail(
                versionId, "GENERATING", persisted.getId(), persisted.getRootTaskId()
        );
    }

    private ObjectNode baseInput(ComicProject project, String assetType, Long versionId, String handlerKey) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("sourceMode", "IMPORT");
        input.put("storyTheme", project.getTitle());
        input.put("comicProjectId", project.getId());
        input.put("projectId", project.getId());
        input.put("comicAssetType", assetType);
        input.put("comicAssetVersionId", versionId);
        input.put("aspectRatio", project.getAspectRatio());
        if (project.getVisualStyle() != null && !project.getVisualStyle().isBlank()) {
            input.put("visualStyle", project.getVisualStyle());
        }
        input.putArray("operationHandlerKeys").add(handlerKey);
        return input;
    }

    private ComicProject requireProject(Long userId, Long projectId) {
        ComicProject project = projectMapper.selectOwnedForUpdate(projectId, userId);
        if (project == null) throw notFound("漫剧项目不存在");
        return project;
    }

    private void requireSameAsset(WorkflowRun run, Long userId, Long projectId, String assetType,
                                  Long assetId, Long versionId, String handlerKey,
                                  String clientRequestId) {
        AiTool tool = toolMapper.findAnyByCode(ComicProjectApplicationService.COMIC_TOOL_CODE)
                .orElse(null);
        if (tool == null
                || !Objects.equals(run.getToolId(), tool.getId())
                || !Objects.equals(run.getUserId(), userId)
                || !Objects.equals(run.getClientRequestId(), clientRequestId)
                || !LAUNCH_SOURCE.equals(run.getLaunchSource())) {
            throw conflict("clientRequestId belongs to another workflow request");
        }
        ComicProjectWorkflowRun binding = projectWorkflowRunMapper.selectByWorkflowRunInternal(run.getId());
        if (binding == null
                || !Objects.equals(binding.getUserId(), userId)
                || !Objects.equals(binding.getProjectId(), projectId)
                || !Objects.equals(binding.getWorkflowRunId(), run.getId())
                || !Objects.equals(binding.getRootTaskId(), run.getRootTaskId())
                || !LAUNCH_SOURCE.equals(binding.getLaunchSource())
                || binding.getEpisodeId() != null
                || binding.getShotId() != null) {
            throw conflict("clientRequestId belongs to another comic project or asset run");
        }
        JsonNode input;
        try {
            input = objectMapper.readTree(run.getInputJson());
        } catch (Exception exception) {
            throw conflict("clientRequestId 已用于无法识别的工作流");
        }
        String assetField = CHARACTER.equals(assetType) ? "character" : "scene";
        JsonNode asset = input == null ? null : input.path(assetField);
        JsonNode operationHandlerKeys = input == null ? null : input.path("operationHandlerKeys");
        if (input == null
                || input.path("comicProjectId").asLong() != projectId
                || input.path("projectId").asLong() != projectId
                || !assetType.equalsIgnoreCase(input.path("comicAssetType").asText())
                || input.path("comicAssetVersionId").asLong() != versionId
                || !LAUNCH_SOURCE.equals(input.path("launchSource").asText())
                || operationHandlerKeys == null
                || !operationHandlerKeys.isArray()
                || operationHandlerKeys.size() != 1
                || !handlerKey.equals(operationHandlerKeys.path(0).asText())
                || asset == null
                || !asset.isObject()
                || !String.valueOf(assetId).equals(asset.path("assetId").asText())
                || !String.valueOf(versionId).equals(asset.path("assetVersionId").asText())) {
            throw conflict("clientRequestId 已用于其他素材生成请求");
        }
    }

    private String joinDescription(String description, String visualPrompt) {
        if (description == null || description.isBlank()) return visualPrompt.trim();
        return description.trim() + "\n" + visualPrompt.trim();
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }

    @FunctionalInterface
    private interface StatusTransition {
        int markGenerating();
    }
}
