package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ComicWorkflowLaunchService {
    private static final String LAUNCH_SOURCE = "COMIC_PROJECT";
    private static final String SCRIPT_HANDLER = "comic.script";
    private static final String STORYBOARD_HANDLER = "comic.storyboard";

    private final ComicWorkflowEpisodeStateService stateService;
    private final ComicProjectService projectService;
    private final ComicProjectMapper projectMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ComicProjectWorkflowRunMapper projectWorkflowRunMapper;
    private final ToolMapper toolMapper;
    private final WorkflowRunApplicationService workflowRunApplicationService;
    private final ObjectMapper objectMapper;

    public ComicWorkflowLaunchService(ComicWorkflowEpisodeStateService stateService,
                                      ComicProjectService projectService,
                                      ComicProjectMapper projectMapper,
                                      WorkflowRunMapper workflowRunMapper,
                                      ComicProjectWorkflowRunMapper projectWorkflowRunMapper,
                                      ToolMapper toolMapper,
                                      WorkflowRunApplicationService workflowRunApplicationService,
                                      ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.projectService = projectService;
        this.projectMapper = projectMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.projectWorkflowRunMapper = projectWorkflowRunMapper;
        this.toolMapper = toolMapper;
        this.workflowRunApplicationService = workflowRunApplicationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComicDtos.EpisodeDetail generateScript(Long userId, Long projectId,
                                                  ComicDtos.GenerateEpisodeRequest request) {
        lockProject(userId, projectId);
        String clientRequestId = clientRequestId("comic-script", request.clientRequestId());
        WorkflowRun existing = workflowRunMapper.selectByUserAndClientRequestId(userId, clientRequestId);
        if (existing != null) {
            return scriptEpisode(existing, userId, projectId, null, clientRequestId, request);
        }

        ComicWorkflowEpisodeStateService.GenerationSnapshot snapshot =
                stateService.startScriptGeneration(userId, projectId, request);
        ComicEpisode episode = snapshot.episode();
        ObjectNode input = baseInput(snapshot.project(), episode);
        input.put("sourceMode", "AI_CREATE");
        input.put("storyTheme", request.title().trim());
        input.put("plotOutline", request.prompt().trim());
        input.put("prompt", request.prompt().trim());
        input.put("episodeDuration", "60");
        input.put("targetShotCount", 12);
        if (request.episodeNo() == null) {
            input.putNull("requestedEpisodeNo");
        } else {
            input.put("requestedEpisodeNo", request.episodeNo());
        }
        operationKeys(input).add(SCRIPT_HANDLER);
        WorkflowRunCreated created = workflowRunApplicationService.createInCurrentTransaction(
                new CreateWorkflowRunCommand(
                        userId,
                        ComicProjectApplicationService.COMIC_TOOL_CODE,
                        input,
                        clientRequestId,
                        LAUNCH_SOURCE,
                        null
                )
        );
        WorkflowRun persisted = requirePersistedRun(created.runId());
        return scriptEpisode(persisted, userId, projectId, episode.getId(), clientRequestId, request);
    }

    @Transactional
    public ComicDtos.EpisodeDetail generateStoryboard(Long userId, Long projectId, Long episodeId,
                                                      ComicDtos.GenerateStoryboardRequest request) {
        lockProject(userId, projectId);
        String clientRequestId = clientRequestId("comic-storyboard", request.clientRequestId());
        WorkflowRun existing = workflowRunMapper.selectByUserAndClientRequestId(userId, clientRequestId);
        if (existing != null) {
            return storyboardEpisode(existing, userId, projectId, episodeId, clientRequestId, request);
        }

        ComicWorkflowEpisodeStateService.GenerationSnapshot snapshot =
                stateService.startStoryboardGeneration(userId, projectId, episodeId, request);
        ComicEpisode episode = snapshot.episode();
        ObjectNode input = baseInput(snapshot.project(), episode);
        input.put("sourceMode", "IMPORT");
        input.put("storyTheme", snapshot.project().getTitle());
        input.put("scriptText", episode.getScriptText());
        input.put("episodeDuration", "60");
        input.put("targetShotCount", 12);
        input.put("expectedRevision", request.expectedRevision());
        operationKeys(input).add(STORYBOARD_HANDLER);
        WorkflowRunCreated created = workflowRunApplicationService.createInCurrentTransaction(
                new CreateWorkflowRunCommand(
                        userId,
                        ComicProjectApplicationService.COMIC_TOOL_CODE,
                        input,
                        clientRequestId,
                        LAUNCH_SOURCE,
                        null
                )
        );
        WorkflowRun persisted = requirePersistedRun(created.runId());
        return storyboardEpisode(persisted, userId, projectId, episodeId, clientRequestId, request);
    }

    private ComicDtos.EpisodeDetail scriptEpisode(WorkflowRun run, Long userId, Long projectId,
                                                   Long expectedEpisodeId, String clientRequestId,
                                                   ComicDtos.GenerateEpisodeRequest request) {
        MatchedLaunch matched = requireSameLaunch(
                run, userId, projectId, expectedEpisodeId, clientRequestId, SCRIPT_HANDLER
        );
        JsonNode input = matched.input();
        requireText(input, "sourceMode", "AI_CREATE");
        requireText(input, "storyTheme", request.title().trim());
        requireText(input, "title", request.title().trim());
        requireText(input, "plotOutline", request.prompt().trim());
        requireText(input, "prompt", request.prompt().trim());
        JsonNode requestedEpisodeNo = input.get("requestedEpisodeNo");
        if (request.episodeNo() == null) {
            if (requestedEpisodeNo == null || !requestedEpisodeNo.isNull()) {
                throw conflict("clientRequestId 已用于其他剧本生成请求");
            }
        } else if (requestedEpisodeNo == null
                || !requestedEpisodeNo.isIntegralNumber()
                || requestedEpisodeNo.asInt() != request.episodeNo()) {
            throw conflict("clientRequestId 已用于其他剧本生成请求");
        }
        return projectService.episodeDetail(userId, projectId, matched.binding().getEpisodeId());
    }

    private ComicDtos.EpisodeDetail storyboardEpisode(WorkflowRun run, Long userId, Long projectId,
                                                       Long episodeId, String clientRequestId,
                                                       ComicDtos.GenerateStoryboardRequest request) {
        MatchedLaunch matched = requireSameLaunch(
                run, userId, projectId, episodeId, clientRequestId, STORYBOARD_HANDLER
        );
        JsonNode input = matched.input();
        requireText(input, "sourceMode", "IMPORT");
        if (!input.has("expectedRevision")
                || !input.get("expectedRevision").isIntegralNumber()
                || input.get("expectedRevision").asLong() != request.expectedRevision()) {
            throw conflict("clientRequestId 已用于其他分镜生成请求");
        }
        return projectService.episodeDetail(userId, projectId, matched.binding().getEpisodeId());
    }

    private MatchedLaunch requireSameLaunch(WorkflowRun run, Long userId, Long projectId,
                                            Long expectedEpisodeId, String clientRequestId,
                                            String handlerKey) {
        AiTool tool = toolMapper.findAnyByCode(ComicProjectApplicationService.COMIC_TOOL_CODE)
                .orElse(null);
        if (tool == null
                || !Objects.equals(run.getToolId(), tool.getId())
                || !Objects.equals(run.getUserId(), userId)
                || !Objects.equals(run.getClientRequestId(), clientRequestId)
                || !LAUNCH_SOURCE.equals(run.getLaunchSource())) {
            throw conflict("clientRequestId 已用于其他工作流请求");
        }
        ComicProjectWorkflowRun binding =
                projectWorkflowRunMapper.selectByWorkflowRunInternal(run.getId());
        if (binding == null
                || !Objects.equals(binding.getUserId(), userId)
                || !Objects.equals(binding.getProjectId(), projectId)
                || !Objects.equals(binding.getWorkflowRunId(), run.getId())
                || !Objects.equals(binding.getRootTaskId(), run.getRootTaskId())
                || binding.getEpisodeId() == null
                || (expectedEpisodeId != null
                && !Objects.equals(binding.getEpisodeId(), expectedEpisodeId))) {
            throw conflict("clientRequestId 已用于其他漫剧项目或剧集");
        }
        JsonNode input = readInput(run);
        JsonNode operationHandlerKeys = input.path("operationHandlerKeys");
        if (input.path("comicProjectId").asLong() != projectId
                || input.path("projectId").asLong() != projectId
                || input.path("comicEpisodeId").asLong() != binding.getEpisodeId()
                || input.path("episodeId").asLong() != binding.getEpisodeId()
                || !operationHandlerKeys.isArray()
                || operationHandlerKeys.size() != 1
                || !handlerKey.equals(operationHandlerKeys.get(0).asText())) {
            throw conflict("clientRequestId 已用于其他漫剧工作流操作");
        }
        return new MatchedLaunch(binding, input);
    }

    private WorkflowRun requirePersistedRun(Long runId) {
        WorkflowRun run = workflowRunMapper.selectById(runId);
        if (run == null) {
            throw conflict("工作流运行创建后无法读取");
        }
        return run;
    }

    private JsonNode readInput(WorkflowRun run) {
        try {
            JsonNode input = objectMapper.readTree(run.getInputJson());
            if (input == null || !input.isObject()) {
                throw conflict("clientRequestId 已用于无法识别的工作流");
            }
            return input;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw conflict("clientRequestId 已用于无法识别的工作流");
        }
    }

    private void requireText(JsonNode input, String field, String expected) {
        if (!expected.equals(input.path(field).asText())) {
            throw conflict("clientRequestId 已用于其他工作流输入");
        }
    }

    private ComicProject lockProject(Long userId, Long projectId) {
        ComicProject project = projectMapper.selectOwnedForUpdate(projectId, userId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        return project;
    }

    private ObjectNode baseInput(ComicProject project, ComicEpisode episode) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("comicProjectId", project.getId());
        input.put("comicEpisodeId", episode.getId());
        input.put("projectId", project.getId());
        input.put("episodeId", episode.getId());
        input.put("episodeNo", episode.getEpisodeNo());
        input.put("title", episode.getTitle());
        input.put("episodeTitle", episode.getTitle());
        input.put("aspectRatio", project.getAspectRatio());
        if (project.getVisualStyle() != null && !project.getVisualStyle().isBlank()) {
            input.put("visualStyle", project.getVisualStyle());
        }
        return input;
    }

    private ArrayNode operationKeys(ObjectNode input) {
        return input.putArray("operationHandlerKeys");
    }

    private String clientRequestId(String prefix, String clientRequestId) {
        return prefix + "-" + clientRequestId.trim();
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }

    private record MatchedLaunch(ComicProjectWorkflowRun binding, JsonNode input) {
    }
}
