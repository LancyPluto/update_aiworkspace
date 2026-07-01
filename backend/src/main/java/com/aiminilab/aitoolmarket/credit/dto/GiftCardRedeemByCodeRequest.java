package com.aiminilab.aitoolmarket.credit.dto;

import jakarta.validation.constraints.NotBlank;

public record GiftCardRedeemByCodeRequest(
        @NotBlank String cardCode
) {
}
