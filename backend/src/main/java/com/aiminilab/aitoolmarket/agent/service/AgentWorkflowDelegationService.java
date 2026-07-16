package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.DelegatedWorkflowToolCallResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

@Service
public class AgentWorkflowDelegationService {

    private static final String TOOL_CALL_RUNNING = "RUNNING";
    private static final String TOOL_CALL_DELEGATED = "DELEGATED";
    private static final String AGENT_RUN_RUNNING = "RUNNING";
    private static final String LAUNCH_SOURCE = "AGENT_CHAT";
    private static final Set<String> FAILED_WORKFLOW_STATUSES = Set.of("FAILED", "TIMEOUT");

    private final AgentToolCallMapper toolCallMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentToolDescriptorService descriptorService;
    private final WorkflowRunApplicationService workflowService;
    private final WorkflowRunMapper workflowRunMapper;
    private final ToolMapper toolMapper;
    private final ObjectMapper objectMapper;

    public AgentWorkflowDelegationService(AgentToolCallMapper toolCallMapper,
                                          AgentRunMapper agentRunMapper,
                                          AgentToolDescriptorService descriptorService,
                                          WorkflowRunApplicationService workflowService,
                                          WorkflowRunMapper workflowRunMapper,
                                          ToolMapper toolMapper,
                                          ObjectMapper objectMapper) {
        this.toolCallMapper = toolCallMapper;
        this.agentRunMapper = agentRunMapper;
        this.descriptorService = descriptorService;
        this.workflowService = workflowService;
        this.workflowRunMapper = workflowRunMapper;
        this.toolMapper = toolMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DelegatedWorkflowToolCallResponse delegate(Long toolCallId) {
        AgentToolCall call = toolCallMapper.findByIdForUpdate(toolCallId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent tool call not found"));
        AgentRun agentRun = agentRunMapper.findById(call.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        if (!Objects.equals(call.getUserId(), agentRun.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Agent tool call does not belong to its run user");
        }
        if (isPersistedDelegationStatus(call)) {
            return existingDelegation(call, agentRun);
        }
        if (!TOOL_CALL_RUNNING.equals(call.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Agent tool call is already terminal");
        }
        if (!AGENT_RUN_RUNNING.equals(agentRun.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Agent run cannot delegate a workflow now");
        }
        if (call.getTaskId() != null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Agent tool call is bound to another task");
        }
        AgentToolDescriptorResponse descriptor = descriptorService.getToolForAgent(call.getUserId(), call.getToolCode());
        if (!"WORKFLOW".equalsIgnoreCase(descriptor.executionMode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent tool call is not a workflow tool");
        }
        JsonNode input = parseArguments(call.getArgumentsJson());
        WorkflowRunCreated created = workflowService.createInCurrentTransaction(new CreateWorkflowRunCommand(
                call.getUserId(),
                call.getToolCode(),
                input,
                clientRequestId(call),
                LAUNCH_SOURCE,
                call.getId()
        ));
        return DelegatedWorkflowToolCallResponse.of(created.rootTaskId(), created.runId(), created.status());
    }

    private DelegatedWorkflowToolCallResponse existingDelegation(AgentToolCall call, AgentRun agentRun) {
        if (call.getTaskId() == null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Delegated Agent tool call has no root task");
        }
        WorkflowRun workflowRun = workflowRunMapper.findByRootTaskId(call.getTaskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Delegated workflow run not found"));
        AiTool tool = toolMapper.findAnyByCode(call.getToolCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Delegated workflow tool not found"));
        if (!Objects.equals(workflowRun.getUserId(), agentRun.getUserId())
                || !Objects.equals(workflowRun.getToolId(), tool.getId())
                || !Objects.equals(workflowRun.getRootTaskId(), call.getTaskId())
                || !LAUNCH_SOURCE.equals(workflowRun.getLaunchSource())
                || !clientRequestId(call).equals(workflowRun.getClientRequestId())
                || !terminalStatusMatches(call.getStatus(), workflowRun.getStatus())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Delegated workflow identity does not match tool call");
        }
        return DelegatedWorkflowToolCallResponse.of(
                workflowRun.getRootTaskId(),
                workflowRun.getId(),
                workflowRun.getStatus()
        );
    }

    private boolean isPersistedDelegationStatus(AgentToolCall call) {
        if (TOOL_CALL_DELEGATED.equals(call.getStatus())) {
            return true;
        }
        return call.getTaskId() != null
                && Set.of("SUCCESS", "FAILED", "CANCELLED").contains(call.getStatus());
    }

    private boolean terminalStatusMatches(String toolCallStatus, String workflowStatus) {
        if (TOOL_CALL_DELEGATED.equals(toolCallStatus)) {
            return true;
        }
        if ("SUCCESS".equals(toolCallStatus)) {
            return "SUCCESS".equals(workflowStatus);
        }
        if ("CANCELLED".equals(toolCallStatus)) {
            return "CANCELLED".equals(workflowStatus);
        }
        return "FAILED".equals(toolCallStatus) && FAILED_WORKFLOW_STATUSES.contains(workflowStatus);
    }

    private JsonNode parseArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent workflow arguments are empty");
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawArguments);
            if (parsed != null && parsed.isTextual()) {
                parsed = objectMapper.readTree(parsed.asText());
            }
            if (parsed == null || !parsed.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent workflow arguments must be a JSON object");
            }
            return parsed;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent workflow arguments are invalid JSON");
        }
    }

    private String clientRequestId(AgentToolCall call) {
        return "agent-run-%d-tool-call-%d".formatted(call.getRunId(), call.getId());
    }
}
