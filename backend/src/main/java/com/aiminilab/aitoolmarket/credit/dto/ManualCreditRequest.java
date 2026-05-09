package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ManualCreditRequest(
        @NotNull @Min(1) Integer amount,
        String reason
) {
}
