package com.aiminilab.aitoolmarket.credit.support;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditInsufficientData;
import com.aiminilab.aitoolmarket.credit.service.CreditService;

public final class CreditInsufficientSupport {

    private CreditInsufficientSupport() {
    }

    public static void ensureAvailable(CreditService creditService,
                                       Long userId,
                                       int requiredCredits,
                                       ErrorCode errorCode,
                                       String toolCode) {
        ensureAvailable(creditService, userId, requiredCredits, errorCode, toolCode, 0);
    }

    /**
     * 检查用户可用算力是否充足，可排除当前 Agent 运行实例已冻结的额度。
     *
     * @param excludeFrozen 需要排除的冻结算力（如当前 Agent run 的 creditBudget），
     *                      会加回到 available 上，避免自身冻结阻塞工具调用。
     */
    public static void ensureAvailable(CreditService creditService,
                                       Long userId,
                                       int requiredCredits,
                                       ErrorCode errorCode,
                                       String toolCode,
                                       int excludeFrozen) {
        if (requiredCredits <= 0) {
            return;
        }
        CreditAccountResponse account = creditService.account(userId);
        int available = account.available() + Math.max(0, excludeFrozen);
        if (available >= requiredCredits) {
            return;
        }
        CreditInsufficientData data = toolCode == null || toolCode.isBlank()
                ? CreditInsufficientData.of(available, requiredCredits)
                : CreditInsufficientData.of(available, requiredCredits, toolCode);
        String message = toolCode == null || toolCode.isBlank()
                ? String.format("可用算力不足：当前 %d，至少需要 %d", available, requiredCredits)
                : String.format("可用算力不足：当前 %d，调用「%s」至少需要 %d", available, toolCode, requiredCredits);
        throw new BusinessException(errorCode, message, data);
    }
}
