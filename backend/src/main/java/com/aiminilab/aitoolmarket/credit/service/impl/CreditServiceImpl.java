package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CreditServiceImpl implements CreditService {

    private final CreditMapper creditMapper;

    public CreditServiceImpl(CreditMapper creditMapper) {
        this.creditMapper = creditMapper;
    }

    @Override
    public CreditAccountResponse account(Long userId) {
        return CreditAccountResponse.from(creditMapper.getOrCreateAccount(userId));
    }

    @Override
    public void deductForTask(Long userId, Long taskId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount account = creditMapper.getOrCreateAccount(userId);
        if (account.getBalance() < amount) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "算力不足");
        }
        if (!creditMapper.deduct(account.getId(), amount)) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "算力不足");
        }
        creditMapper.insertDeductLog(account, taskId, amount);
    }

    @Override
    @Transactional
    public ManualAddCreditsResponse manualAdd(Long userId, int amount, String reason, Long operatorId) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "加算力数量必须大于 0");
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        creditMapper.increaseBalance(before.getId(), amount);
        creditMapper.insertManualAddLog(before, amount, operatorId, reason);
        return new ManualAddCreditsResponse(
                userId,
                amount,
                before.getBalance(),
                before.getBalance() + amount,
                reason,
                LocalDateTime.now()
        );
    }

    @Override
    public Integer balance(Long userId) {
        return creditMapper.findOptionalAccountByUserId(userId)
                .map(CreditAccount::getBalance)
                .orElse(0);
    }
}
