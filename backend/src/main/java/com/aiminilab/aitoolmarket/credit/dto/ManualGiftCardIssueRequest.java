package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualGiftCardIssueRequest(
        @NotNull @Min(1) Integer amount,
        @Size(max = 512) String reason,
        @NotBlank @Size(max = 100) String operationId
) {
}
