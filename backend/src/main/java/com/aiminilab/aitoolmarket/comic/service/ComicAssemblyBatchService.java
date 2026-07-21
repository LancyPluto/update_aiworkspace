package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicAssemblyBatch;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicAssemblyBatchMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ComicAssemblyBatchService {
    private static final Logger LOG = LoggerFactory.getLogger(ComicAssemblyBatchService.class);
    private static final String COMPOSE_HANDLER_KEY = "comic.compose";
    private static final String DEFAULT_TOOL_CODE = ComicProjectApplicationService.COMIC_TOOL_CODE;
    private static final Set<String> ELIGIBLE_EPISODE_STATUSES = Set.of(
            "ASSETS_CONFIRMED", "GENERATING", "COMPLETED"
    );
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "CREATING", "RUNNING", "AWAITING_USER", "AWAITING_FUNDS", "CANCELLING"
    );
    private static final Set<String> FAILED_RUN_STATUSES = Set.of("FAILED", "TIMEOUT", "CANCELLED");

    private final ComicAssemblyBatchMapper batchMapper;
    private final ComicEpisodeMapper episodeMapper;
    private final ComicShotMapper shotMapper;
    private final ComicShotAttemptMapper attemptMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowRunStepMapper workflowRunStepMapper;
    private final WorkflowRunApplicationService workflowRunApplicationService;
    private final ObjectMapper objectMapper;

    public ComicAssemblyBatchService(ComicAssemblyBatchMapper batchMapper,
                                     ComicEpisodeMapper episodeMapper,
                                     ComicShotMapper shotMapper,
                                     ComicShotAttemptMapper attemptMapper,
                                     WorkflowRunMapper workflowRunMapper,
                                     WorkflowRunStepMapper workflowRunStepMapper,
                                     WorkflowRunApplicationService workflowRunApplicationService,
                                     ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.episodeMapper = episodeMapper;
        this.shotMapper = shotMapper;
        this.attemptMapper = attemptMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowRunStepMapper = workflowRunStepMapper;
        this.workflowRunApplicationService = workflowRunApplicationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComicDtos.AssemblyBatchDetail create(Long userId, Long projectId, Long episodeId,
                                                ComicDtos.CreateAssemblyBatchRequest request) {
        validateCreateRequest(request);
        String clientRequestId = request.clientRequestId().trim();
        String toolCode = DEFAULT_TOOL_CODE;
        ComicAssemblyBatch existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, toolCode);
            return toDetail(reconcile(existing));
        }

        ComicEpisode episode = episodeMapper.selectOwnedForUpdate(projectId, episodeId, userId);
        if (episode == null) {
            throw notFound("漫剧剧集不存在");
        }
        if (!ELIGIBLE_EPISODE_STATUSES.contains(episode.getStatus())) {
            throw invalid("请先完成分镜生成并选定每个镜头的版本");
        }
        existing = batchMapper.selectByRequest(userId, clientRequestId);
        if (existing != null) {
            ensureSameRequest(existing, projectId, episodeId, toolCode);
            return toDetail(reconcile(existing));
        }
        ComicAssemblyBatch active = batchMapper.selectActiveByEpisode(episodeId);
        if (active != null) {
            throw conflict("该剧集已有最终合成任务正在运行");
        }

        List<ComicShot> shots = shotMapper.selectByEpisode(episodeId);
        if (shots.isEmpty()) {
            throw invalid("剧集没有可合成的分镜");
        }
        ArrayNode selectedShots = buildSelectedShots(shots);
        LocalDateTime now = LocalDateTime.now();
        ComicAssemblyBatch batch = new ComicAssemblyBatch();
        batch.setUserId(userId);
        batch.setProjectId(projectId);
        batch.setEpisodeId(episodeId);
        batch.setToolCode(toolCode);
        batch.setClientRequestId(clientRequestId);
        batch.setShotCount(selectedShots.size());
        batch.setStatus("CREATING");
        batch.setSelectedShotsJson(writeJson(selectedShots));
        batch.setConfirmedAt(now);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException duplicate) {
            ComicAssemblyBatch concurrent = batchMapper.selectByRequest(userId, clientRequestId);
            if (concurrent == null) {
                throw duplicate;
            }
            ensureSameRequest(concurrent, projectId, episodeId, toolCode);
            return toDetail(reconcile(concurrent));
        }

        ObjectNode input = buildComposeInput(batch, episode, selectedShots);
        WorkflowRunCreated created = workflowRunApplicationService.createInCurrentTransaction(
                new CreateWorkflowRunCommand(
                        userId,
                        toolCode,
                        input,
                        "comic:assembly:" + batch.getId(),
                        "COMIC_PROJECT",
                        null
                )
        );
        String initialStatus = activeRunStatus(created.status());
        if (batchMapper.bindWorkflow(
                batch.getId(), created.runId(), created.rootTaskId(), initialStatus, now
        ) != 1) {
            throw new IllegalStateException("Comic assembly batch lost creation ownership");
        }
        batch.setWorkflowRunId(created.runId());
        batch.setRootTaskId(created.rootTaskId());
        batch.setStatus(initialStatus);
        batch.setStartedAt(now);
        return toDetail(reconcile(batch));
    }

    @Transactional
    public ComicDtos.AssemblyBatchDetail get(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicAssemblyBatch batch = requireOwned(userId, projectId, episodeId, batchId);
        return toDetail(reconcile(batch));
    }

    @Transactional
    public ComicDtos.AssemblyBatchDetail latest(Long userId, Long projectId, Long episodeId) {
        if (episodeMapper.selectOwned(projectId, episodeId, userId) == null) {
            throw notFound("漫剧剧集不存在");
        }
        ComicAssemblyBatch batch = batchMapper.selectLatest(userId, projectId, episodeId);
        if (batch == null) {
            throw notFound("该剧集还没有最终合成任务");
        }
        return toDetail(reconcile(batch));
    }

    @Scheduled(fixedDelayString = "${comic.assembly.reconcile-interval-ms:5000}")
    @Transactional
    public void reconcileActiveBatches() {
        for (Long batchId : batchMapper.selectReconcilableIds(50)) {
            try {
                ComicAssemblyBatch batch = batchMapper.selectById(batchId);
                if (batch != null) {
                    reconcile(batch);
                }
            } catch (Exception exception) {
                LOG.warn("Comic assembly reconciliation failed batchId={}", batchId, exception);
            }
        }
    }

    private ComicAssemblyBatch reconcile(ComicAssemblyBatch batch) {
        if (batch == null || !ACTIVE_STATUSES.contains(batch.getStatus()) || batch.getWorkflowRunId() == null) {
            return batch;
        }
        WorkflowRun run = workflowRunMapper.selectById(batch.getWorkflowRunId());
        if (run == null) {
            return batch;
        }
        String runStatus = run.getStatus();
        if ("SUCCESS".equals(runStatus)) {
            completeFromRun(batch, run);
            return batch;
        }
        if (FAILED_RUN_STATUSES.contains(runStatus)) {
            LocalDateTime finishedAt = defaultFinishedAt(run.getFinishedAt());
            String message = limit(run.getErrorMessage(), 1900);
            batchMapper.completeFailure(batch.getId(), runStatus, runStatus, message, finishedAt);
            batch.setStatus(runStatus);
            batch.setErrorCode(runStatus);
            batch.setErrorMessage(message);
            batch.setFinishedAt(finishedAt);
            return batch;
        }
        String activeStatus = activeRunStatus(runStatus);
        if (!activeStatus.equals(batch.getStatus())) {
            batchMapper.syncActiveStatus(batch.getId(), activeStatus);
            batch.setStatus(activeStatus);
        }
        return batch;
    }

    private void completeFromRun(ComicAssemblyBatch batch, WorkflowRun run) {
        WorkflowRunStep composeStep = workflowRunStepMapper.selectByRunIdAndNodeId(run.getId(), "compose");
        JsonNode composeOutput = composeStep == null || !"SUCCESS".equals(composeStep.getStatus())
                ? null
                : readJson(composeStep.getOutputJson());
        String finalVideoUrl = firstText(composeOutput, "finalVideoUrl", "videoUrl");
        String subtitleUrl = text(composeOutput, "subtitleUrl");
        String expectedVersionId = "comic:assembly:" + batch.getId();
        JsonNode manifest = object(composeOutput, "compositionManifest");
        String actualVersionId = firstText(manifest, "compositionVersionId");
        LocalDateTime finishedAt = defaultFinishedAt(run.getFinishedAt());
        if (!COMPOSE_HANDLER_KEY.equals(text(composeOutput, "handlerKey"))
                || !expectedVersionId.equals(actualVersionId)
                || finalVideoUrl == null || subtitleUrl == null) {
            String message = "comic.compose 成功结果与当前合成版本不匹配，或缺少 MP4/SRT 产物";
            batchMapper.completeFailure(
                    batch.getId(), "FAILED", "COMPOSE_OUTPUT_INVALID", message, finishedAt
            );
            batch.setStatus("FAILED");
            batch.setErrorCode("COMPOSE_OUTPUT_INVALID");
            batch.setErrorMessage(message);
            batch.setFinishedAt(finishedAt);
            return;
        }
        String resultJson = writeJson(composeOutput);
        int updated = batchMapper.completeSuccess(
                batch.getId(), resultJson, finalVideoUrl, subtitleUrl, finishedAt
        );
        if (updated == 1) {
            batchMapper.markEpisodeCompleted(batch.getEpisodeId());
        }
        batch.setStatus("SUCCESS");
        batch.setResultJson(resultJson);
        batch.setFinalVideoUrl(finalVideoUrl);
        batch.setSubtitleUrl(subtitleUrl);
        batch.setErrorCode(null);
        batch.setErrorMessage(null);
        batch.setFinishedAt(finishedAt);
    }

    private ArrayNode buildSelectedShots(List<ComicShot> shots) {
        ArrayNode selected = objectMapper.createArrayNode();
        for (ComicShot shot : shots) {
            if (shot.getSelectedAttemptId() == null) {
                throw invalid("分镜 " + shot.getSequenceNo() + " 尚未选定生成版本");
            }
            ComicShotAttempt attempt = attemptMapper.selectForShot(shot.getSelectedAttemptId(), shot.getId());
            if (attempt == null || !"SUCCESS".equals(attempt.getStatus())) {
                throw invalid("分镜 " + shot.getSequenceNo() + " 的已选版本尚未生成成功");
            }
            JsonNode attemptResult = readJson(attempt.getResultJson());
            JsonNode videoOutput = findHandlerOutput(attemptResult, "comic.shot_video", 0);
            JsonNode audioOutput = findHandlerOutput(attemptResult, "comic.shot_tts", 0);
            JsonNode keyframeOutput = findHandlerOutput(attemptResult, "comic.shot_keyframe", 0);
            JsonNode clipVersion = object(videoOutput, "clipVersion");
            String shotVersionId = firstText(videoOutput, "shotVersionId");
            if (shotVersionId == null) {
                shotVersionId = firstText(clipVersion, "shotVersionId");
            }
            String clipVersionId = firstText(clipVersion, "clipVersionId");
            if (clipVersionId == null) {
                clipVersionId = firstText(videoOutput, "clipVersionId");
            }
            String videoUrl = firstText(clipVersion, "videoUrl");
            if (videoUrl == null) {
                videoUrl = firstText(videoOutput, "videoUrl");
            }
            if (shotVersionId == null || clipVersionId == null || videoUrl == null) {
                throw invalid("分镜 " + shot.getSequenceNo() + " 的已选版本缺少视频产物");
            }

            ObjectNode item = selected.addObject();
            item.put("selected", true);
            item.put("selectedAttemptId", attempt.getId());
            item.put("shotId", String.valueOf(shot.getId()));
            item.put("shotVersionId", shotVersionId);
            item.put("order", shot.getSequenceNo());
            item.put("clipVersionId", clipVersionId);
            item.put("videoUrl", videoUrl);
            String subtitle = firstNonBlank(shot.getDialogue(), shot.getNarration());
            if (subtitle != null) {
                item.put("subtitleZh", subtitle);
            }
            appendAudio(item, audioOutput);
            appendKeyframe(item, keyframeOutput);
        }
        return selected;
    }

    private void appendAudio(ObjectNode item, JsonNode output) {
        JsonNode version = object(output, "audioVersion");
        String versionId = firstText(version, "audioVersionId");
        if (versionId == null) {
            versionId = firstText(output, "audioVersionId");
        }
        String url = firstText(version, "audioUrl");
        if (url == null) {
            url = firstText(output, "audioUrl");
        }
        if (versionId != null) item.put("audioVersionId", versionId);
        if (url != null) item.put("audioUrl", url);
    }

    private void appendKeyframe(ObjectNode item, JsonNode output) {
        JsonNode version = object(output, "keyframeVersion");
        String versionId = firstText(version, "keyframeVersionId");
        if (versionId == null) {
            versionId = firstText(output, "keyframeVersionId");
        }
        String url = firstText(version, "imageUrl");
        if (url == null) {
            url = firstText(output, "imageUrl");
        }
        if (versionId != null) item.put("keyframeVersionId", versionId);
        if (url != null) item.put("keyframeImageUrl", url);
    }

    private ObjectNode buildComposeInput(ComicAssemblyBatch batch, ComicEpisode episode, ArrayNode selectedShots) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("sourceMode", "IMPORT");
        input.put("storyTheme", episode.getTitle());
        input.put("scriptText", episode.getScriptText());
        input.put("productionMode", "EPISODE_COMPOSE");
        input.put("operationHandlerKey", COMPOSE_HANDLER_KEY);
        input.put("comicProjectId", batch.getProjectId());
        input.put("comicEpisodeId", batch.getEpisodeId());
        input.put("comicAssemblyBatchId", batch.getId());
        input.put("compositionVersionId", "comic:assembly:" + batch.getId());
        input.put("title", episode.getTitle());
        input.set("selectedShotVersions", selectedShots.deepCopy());
        return input;
    }

    private ComicAssemblyBatch requireOwned(Long userId, Long projectId, Long episodeId, Long batchId) {
        ComicAssemblyBatch batch = batchMapper.selectOwned(batchId, userId);
        if (batch == null || !projectId.equals(batch.getProjectId()) || !episodeId.equals(batch.getEpisodeId())) {
            throw notFound("最终合成任务不存在");
        }
        return batch;
    }

    private void ensureSameRequest(ComicAssemblyBatch batch, Long projectId, Long episodeId, String toolCode) {
        if (!projectId.equals(batch.getProjectId()) || !episodeId.equals(batch.getEpisodeId())
                || !toolCode.equals(batch.getToolCode())) {
            throw conflict("clientRequestId 已用于其他最终合成请求");
        }
    }

    private void validateCreateRequest(ComicDtos.CreateAssemblyBatchRequest request) {
        if (request == null || request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            throw invalid("clientRequestId 不能为空");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("请确认已选分镜版本和预计费用后再开始最终合成");
        }
    }

    private ComicDtos.AssemblyBatchDetail toDetail(ComicAssemblyBatch batch) {
        JsonNode selectedShots = readJson(batch.getSelectedShotsJson());
        List<Long> selectedAttemptIds = new ArrayList<>();
        if (selectedShots != null && selectedShots.isArray()) {
            selectedShots.forEach(item -> {
                JsonNode id = item.get("selectedAttemptId");
                if (id != null && id.canConvertToLong()) {
                    selectedAttemptIds.add(id.asLong());
                }
            });
        }
        return new ComicDtos.AssemblyBatchDetail(
                batch.getId(), batch.getProjectId(), batch.getEpisodeId(), batch.getToolCode(),
                batch.getClientRequestId(), batch.getShotCount() == null ? selectedAttemptIds.size() : batch.getShotCount(),
                List.copyOf(selectedAttemptIds), batch.getStatus(), batch.getWorkflowRunId(), batch.getRootTaskId(),
                batch.getFinalVideoUrl(), batch.getSubtitleUrl(), readJson(batch.getResultJson()),
                batch.getErrorCode(), batch.getErrorMessage(), batch.getConfirmedAt(), batch.getStartedAt(),
                batch.getFinishedAt(), batch.getCreatedAt()
        );
    }

    private JsonNode findHandlerOutput(JsonNode node, String handlerKey, int depth) {
        if (node == null || node.isNull() || depth > 32) return null;
        if (node.isObject() && handlerKey.equals(text(node, "handlerKey"))) return node;
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode found = findHandlerOutput(child, handlerKey, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private JsonNode object(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private String firstText(JsonNode node, String... fields) {
        if (fields == null) return null;
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.hasNonNull(field)) return null;
        String value = node.get(field).asText().trim();
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String activeRunStatus(String status) {
        if ("AWAITING_USER".equals(status) || "AWAITING_FUNDS".equals(status) || "CANCELLING".equals(status)) {
            return status;
        }
        return "RUNNING";
    }

    private LocalDateTime defaultFinishedAt(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize comic assembly payload", exception);
        }
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
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
