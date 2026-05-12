package com.aiminilab.aitoolmarket.user.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.user.dto.AdminUserSummaryResponse;

public interface AdminUserService {
    PageResponse<AdminUserSummaryResponse> list();

    ManualAddCreditsResponse manualAddCredits(Long userId, int amount, String reason, Long operatorId);
}
