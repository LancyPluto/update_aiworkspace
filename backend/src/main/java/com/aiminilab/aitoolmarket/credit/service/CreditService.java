package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;

public interface CreditService {
    CreditAccountResponse account(Long userId);

    void freeze(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    void settle(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    void release(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    default void freezeForTask(Long userId, Long taskId, int amount) {
        freeze(userId, CreditSourceType.TASK, taskId, amount);
    }

    default void settleForTask(Long userId, Long taskId, int amount) {
        settle(userId, CreditSourceType.TASK, taskId, amount);
    }

    default void releaseForTask(Long userId, Long taskId, int amount) {
        release(userId, CreditSourceType.TASK, taskId, amount);
    }

    default void freezeForAgentRun(Long userId, Long runId, int amount) {
        freeze(userId, CreditSourceType.AGENT_RUN, runId, amount);
    }

    default void settleForAgentRun(Long userId, Long runId, int amount) {
        settle(userId, CreditSourceType.AGENT_RUN, runId, amount);
    }

    default void releaseForAgentRun(Long userId, Long runId, int amount) {
        release(userId, CreditSourceType.AGENT_RUN, runId, amount);
    }

    CreditAccountResponse manualAdd(Long userId, int amount, String reason, Long operatorId);

    CreditAccountResponse manualDeduct(Long userId, int amount, String reason, Long operatorId);

    CreditAccountResponse rechargeAdd(Long userId, Long rechargeOrderId, int amount, String reason);

    PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize);
}
