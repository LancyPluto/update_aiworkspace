package com.aiminilab.aitoolmarket.tool.dto;

import java.util.List;

public record WorkflowValidationResponse(
        boolean valid,
        List<String> errors
) {}
