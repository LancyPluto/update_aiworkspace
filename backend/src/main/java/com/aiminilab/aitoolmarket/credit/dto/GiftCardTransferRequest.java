package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GiftCardTransferRequest(
        @NotBlank @Size(max = 128) String account
) {
}
