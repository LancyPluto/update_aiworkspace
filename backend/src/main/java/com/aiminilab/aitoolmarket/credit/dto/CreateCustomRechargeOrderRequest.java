package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCustomRechargeOrderRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("9999.99") BigDecimal amount,
        @Size(max = 32) String paymentChannel,
        @NotBlank @Size(max = 128) String clientRequestId
) {
}
