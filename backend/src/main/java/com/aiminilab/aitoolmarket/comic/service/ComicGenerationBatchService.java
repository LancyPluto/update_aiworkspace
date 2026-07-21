package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicGenerationBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicGenerationBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ComicGenerationBatchService {
    private static final String DEFAULT_TOOL_CODE = ComicProjectApplicationService.COMIC_TOOL_CODE;

    private final ComicGenerationBatchMapper batchMapper;
    private final ComicShotAttemptMapper attemptMapper;
    private final ComicShotMapper shotMapper;
    private final ComicEpisodeMapper episodeMapper;
    private final ComicProjectService projectService;
    private final ObjectMapper objectMapper;

    public ComicGenerationBatchService(ComicGenerationBatchMapper batchMapper,
                                       ComicShotAttemptMapper attemptMapper,
                                       ComicShotMapper shotMapper,
                                       ComicEpisodeMapper episodeMapper,
                                       ComicProjectService projectService,
                                       ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.shotMapper = shotMapper;
        this.episodeMapper = episodeMapper;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComicDtos.BatchDetail create(Long userId, Long projectId, Long episodeId,
                                         ComicDtos.CreateBatchRequest request) {
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("请确认参考素材和预计费用后再开始生成");
        }
        String clientRequestId = request.clientRequestId().trim();
        ComicGenerationBatch existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, request.toolCode(), request);
            return detail(existing);
        }
        ComicEpisode episode = episodeMapper.selectOwnedForUpdate(projectId, episodeId, userId);
        if (episode == null) throw notFound("漫剧剧集不存在");
        existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, request.toolCode(), request);
            return detail(existing);
        }
        if (!Set.of("ASSETS_CONFIRMED", "GENERATING").contains(episode.getStatus())) {
            throw invalid("必须先锁定分镜并确认角色与场景素材");
        }
        if (batchMapper.countActiveByEpisode(episodeId) > 0) {
            throw conflict("该剧集已有生成批次正在运行");
        }
        List<ComicShot> shots = selectShots(episodeId, request.shotIds());
        if (shots.isEmpty()) throw invalid("请选择至少一个分镜");

        ComicGenerationBatch batch = new ComicGenerationBatch();
        batch.setUserId(userId);
        batch.setProjectId(projectId);
        batch.setEpisodeId(episodeId);
        batch.setBatchType("SHOT_VIDEO");
        batch.setToolCode(defaultValue(request.toolCode(), DEFAULT_TOOL_CODE));
        batch.setClientRequestId(clientRequestId);
        batch.setMaxParallelism(request.maxParallelism() == null ? 4 : request.maxParallelism());
        batch.setEstimatedCredits(request.estimatedCredits());
        batch.setStatus("CREATED");
        batch.setRequestJson(writeJson(request));
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException duplicate) {
            ComicGenerationBatch concurrent = batchMapper.selectByRequest(userId, clientRequestId);
            if (concurrent == null) throw duplicate;
            ensureSameRequest(concurrent, projectId, episodeId, request.toolCode(), request);
            return detail(concurrent);
        }
        for (ComicShot shot : shots) {
            insertAttempt(batch.getId(), shot.getId(), attemptMapper.maxAttemptNo(shot.getId()) + 1);
        }
        episodeMapper.markGenerating(episodeId);
        return detail(batch);
    }

    @Transactional
    public ComicDtos.BatchDetail createRetry(Long userId, Long projectId, Long episodeId, Long shotId,
                                              ComicDtos.RetryShotRequest request) {
        String clientRequestId = request.clientRequestId().trim();
        ComicGenerationBatch existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, request.toolCode(), request);
            return detail(existing);
        }
        ComicEpisode episode = episodeMapper.selectOwnedForUpdate(projectId, episodeId, userId);
        if (episode == null) throw notFound("漫剧剧集不存在");
        existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, request.toolCode(), request);
            return detail(existing);
        }
        if (!Set.of("ASSETS_CONFIRMED", "GENERATING", "COMPLETED").contains(episode.getStatus())) {
            throw invalid("当前阶段不能重新生成分镜");
        }
        if (batchMapper.countActiveByEpisode(episodeId) > 0) {
            throw conflict("该剧集已有生成批次正在运行");
        }
        ComicShot shot = shotMapper.selectInEpisode(shotId, episodeId);
        if (shot == null) throw notFound("分镜不存在");
        if (attemptMapper.countActiveByShot(shotId) > 0) throw conflict("该分镜已有生成任务正在运行");

        ComicGenerationBatch batch = new ComicGenerationBatch();
        batch.setUserId(userId);
        batch.setProjectId(projectId);
        batch.setEpisodeId(episodeId);
        batch.setBatchType("SHOT_RETRY");
        batch.setToolCode(defaultValue(request.toolCode(), DEFAULT_TOOL_CODE));
        batch.setClientRequestId(clientRequestId);
        batch.setMaxParallelism(1);
        batch.setStatus("CREATED");
        batch.setRequestJson(writeJson(request));
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException duplicate) {
            ComicGenerationBatch concurrent = batchMapper.selectByRequest(userId, clientRequestId);
            if (concurrent == null) throw duplicate;
            ensureSameRequest(concurrent, projectId, episodeId, request.toolCode(), request);
            return detail(concurrent);
        }
        insertAttempt(batch.getId(), shotId, attemptMapper.maxAttemptNo(shotId) + 1);
        episodeMapper.markGenerating(episodeId);
        return detail(batch);
    }

    public ComicDtos.BatchDetail get(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicGenerationBatch batch = requireOwned(userId, projectId, episodeId, batchId);
        return detail(batch);
    }

    public ComicGenerationBatch requireLatest(Long userId, Long projectId, Long episodeId) {
        projectService.requireEpisode(userId, projectId, episodeId);
        ComicGenerationBatch batch = batchMapper.selectLatest(userId, projectId, episodeId);
        if (batch == null) throw notFound("该剧集还没有生成批次");
        return batch;
    }

    ComicGenerationBatch requireOwned(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicGenerationBatch batch = batchMapper.selectOwned(batchId, userId);
        if (batch == null || !projectId.equals(batch.getProjectId()) || !episodeId.equals(batch.getEpisodeId())) {
            throw notFound("生成批次不存在");
        }
        return batch;
    }

    ComicDtos.BatchDetail detail(ComicGenerationBatch batch) {
        List<ComicShotAttempt> attempts = attemptMapper.selectByBatch(batch.getId());
        int pending = 0;
        int running = 0;
        int success = 0;
        int failed = 0;
        for (ComicShotAttempt attempt : attempts) {
            switch (attempt.getStatus()) {
                case "PENDING", "DISPATCHING" -> pending++;
                case "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING" -> running++;
                case "SUCCESS" -> success++;
                case "FAILED", "TIMEOUT", "CANCELLED" -> failed++;
                default -> running++;
            }
        }
        return new ComicDtos.BatchDetail(
                batch.getId(), batch.getProjectId(), batch.getEpisodeId(), batch.getBatchType(),
                batch.getToolCode(), batch.getClientRequestId(), batch.getMaxParallelism(),
                batch.getEstimatedCredits(), batch.getStatus(), attempts.size(), pending, running, success, failed,
                attempts.stream().map(this::toAttempt).toList(), batch.getConfirmedAt(), batch.getStartedAt(),
                batch.getFinishedAt(), batch.getCreatedAt()
        );
    }

    private ComicDtos.ShotAttemptDetail toAttempt(ComicShotAttempt attempt) {
        return new ComicDtos.ShotAttemptDetail(
                attempt.getId(), attempt.getShotId(), attempt.getAttemptNo(), attempt.getStatus(),
                attempt.getWorkflowRunId(), attempt.getRootTaskId(), readJson(attempt.getResultJson()),
                attempt.getErrorCode(), attempt.getErrorMessage(), attempt.getStartedAt(), attempt.getFinishedAt()
        );
    }

    private List<ComicShot> selectShots(Long episodeId, List<Long> requestedIds) {
        List<ComicShot> all = shotMapper.selectByEpisode(episodeId);
        if (requestedIds == null || requestedIds.isEmpty()) return all;
        Set<Long> requested = new HashSet<>(requestedIds);
        List<ComicShot> selected = all.stream().filter(shot -> requested.contains(shot.getId())).toList();
        if (selected.size() != requested.size()) throw invalid("部分分镜不属于当前剧集");
        return selected;
    }

    private void insertAttempt(Long batchId, Long shotId, int attemptNo) {
        ComicShotAttempt attempt = new ComicShotAttempt();
        attempt.setBatchId(batchId);
        attempt.setShotId(shotId);
        attempt.setAttemptNo(attemptNo);
        attempt.setIdempotencyKey("comic:batch:%d:shot:%d:attempt:%d".formatted(batchId, shotId, attemptNo));
        attempt.setStatus("PENDING");
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        attemptMapper.insert(attempt);
    }

    private void ensureSameRequest(ComicGenerationBatch batch, Long projectId, Long episodeId,
                                   String toolCode, Object request) {
        String expectedTool = defaultValue(toolCode, DEFAULT_TOOL_CODE);
        if (!projectId.equals(batch.getProjectId()) || !episodeId.equals(batch.getEpisodeId())
                || !expectedTool.equals(batch.getToolCode()) || !sameJson(batch.getRequestJson(), request)) {
            throw conflict("clientRequestId 已用于其他生成请求");
        }
    }

    private boolean sameJson(String stored, Object request) {
        try {
            return objectMapper.readTree(stored).equals(objectMapper.valueToTree(request));
        } catch (Exception exception) {
            return false;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize comic batch request", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("raw", value);
        }
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }
}
