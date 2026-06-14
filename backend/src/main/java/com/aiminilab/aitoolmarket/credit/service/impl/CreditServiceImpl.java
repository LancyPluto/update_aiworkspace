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
import org.springframework.dao.DuplicateKeyException;
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
    public int settleCompleted(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return 0;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        if (creditMapper.settle(before.getId(), amount)) {
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
                    sourceLabel(sourceType) + " success credit deduction"
            );
            return amount;
        }

        CreditAccount current = creditMapper.getOrCreateAccount(userId);
        if (creditMapper.deductAvailable(current.getId(), amount)) {
            insertLog(
                    current,
                    taskId(sourceType, sourceId),
                    agentRunId(sourceType, sourceId),
                    CreditLogType.DEDUCT.name(),
                    amount,
                    0,
                    current.getBalance() - amount,
                    current.getFrozen(),
                    "SYSTEM",
                    null,
                    sourceLabel(sourceType) + " success credit deduction without frozen balance"
            );
            return amount;
        }
        return 0;
    }

    @Override
    @Transactional
    public int deductAvailable(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return 0;
        }
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        int available = before.getBalance() - before.getFrozen();
        int toDeduct = Math.min(available, amount);
        if (toDeduct <= 0) {
            return 0;
        }
        if (!creditMapper.deductAvailable(before.getId(), toDeduct)) {
            return 0;
        }
        insertLog(
                before,
                taskId(sourceType, sourceId),
                agentRunId(sourceType, sourceId),
                CreditLogType.DEDUCT.name(),
                toDeduct,
                0,
                before.getBalance() - toDeduct,
                before.getFrozen(),
                "SYSTEM",
                null,
                sourceLabel(sourceType) + "超预算补扣算力"
        );
        return toDeduct;
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
    @Transactional
    public CreditAccountResponse rechargeAdd(Long userId, Long rechargeOrderId, int amount, String reason) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge amount must be positive");
        }
        String idempotencyKey = "RECHARGE_ORDER:" + rechargeOrderId;
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        try {
            insertLog(
                    before,
                    null,
                    null,
                    CreditLogType.RECHARGE.name(),
                    amount,
                    0,
                    before.getBalance() + amount,
                    before.getFrozen(),
                    "PAYMENT",
                    null,
                    normalizeReason(reason, "Recharge credits"),
                    idempotencyKey
            );
        } catch (DuplicateKeyException ignored) {
            return account(userId);
        }
        if (!creditMapper.rechargeAdd(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "credit account is unavailable");
        }
        return account(userId);
    }

    @Override
    public PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize) {
        return logs(userId, logType, pageNo, pageSize, false);
    }

    @Override
    public PageResponse<CreditLogResponse> logs(Long userId, String logType, Integer pageNo, Integer pageSize, boolean includeInternal) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<CreditLogResponse> list = creditLogMapper.findLogs(userId, logType, normalizedPageSize, offset, includeInternal).stream()
                .map(this::toResponse)
                .toList();
        long total = creditLogMapper.countLogs(userId, logType, includeInternal);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    private void insertLog(CreditAccount before, Long taskId, Long agentRunId, String logType, int amount, int frozenAmount,
                           int balanceAfter, int frozenAfter, String operatorType, Long operatorId, String reason) {
        insertLog(before, taskId, agentRunId, logType, amount, frozenAmount, balanceAfter, frozenAfter, operatorType,
                operatorId, reason, null);
    }

    private void insertLog(CreditAccount before, Long taskId, Long agentRunId, String logType, int amount, int frozenAmount,
                           int balanceAfter, int frozenAfter, String operatorType, Long operatorId, String reason,
                           String idempotencyKey) {
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
        log.setIdempotencyKey(idempotencyKey);
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
        if (sourceType == CreditSourceType.AGENT_RUN) {
            return ErrorCode.AGENT_CREDIT_NOT_ENOUGH;
        }
        return ErrorCode.CREDIT_NOT_ENOUGH;
    }

    private Long taskId(CreditSourceType sourceType, Long sourceId) {
        return sourceType == CreditSourceType.TASK ? sourceId : null;
    }

    private Long agentRunId(CreditSourceType sourceType, Long sourceId) {
        return sourceType == CreditSourceType.AGENT_RUN ? sourceId : null;
    }

    private String sourceLabel(CreditSourceType sourceType) {
        return switch (sourceType) {
            case AGENT_RUN -> "Agent 运行";
            case PPT_STEP -> "PPT 生成";
            default -> "任务";
        };
    }
}
