package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.dto.BindAgentToolCallTaskRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CompleteAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.ConfirmAgentToolRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.EditRegenerateAgentMessageRequest;
import com.aiminilab.aitoolmarket.agent.dto.RegenerateAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentToolCallRequest;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpsertStreamingAgentAnswerRequest;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentRunService {
    CreateAgentMessageResponse sendMessage(Long userId, Long sessionId, CreateAgentMessageRequest request);

    CreateAgentMessageResponse regenerateRun(Long userId, Long runId, RegenerateAgentRunRequest request);

    CreateAgentMessageResponse editRegenerateMessage(Long userId, Long sessionId, Long messageId,
                                                     EditRegenerateAgentMessageRequest request);

    AgentRunResponse detail(Long userId, Long runId);

    AgentRunResponse cancel(Long userId, Long runId);

    AgentRunResponse confirmTool(Long userId, Long runId, ConfirmAgentToolRequest request);

    PageResponse<AgentRunEventResponse> events(Long userId, Long runId, Long afterEventId, Integer pageSize);

    SseEmitter streamEvents(Long userId, Long runId, Long afterEventId);

    InternalAgentRunContextResponse context(Long runId);

    AgentRunEventResponse appendEvent(Long runId, CreateAgentRunEventRequest request);

    AgentToolCallResponse createToolCall(Long runId, CreateAgentToolCallRequest request);

    AgentToolCallResponse bindToolCallTask(Long toolCallId, BindAgentToolCallTaskRequest request);

    AgentToolCallResponse completeToolCall(Long toolCallId, CompleteAgentToolCallRequest request);

    AgentToolCallResponse failToolCall(Long toolCallId, FailAgentToolCallRequest request);

    AgentRunResponse completeRun(Long runId, CompleteAgentRunRequest request);

    AgentRunResponse upsertStreamingAnswer(Long runId, UpsertStreamingAgentAnswerRequest request);

    AgentRunResponse failRun(Long runId, FailAgentRunRequest request);

    void saveGraphCheckpoint(Long runId, String checkpointJson);

    String getGraphCheckpoint(Long runId);

    void clearGraphCheckpoint(Long runId);
}
