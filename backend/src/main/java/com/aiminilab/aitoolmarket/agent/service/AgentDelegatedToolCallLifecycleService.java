package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.metrics.AgentMetrics;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AgentDelegatedToolCallLifecycleService {

    private static final Set<String> TERMINAL_WORKFLOW_STATUSES = Set.of("SUCCESS", "FAILED", "TIMEOUT", "CANCELLED");

    private final AgentToolCallMapper toolCallMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ComicProjectApplicationService comicProjectApplicationService;

    public AgentDelegatedToolCallLifecycleService(AgentToolCallMapper toolCallMapper,
                                                  AgentRunEventMapper eventMapper,
                                                  AgentMetrics metrics,
                                                  ObjectMapper objectMapper,
                                                  ComicProjectApplicationService comicProjectApplicationService) {
        this.toolCallMapper = toolCallMapper;
        this.eventMapper = eventMapper;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.comicProjectApplicationService = comicProjectApplicationService;
    }

    public void bindDelegated(Long toolCallId,
                              Long rootTaskId,
                              Long workflowRunId,
                              String workflowStatus) {
        AgentToolCall call = toolCallMapper.findById(toolCallId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent tool call not found"));
        if (isSamePersistedDelegation(call, rootTaskId)) {
            return;
        }
        if (!"RUNNING".equals(call.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Agent tool call cannot be delegated");
        }
        if (call.getTaskId() != null && !Objects.equals(call.getTaskId(), rootTaskId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Agent tool call is bound to another task");
        }
        if (toolCallMapper.markDelegated(toolCallId, rootTaskId) == 1) {
            appendProgressEvent(call, rootTaskId, workflowRunId, "DELEGATED", workflowStatus);
            return;
        }
        AgentToolCall current = toolCallMapper.findById(toolCallId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent tool call not found"));
        if (isSamePersistedDelegation(current, rootTaskId)) {
            return;
        }
        if (current.getTaskId() != null && !Objects.equals(current.getTaskId(), rootTaskId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Agent tool call was bound to another task");
        }
        throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Agent tool call delegation lost its state race");
    }

    @Transactional
    public void progressForWorkflow(Long rootTaskId, Long workflowRunId, String workflowStatus) {
        String normalizedStatus = workflowStatus == null ? "" : workflowStatus.trim().toUpperCase();
        if (!Set.of("RUNNING", "AWAITING_USER", "AWAITING_FUNDS").contains(normalizedStatus)) {
            return;
        }
        AgentToolCall call = toolCallMapper.findDelegatedByWorkflow(rootTaskId, workflowRunId).orElse(null);
        if (call == null) {
            return;
        }
        appendProgressEvent(call, rootTaskId, workflowRunId, normalizedStatus, normalizedStatus);
    }

    private boolean isSamePersistedDelegation(AgentToolCall call, Long rootTaskId) {
        return Objects.equals(call.getTaskId(), rootTaskId)
                && Set.of("DELEGATED", "SUCCESS", "FAILED", "CANCELLED").contains(call.getStatus());
    }

    /**
     * Terminal producers call this in their own transaction after their workflow state CAS succeeds.
     * The delegated-call CAS keeps repeated terminal delivery idempotent.
     */
    @Transactional
    public void finishForWorkflow(Long rootTaskId, Long workflowRunId, String workflowStatus, String workflowError) {
        String normalizedStatus = workflowStatus == null ? "" : workflowStatus.trim().toUpperCase();
        if (!TERMINAL_WORKFLOW_STATUSES.contains(normalizedStatus)) {
            return;
        }
        AgentToolCall call = toolCallMapper.findDelegatedByWorkflow(rootTaskId, workflowRunId).orElse(null);
        if (call == null) {
            return;
        }
        boolean success = "SUCCESS".equals(normalizedStatus);
        String nextStatus = success
                ? "SUCCESS"
                : "CANCELLED".equals(normalizedStatus) ? "CANCELLED" : "FAILED";
        String errorCode = success ? null : "WORKFLOW_" + normalizedStatus;
        String errorMessage = success ? null : normalizeError(normalizedStatus, workflowError);
        LocalDateTime now = LocalDateTime.now();
        String runUrl = runUrl(rootTaskId);
        String resultJson = writeJson(Map.of(
                "success", success,
                "toolCode", call.getToolCode(),
                "taskId", rootTaskId,
                "status", nextStatus,
                "runUrl", runUrl,
                "data", terminalData(workflowRunId, normalizedStatus, workflowError)
        ));
        int updated = toolCallMapper.finishDelegated(
                call.getId(),
                rootTaskId,
                nextStatus,
                resultJson,
                errorCode,
                errorMessage,
                now
        );
        if (updated == 0) {
            return;
        }
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(call.getRunId());
        event.setUserId(call.getUserId());
        event.setEventType("tool.finished");
        event.setEventText(success ? "Workflow completed" : errorMessage);
        event.setEventJson(writeJson(Map.of(
                "toolCode", call.getToolCode(),
                "toolCallId", call.getId(),
                "taskId", rootTaskId,
                "status", nextStatus,
                "workflowRunId", workflowRunId,
                "workflowStatus", normalizedStatus,
                "errorCode", errorCode == null ? "" : errorCode,
                "workflowError", workflowError == null ? "" : workflowError,
                "runUrl", runUrl
        )));
        event.setCreatedAt(now);
        eventMapper.insertEvent(event);
        metrics.recordToolCallOutcome(call.getToolCode(), nextStatus, call.getStartedAt(), now);
    }

    private Map<String, Object> terminalData(Long workflowRunId, String workflowStatus, String workflowError) {
        return Map.of(
                "runId", workflowRunId,
                "workflowStatus", workflowStatus,
                "workflowError", workflowError == null ? "" : workflowError
        );
    }

    private void appendProgressEvent(AgentToolCall call,
                                     Long rootTaskId,
                                     Long workflowRunId,
                                     String status,
                                     String workflowStatus) {
        LocalDateTime now = LocalDateTime.now();
        String runUrl = runUrl(rootTaskId);
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(call.getRunId());
        event.setUserId(call.getUserId());
        event.setEventType("DELEGATED".equals(status) ? "tool.task_dispatched" : "tool.task_progress");
        event.setEventText(progressText(status));
        event.setEventJson(writeJson(Map.of(
                "toolCode", call.getToolCode(),
                "toolCallId", call.getId(),
                "taskId", rootTaskId,
                "status", status,
                "workflowRunId", workflowRunId,
                "workflowStatus", workflowStatus == null ? "RUNNING" : workflowStatus,
                "runUrl", runUrl
        )));
        event.setCreatedAt(now);
        eventMapper.insertEvent(event);
    }

    private String progressText(String status) {
        return switch (status) {
            case "AWAITING_USER" -> "Workflow is waiting for user input";
            case "AWAITING_FUNDS" -> "Workflow is waiting for credits";
            case "RUNNING" -> "Workflow resumed";
            default -> "Workflow delegated";
        };
    }

    private String runUrl(Long rootTaskId) {
        ComicDtos.WorkspaceBinding binding = comicProjectApplicationService.workspaceByRootTaskIdInternal(rootTaskId);
        return binding == null ? "/agents/runs/" + rootTaskId : binding.workspacePath();
    }

    private String normalizeError(String workflowStatus, String workflowError) {
        String message = workflowError == null || workflowError.isBlank()
                ? "Workflow ended with status " + workflowStatus
                : workflowError.trim();
        return message.length() <= 4000 ? message : message.substring(0, 4000);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize Agent workflow lifecycle payload", exception);
        }
    }
}
