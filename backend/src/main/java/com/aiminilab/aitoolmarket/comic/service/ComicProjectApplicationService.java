package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ComicProjectApplicationService {
    public static final String COMIC_TOOL_CODE = "ai_comic_drama_agent";

    private final ComicProjectMapper projectMapper;
    private final ComicEpisodeMapper episodeMapper;
    private final ComicShotMapper shotMapper;
    private final ComicProjectWorkflowRunMapper workflowRunMapper;
    private final ObjectMapper objectMapper;

    public ComicProjectApplicationService(ComicProjectMapper projectMapper,
                                          ComicEpisodeMapper episodeMapper,
                                          ComicShotMapper shotMapper,
                                          ComicProjectWorkflowRunMapper workflowRunMapper,
                                          ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.episodeMapper = episodeMapper;
        this.shotMapper = shotMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedComicRun prepare(Long userId, JsonNode input, String launchSource) {
        ObjectNode enriched = input != null && input.isObject()
                ? ((ObjectNode) input).deepCopy()
                : objectMapper.createObjectNode();
        Long projectId = longOrNull(enriched, "comicProjectId");
        Long episodeId = longOrNull(enriched, "comicEpisodeId");
        Long shotId = longOrNull(enriched, "comicShotId");

        ComicProject project;
        ComicEpisode episode = null;
        if (projectId == null) {
            project = createFromWorkflow(userId, enriched);
            projectId = project.getId();
            episode = createInitialEpisode(project, enriched);
            episodeId = episode.getId();
        } else {
            project = requireOwnedProject(projectId, userId);
            if (episodeId != null) {
                episode = episodeMapper.selectOwned(projectId, episodeId, userId);
                if (episode == null) {
                    throw notFound("漫剧剧集不存在");
                }
            }
        }
        if (shotId != null) {
            if (episode == null) {
                throw invalid("comicShotId 必须与 comicEpisodeId 一起提交");
            }
            ComicShot shot = shotMapper.selectInEpisode(shotId, episode.getId());
            if (shot == null) {
                throw notFound("漫剧分镜不存在");
            }
        }

        enriched.put("comicProjectId", projectId);
        if (episodeId != null) {
            enriched.put("comicEpisodeId", episodeId);
        }
        if (shotId != null) {
            enriched.put("comicShotId", shotId);
        }
        enriched.put("workspacePath", workspacePath(projectId, episodeId));
        enriched.put("launchSource", launchSource == null ? "UNKNOWN" : launchSource);
        return new PreparedComicRun(projectId, episodeId, shotId, enriched);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(PreparedComicRun prepared, Long userId, Long rootTaskId, Long workflowRunId,
                     String launchSource) {
        ComicProjectWorkflowRun existing = workflowRunMapper.selectByRootTaskInternal(rootTaskId);
        if (existing != null) {
            if (!existing.getProjectId().equals(prepared.projectId())
                    || !existing.getWorkflowRunId().equals(workflowRunId)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "工作流运行已绑定到其他漫剧项目");
            }
            return;
        }
        ComicProjectWorkflowRun binding = new ComicProjectWorkflowRun();
        binding.setUserId(userId);
        binding.setProjectId(prepared.projectId());
        binding.setEpisodeId(prepared.episodeId());
        binding.setShotId(prepared.shotId());
        binding.setWorkflowRunId(workflowRunId);
        binding.setRootTaskId(rootTaskId);
        binding.setLaunchSource(launchSource);
        binding.setCreatedAt(LocalDateTime.now());
        workflowRunMapper.insert(binding);
    }

    public ComicDtos.WorkspaceBinding workspaceByRootTaskId(Long rootTaskId, Long userId) {
        ComicProjectWorkflowRun binding = workflowRunMapper.selectByRootTask(rootTaskId, userId);
        if (binding == null) {
            throw notFound("该任务没有关联漫剧工作台");
        }
        return toWorkspace(binding);
    }

    public ComicDtos.WorkspaceBinding workspaceByRootTaskIdInternal(Long rootTaskId) {
        ComicProjectWorkflowRun binding = workflowRunMapper.selectByRootTaskInternal(rootTaskId);
        return binding == null ? null : toWorkspace(binding);
    }

    private ComicDtos.WorkspaceBinding toWorkspace(ComicProjectWorkflowRun binding) {
        return new ComicDtos.WorkspaceBinding(
                binding.getProjectId(), binding.getEpisodeId(), binding.getShotId(),
                binding.getRootTaskId(), binding.getWorkflowRunId(), binding.getLaunchSource(),
                workspacePath(binding.getProjectId(), binding.getEpisodeId())
        );
    }

    private ComicProject createFromWorkflow(Long userId, ObjectNode input) {
        LocalDateTime now = LocalDateTime.now();
        ComicProject project = new ComicProject();
        project.setUserId(userId);
        project.setTitle(firstText(input, "title", "storyTheme", "prompt", "未命名 AI 漫剧"));
        project.setDescription(text(input, "synopsis"));
        project.setAspectRatio(defaultText(input, "aspectRatio", "16:9"));
        project.setVisualStyle(firstText(input, "visualStyle", "style", null));
        project.setStatus("ACTIVE");
        project.setRevision(0L);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);
        return project;
    }

    private ComicEpisode createInitialEpisode(ComicProject project, ObjectNode input) {
        LocalDateTime now = LocalDateTime.now();
        ComicEpisode episode = new ComicEpisode();
        episode.setProjectId(project.getId());
        episode.setEpisodeNo(1);
        episode.setTitle(firstText(input, "episodeTitle", "title", "第 1 集"));
        episode.setScriptSourceType("AI");
        episode.setScriptText(firstFullText(input, "scriptText", "plotOutline", "prompt", "待 AI 生成剧本"));
        episode.setStatus("DRAFT");
        episode.setRevision(0L);
        episode.setCreatedAt(now);
        episode.setUpdatedAt(now);
        episodeMapper.insert(episode);
        return episode;
    }

    private ComicProject requireOwnedProject(Long projectId, Long userId) {
        ComicProject project = projectMapper.selectOwned(projectId, userId);
        if (project == null) {
            throw notFound("漫剧项目不存在");
        }
        return project;
    }

    private String workspacePath(Long projectId, Long episodeId) {
        String path = "/agents/comic-projects/" + projectId;
        return episodeId == null ? path : path + "?episode=" + episodeId;
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw invalid(field + " 必须是正整数");
        }
        return value.asLong();
    }

    private String firstText(JsonNode node, String first, String second, String fallback) {
        String value = text(node, first);
        if (value == null && second != null) {
            value = text(node, second);
        }
        if (value == null) {
            value = fallback;
        }
        return value == null ? null : limit(value, 255);
    }

    private String firstText(JsonNode node, String first, String second, String third, String fallback) {
        String value = text(node, first);
        if (value == null) value = text(node, second);
        if (value == null) value = text(node, third);
        if (value == null) value = fallback;
        return value == null ? null : limit(value, 255);
    }

    private String defaultText(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    private String firstFullText(JsonNode node, String first, String second, String third, String fallback) {
        String value = text(node, first);
        if (value == null) value = text(node, second);
        if (value == null) value = text(node, third);
        return value == null ? fallback : limit(value, 500_000);
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) return null;
        String value = node.get(field).asText().trim();
        return value.isBlank() ? null : value;
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    public record PreparedComicRun(Long projectId, Long episodeId, Long shotId, ObjectNode input) {
    }
}
