package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateToolFieldsRequest(
        @NotEmpty @Valid List<ToolFieldRequest> fields
) {
}
