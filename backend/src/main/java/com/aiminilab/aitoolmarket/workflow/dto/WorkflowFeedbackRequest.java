package com.aiminilab.aitoolmarket.workflow.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record WorkflowFeedbackRequest(@NotNull Map<String, String> fields) {
}
