package com.aiminilab.aitoolmarket.ppt.dto;

public record UpdatePptModelBindingRequest(
        Long textModelConfigId,
        Long imageModelConfigId
) {
}
