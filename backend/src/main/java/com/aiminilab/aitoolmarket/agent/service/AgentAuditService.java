package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.*;
import java.util.List;

public interface AgentAuditService {
    AgentModelRequestSnapshotResponse recordModelRequest(Long runId, CreateModelRequestSnapshotRequest request);
    AgentRunAuditResponse audit(Long runId);
    List<AgentModelRequestSnapshotResponse> modelRequests(Long runId, Long afterId, Integer pageSize);
    AgentRunAuditReviewResponse review(Long runId, Long reviewerId, UpdateAgentRunAuditReviewRequest request);
    List<AgentSkillCoverageResponse> skillCoverage();
    int expirePayloads();
}
