package com.aiminilab.aitoolmarket.ppt.dto;

public record PptProjectCreatedResponse(
        Long bindingId,
        String projectId,
        String status
) {
}
