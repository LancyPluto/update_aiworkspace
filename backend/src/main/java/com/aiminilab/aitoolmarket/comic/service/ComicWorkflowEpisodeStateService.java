package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ComicWorkflowEpisodeStateService {
    private final ComicProjectMapper projectMapper;
    private final ComicEpisodeMapper episodeMapper;

    public ComicWorkflowEpisodeStateService(ComicProjectMapper projectMapper,
                                            ComicEpisodeMapper episodeMapper) {
        this.projectMapper = projectMapper;
        this.episodeMapper = episodeMapper;
    }

    @Transactional
    public GenerationSnapshot startScriptGeneration(Long userId, Long projectId,
                                                     ComicDtos.GenerateEpisodeRequest request) {
        ComicProject project = projectMapper.selectOwnedForUpdate(projectId, userId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        int episodeNo = request.episodeNo() == null
                ? episodeMapper.maxEpisodeNo(projectId) + 1
                : request.episodeNo();
        LocalDateTime now = LocalDateTime.now();
        ComicEpisode episode = new ComicEpisode();
        episode.setProjectId(projectId);
        episode.setEpisodeNo(episodeNo);
        episode.setTitle(request.title().trim());
        episode.setScriptSourceType("AI");
        episode.setScriptText("");
        episode.setStatus("SCRIPT_GENERATING");
        episode.setRevision(0L);
        episode.setCreatedAt(now);
        episode.setUpdatedAt(now);
        try {
            episodeMapper.insert(episode);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该集数已经存在");
        }
        return new GenerationSnapshot(project, episode);
    }

    @Transactional
    public GenerationSnapshot startStoryboardGeneration(Long userId, Long projectId, Long episodeId,
                                                         ComicDtos.GenerateStoryboardRequest request) {
        ComicProject project = projectMapper.selectOwnedForUpdate(projectId, userId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        ComicEpisode episode = episodeMapper.selectOwnedForUpdate(projectId, episodeId, userId);
        if (episode == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "漫剧剧集不存在");
        }
        if (!"DRAFT".equals(episode.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只有草稿剧集可以生成分镜");
        }
        if (episode.getScriptText() == null || episode.getScriptText().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请先完成剧本，再生成分镜");
        }
        if (episodeMapper.startStoryboardGeneration(episodeId, request.expectedRevision()) != 1) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "剧集已更新，请刷新后重试");
        }
        episode.setStatus("STORYBOARD_GENERATING");
        episode.setRevision(value(episode.getRevision()) + 1);
        return new GenerationSnapshot(project, episode);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    public record GenerationSnapshot(ComicProject project, ComicEpisode episode) {
    }
}
