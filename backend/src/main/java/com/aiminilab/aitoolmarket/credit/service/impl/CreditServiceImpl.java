package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.CreditLogType;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.entity.CreditLog;
import com.aiminilab.aitoolmarket.credit.mapper.CreditLogMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    @Transactional(noRollbackFor = BusinessException.class)
    public void freeze(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (before.getBalance() - before.getFrozen() < amount) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "可用算力不足");
        }
        if (!creditMapper.freeze(before.getId(), amount)) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "可用算力不足");
        }
        insertLog(
                before,
                taskId(sourceType, sourceId),
                agentRunId(sourceType, sourceId),
                CreditLogType.FREEZE.name(),
                0,
                amount,
                before.getBalance(),
                before.getFrozen() + amount,
                "SYSTEM",
                null,
                sourceLabel(sourceType) + "冻结算力"
        );
    }

    @Override
    @Transactional
    public void settle(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (!creditMapper.settle(before.getId(), amount)) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "冻结算力不足，无法扣除");
        }
        insertLog(
                before,
                taskId(sourceType, sourceId),
                agentRunId(sourceType, sourceId),
                CreditLogType.DEDUCT.name(),
                amount,
                -amount,
                before.getBalance() - amount,
                before.getFrozen() - amount,
                "SYSTEM",
                null,
                sourceLabel(sourceType) + "成功扣除算力"
        );
    }

    @Override
    @Transactional
    public void release(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (!creditMapper.release(before.getId(), amount)) {
            return;
        }
        insertLog(
                before,
                taskId(sourceType, sourceId),
                agentRunId(sourceType, sourceId),
                CreditLogType.RELEASE.name(),
                0,
                -amount,
                before.getBalance(),
                before.getFrozen() - amount,
                "SYSTEM",
                null,
                sourceLabel(sourceType) + "释放冻结算力"
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

    private void insertLog(CreditAccount before, Long taskId, Long agentRunId, String logType, int amount, int frozenAmount,
                           int balanceAfter, int frozenAfter, String operatorType, Long operatorId, String reason) {
        CreditLog log = new CreditLog();
        log.setUserId(before.getUserId());
        log.setAccountId(before.getId());
        log.setTaskId(taskId);
        log.setAgentRunId(agentRunId);
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
                log.getAgentRunId(),
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

    private ErrorCode notEnoughErrorCode(CreditSourceType sourceType) {
        return sourceType == CreditSourceType.AGENT_RUN ? ErrorCode.AGENT_CREDIT_NOT_ENOUGH : ErrorCode.CREDIT_NOT_ENOUGH;
    }

    private Long taskId(CreditSourceType sourceType, Long sourceId) {
        return sourceType == CreditSourceType.TASK ? sourceId : null;
    }

    private Long agentRunId(CreditSourceType sourceType, Long sourceId) {
        return sourceType == CreditSourceType.AGENT_RUN ? sourceId : null;
    }

    private String sourceLabel(CreditSourceType sourceType) {
        return sourceType == CreditSourceType.AGENT_RUN ? "Agent 运行" : "任务";
    }
}
