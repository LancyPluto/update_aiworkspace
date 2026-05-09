package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;

public interface CreditService {
    CreditAccountResponse account(Long userId);

    void freezeForTask(Long userId, Long taskId, int amount);

    void settleForTask(Long userId, Long taskId, int amount);

    void releaseForTask(Long userId, Long taskId, int amount);

    CreditAccountResponse manualAdd(Long userId, int amount, String reason, Long operatorId);

    CreditAccountResponse manualDeduct(Long userId, int amount, String reason, Long operatorId);

    PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize);
}
