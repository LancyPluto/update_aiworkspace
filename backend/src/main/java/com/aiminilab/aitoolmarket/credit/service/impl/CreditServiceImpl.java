package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditLogType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        creditMapper.insertLog(
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
        creditMapper.insertLog(
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
        creditMapper.insertLog(
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
        creditMapper.insertLog(
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
        creditMapper.insertLog(
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
        List<CreditLogResponse> list = creditMapper.findLogs(userId, logType, normalizedPageSize, offset);
        long total = creditMapper.countLogs(userId, logType);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    private String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }
}
