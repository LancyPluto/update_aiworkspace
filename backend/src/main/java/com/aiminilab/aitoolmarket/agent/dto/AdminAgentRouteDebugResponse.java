package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AdminAgentRouteDebugResponse(
        String intent,
        Double confidence,
        String selectedToolCode,
        List<String> candidateToolCodes,
        String clarifyingQuestion,
        String decisionSource,
        String reason,
        String requestedOutputModality,
        Integer visibleToolCount,
        List<RouteDebugToolResponse> visibleTools,
        List<RouteDebugFilteredToolResponse> filteredTools,
        String readiness,
        Boolean modelConnectivity,
        Integer availableToolCount,
        Integer disclosedToolCount,
        Integer estimatedRouterPromptBytes,
        List<String> nextActions,
        Boolean llmRouterEnabled,
        Boolean productToolLoopEnabled,
        Boolean toolDisclosureEnabled
) {
    public record RouteDebugToolResponse(
            String toolCode,
            String toolName,
            Boolean autoCallable
    ) {
    }

    public record RouteDebugFilteredToolResponse(
            String toolCode,
            String toolName,
            String reason
    ) {
    }
}
