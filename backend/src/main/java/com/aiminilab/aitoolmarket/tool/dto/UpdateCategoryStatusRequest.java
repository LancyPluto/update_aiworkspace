package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateCategoryStatusRequest(@NotBlank String status) {
}
