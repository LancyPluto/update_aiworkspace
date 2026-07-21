package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;

public interface CreditService {
    CreditAccountResponse account(Long userId);

    void freeze(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    void settle(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    int settleCompleted(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    /**
     * Best-effort deduction directly from available balance (not frozen). Used to collect an
     * over-budget shortfall at settlement. Returns the amount actually deducted (may be partial,
     * down to 0 when the user has no available balance), never throwing on insufficiency.
     */
    int deductAvailable(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    int deductAvailable(Long userId, CreditSourceType sourceType, Long sourceId,
                        int amount, String idempotencyKey);

    void release(Long userId, CreditSourceType sourceType, Long sourceId, int amount);

    boolean tryFreeze(Long userId, CreditSourceType sourceType, Long sourceId, int amount, String idempotencyKey);

    void captureReserved(Long userId, CreditSourceType sourceType, Long sourceId,
                         int reservedAmount, int actualAmount, String idempotencyKey);

    void releaseReserved(Long userId, CreditSourceType sourceType, Long sourceId,
                         int amount, String idempotencyKey);

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

    CreditAccountResponse membershipRechargeAdd(Long userId, Long rechargeOrderId, int amount, String reason);

    void expireMembershipIfNeeded(Long userId);

    CreditAccountResponse giftRedeemAdd(Long userId, Long giftCardId, int amount, String reason);

    CreditAccountResponse referralRegistrationBonusAdd(Long userId, Long referralId, String beneficiaryRole,
                                                       int amount, String reason);

    PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize);

    PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize, boolean includeInternal);
}
