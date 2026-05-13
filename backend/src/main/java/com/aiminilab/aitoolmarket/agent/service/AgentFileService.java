package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.web.multipart.MultipartFile;

public interface AgentFileService {
    AgentFileResponse upload(Long userId, Long sessionId, MultipartFile multipartFile);

    AgentFileResponse createArtifactForRun(Long runId, CreateAgentArtifactRequest request);

    PageResponse<AgentFileResponse> list(Long userId, Long sessionId);
}
