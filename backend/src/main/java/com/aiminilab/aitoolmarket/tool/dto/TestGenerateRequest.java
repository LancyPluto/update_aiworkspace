package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TestGenerateRequest(
        @NotNull Map<String, Object> params
) {
}
