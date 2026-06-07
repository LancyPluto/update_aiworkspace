package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AdminAgentRunContextSnapshotResponse(
        Long sessionId,
        Long workspaceId,
        Long userId,
        String userMessage,
        List<AdminAgentFileSnapshotResponse> agentFiles
) {
    public static AdminAgentRunContextSnapshotResponse from(InternalAgentRunContextResponse context) {
        return new AdminAgentRunContextSnapshotResponse(
                context.sessionId(),
                context.workspaceId(),
                context.userId(),
                context.message() == null ? "" : context.message(),
                context.agentFiles() == null
                        ? List.of()
                        : context.agentFiles().stream().map(AdminAgentFileSnapshotResponse::from).toList()
        );
    }
}
