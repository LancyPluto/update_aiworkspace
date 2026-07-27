package com.aiminilab.aitoolmarket.ppt.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PptExecutionTokenRequest(
        @NotNull Long projectId,
        @NotNull Long jobId,
        @NotEmpty List<String> capabilities
) {}
