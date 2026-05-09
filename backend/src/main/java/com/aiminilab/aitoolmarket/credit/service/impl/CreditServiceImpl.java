package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditLogType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
<<<<<<< HEAD
import com.aiminilab.aitoolmarket.credit.dto.ManualAddCreditsResponse;
=======
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
>>>>>>> origin/feature/backend-core
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.entity.CreditLog;
import com.aiminilab.aitoolmarket.credit.mapper.CreditLogMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

<<<<<<< HEAD
import java.time.LocalDateTime;
=======
import java.util.List;
>>>>>>> origin/feature/backend-core

@Service
public class CreditServiceImpl implements CreditService {

    private final CreditMapper creditMapper;
    private final CreditLogMapper creditLogMapper;

    public CreditServiceImpl(CreditMapper creditMapper, CreditLogMapper creditLogMapper) {
        this.creditMapper = creditMapper;
        this.creditLogMapper = creditLogMapper;
    }

    @Override
    public CreditAccountResponse account(Long userId) {
        return CreditAccountResponse.from(creditMapper.getOrCreateAccount(userId));
    }

    @Override
    @Transactional
    public void freezeForTask(Long userId, Long taskId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (before.getBalance() - before.getFrozen() < amount) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "算力不足");
        }
        if (!creditMapper.freeze(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "算力不足");
        }
        insertLog(
                before,
                taskId,
                CreditLogType.FREEZE.name(),
                0,
                amount,
                before.getBalance(),
                before.getFrozen() + amount,
                "SYSTEM",
                null,
                "创建任务冻结算力"
        );
    }

    @Override
    @Transactional
    public void settleForTask(Long userId, Long taskId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (!creditMapper.settle(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "冻结算力不足，无法扣除");
        }
        insertLog(
                before,
                taskId,
                CreditLogType.DEDUCT.name(),
                amount,
                -amount,
                before.getBalance() - amount,
                before.getFrozen() - amount,
                "SYSTEM",
                null,
                "任务成功扣除算力"
        );
    }

    @Override
    @Transactional
    public void releaseForTask(Long userId, Long taskId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (!creditMapper.release(before.getId(), amount)) {
            return;
        }
        insertLog(
                before,
                taskId,
                CreditLogType.RELEASE.name(),
                0,
                -amount,
                before.getBalance(),
                before.getFrozen() - amount,
                "SYSTEM",
                null,
                "任务失败或取消释放算力"
        );
    }

    @Override
    @Transactional
    public CreditAccountResponse manualAdd(Long userId, int amount, String reason, Long operatorId) {
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (!creditMapper.manualAdd(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "算力账户不可用");
        }
        insertLog(
                before,
                null,
                CreditLogType.MANUAL_ADD.name(),
                amount,
                0,
                before.getBalance() + amount,
                before.getFrozen(),
                "ADMIN",
                operatorId,
                normalizeReason(reason, "后台手动增加算力")
        );
        return account(userId);
    }

    @Override
    @Transactional
    public CreditAccountResponse manualDeduct(Long userId, int amount, String reason, Long operatorId) {
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (before.getBalance() - before.getFrozen() < amount || !creditMapper.manualDeduct(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "可用算力不足");
        }
        insertLog(
                before,
                null,
                CreditLogType.MANUAL_DEDUCT.name(),
                amount,
                0,
                before.getBalance() - amount,
                before.getFrozen(),
                "ADMIN",
                operatorId,
                normalizeReason(reason, "后台手动扣减算力")
        );
        return account(userId);
    }

    @Override
    public PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<CreditLogResponse> list = creditLogMapper.findLogs(userId, logType, normalizedPageSize, offset).stream()
                .map(this::toResponse)
                .toList();
        long total = creditLogMapper.countLogs(userId, logType);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    private void insertLog(CreditAccount before, Long taskId, String logType, int amount, int frozenAmount,
                           int balanceAfter, int frozenAfter, String operatorType, Long operatorId, String reason) {
        CreditLog log = new CreditLog();
        log.setUserId(before.getUserId());
        log.setAccountId(before.getId());
        log.setTaskId(taskId);
        log.setLogType(logType);
        log.setAmount(amount);
        log.setFrozenAmount(frozenAmount);
        log.setBalanceBefore(before.getBalance());
        log.setBalanceAfter(balanceAfter);
        log.setFrozenBefore(before.getFrozen());
        log.setFrozenAfter(frozenAfter);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setReason(reason);
        creditLogMapper.insert(log);
    }

    private CreditLogResponse toResponse(CreditLog log) {
        return new CreditLogResponse(
                log.getId(),
                log.getUserId(),
                log.getTaskId(),
                log.getLogType(),
                log.getAmount(),
                log.getFrozenAmount(),
                log.getBalanceBefore(),
                log.getBalanceAfter(),
                log.getFrozenBefore(),
                log.getFrozenAfter(),
                log.getOperatorType(),
                log.getOperatorId(),
                log.getReason(),
                log.getCreatedAt()
        );
    }

    private String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
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
