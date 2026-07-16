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
import com.aiminilab.aitoolmarket.credit.entity.UserMembership;
import com.aiminilab.aitoolmarket.credit.mapper.CreditLogMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.credit.mapper.UserMembershipMapper;
import com.aiminilab.aitoolmarket.credit.realtime.CreditChangeNotifier;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CreditServiceImpl implements CreditService {

    private final CreditMapper creditMapper;
    private final CreditLogMapper creditLogMapper;
    private final CreditChangeNotifier creditChangeNotifier;
    private final UserMembershipMapper membershipMapper;

    public CreditServiceImpl(CreditMapper creditMapper,
                             CreditLogMapper creditLogMapper,
                             CreditChangeNotifier creditChangeNotifier,
                             UserMembershipMapper membershipMapper) {
        this.creditMapper = creditMapper;
        this.creditLogMapper = creditLogMapper;
        this.creditChangeNotifier = creditChangeNotifier;
        this.membershipMapper = membershipMapper;
    }

    @Override
    @Transactional
    public CreditAccountResponse account(Long userId) {
        expireMembershipIfNeeded(userId);
        return CreditAccountResponse.from(creditMapper.getOrCreateAccount(userId));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void freeze(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        expireMembershipIfNeeded(userId);
        CreditAccount before = lockedAccount(userId);
        if (before.getBalance() - before.getFrozen() < amount) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "可用算力不足");
        }
        if (!allocateFreeze(before, amount)) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "可用算力不足");
        }
        creditMapper.updateById(before);
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
        creditChangeNotifier.notifyAfterCommit(userId);
    }

    @Override
    @Transactional
    public void settle(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = lockedAccount(userId);
        if (!settleFrozen(before, amount)) {
            throw new BusinessException(notEnoughErrorCode(sourceType), sourceLabel(sourceType) + "冻结算力不足，无法扣除");
        }
        creditMapper.updateById(before);
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
        creditChangeNotifier.notifyAfterCommit(userId);
    }

    @Override
    @Transactional
    public int settleCompleted(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return 0;
        }
        CreditAccount before = lockedAccount(userId);
        if (settleFrozen(before, amount)) {
            creditMapper.updateById(before);
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
            creditChangeNotifier.notifyAfterCommit(userId);
            return amount;
        }

        CreditAccount current = lockedAccount(userId);
        if (deductAvailableBuckets(current, amount) == amount) {
            creditMapper.updateById(current);
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
            creditChangeNotifier.notifyAfterCommit(userId);
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
        expireMembershipIfNeeded(userId);
        CreditAccount before = lockedAccount(userId);
        int available = before.getBalance() - before.getFrozen();
        int toDeduct = Math.min(available, amount);
        if (toDeduct <= 0) {
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
        if (deductAvailableBuckets(before, toDeduct) != toDeduct) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "credit bucket balance is inconsistent");
        }
        creditMapper.updateById(before);
        creditChangeNotifier.notifyAfterCommit(userId);
        return toDeduct;
    }

    @Override
    @Transactional
    public void release(Long userId, CreditSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            return;
        }
        CreditAccount before = lockedAccount(userId);
        if (!releaseFrozen(before, amount)) {
            return;
        }
        creditMapper.updateById(before);
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
        creditChangeNotifier.notifyAfterCommit(userId);
    }

    @Override
    @Transactional
    public boolean tryFreeze(Long userId, CreditSourceType sourceType, Long sourceId,
                             int amount, String idempotencyKey) {
        requireIdempotentArguments(userId, sourceType, sourceId, amount, idempotencyKey);
        CreditLog existing = creditLogMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            validateIdempotentLog(existing, userId, sourceType, sourceId,
                    CreditLogType.FREEZE.name(), 0, amount);
            return true;
        }
        creditMapper.getOrCreateAccount(userId);
        CreditAccount before = requireAccountForUpdate(userId);
        existing = creditLogMapper.selectByIdempotencyKeyForUpdate(idempotencyKey);
        if (existing != null) {
            validateIdempotentLog(existing, userId, sourceType, sourceId,
                    CreditLogType.FREEZE.name(), 0, amount);
            return true;
        }
        if (before.getBalance() - before.getFrozen() < amount) {
            return false;
        }
        insertIdempotentLog(before, sourceType, sourceId, CreditLogType.FREEZE.name(),
                0, amount, before.getBalance(), before.getFrozen() + amount, idempotencyKey,
                sourceLabel(sourceType) + " reserved credits");
        if (amount > 0 && !creditMapper.freeze(before.getId(), amount)) {
            throw new IllegalStateException("Credit reservation lost its account lock");
        }
        return true;
    }

    @Override
    @Transactional
    public void captureReserved(Long userId, CreditSourceType sourceType, Long sourceId,
                                int reservedAmount, int actualAmount, String idempotencyKey) {
        requireIdempotentArguments(userId, sourceType, sourceId, reservedAmount, idempotencyKey);
        if (actualAmount < 0 || actualAmount > reservedAmount) {
            throw new IllegalArgumentException("Actual credits must be between zero and the reserved amount");
        }
        creditMapper.getOrCreateAccount(userId);
        CreditAccount before = requireAccountForUpdate(userId);
        CreditLog existing = creditLogMapper.selectByIdempotencyKeyForUpdate(idempotencyKey);
        if (existing != null) {
            validateIdempotentLog(existing, userId, sourceType, sourceId,
                    CreditLogType.DEDUCT.name(), actualAmount, -reservedAmount);
            return;
        }
        insertIdempotentLog(before, sourceType, sourceId, CreditLogType.DEDUCT.name(),
                actualAmount, -reservedAmount, before.getBalance() - actualAmount,
                before.getFrozen() - reservedAmount, idempotencyKey,
                sourceLabel(sourceType) + " captured reserved credits");
        if (creditMapper.captureReservedRows(before.getId(), reservedAmount, actualAmount) != 1) {
            throw new IllegalStateException("Reserved credits are no longer available for capture");
        }
    }

    @Override
    @Transactional
    public void releaseReserved(Long userId, CreditSourceType sourceType, Long sourceId,
                                int amount, String idempotencyKey) {
        requireIdempotentArguments(userId, sourceType, sourceId, amount, idempotencyKey);
        creditMapper.getOrCreateAccount(userId);
        CreditAccount before = requireAccountForUpdate(userId);
        CreditLog existing = creditLogMapper.selectByIdempotencyKeyForUpdate(idempotencyKey);
        if (existing != null) {
            validateIdempotentLog(existing, userId, sourceType, sourceId,
                    CreditLogType.RELEASE.name(), 0, -amount);
            return;
        }
        insertIdempotentLog(before, sourceType, sourceId, CreditLogType.RELEASE.name(),
                0, -amount, before.getBalance(), before.getFrozen() - amount, idempotencyKey,
                sourceLabel(sourceType) + " released reserved credits");
        if (amount > 0 && !creditMapper.release(before.getId(), amount)) {
            throw new IllegalStateException("Reserved credits are no longer available for release");
        }
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
        creditChangeNotifier.notifyAfterCommit(userId);
        return account(userId);
    }

    @Override
    @Transactional
    public CreditAccountResponse manualDeduct(Long userId, int amount, String reason, Long operatorId) {
        expireMembershipIfNeeded(userId);
        CreditAccount before = lockedAccount(userId);
        if (before.getBalance() - before.getFrozen() < amount) {
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
        if (deductAvailableBuckets(before, amount) != amount) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "可用算力不足");
        }
        creditMapper.updateById(before);
        creditChangeNotifier.notifyAfterCommit(userId);
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
        creditChangeNotifier.notifyAfterCommit(userId);
        return account(userId);
    }

    @Override
    @Transactional
    public CreditAccountResponse giftRedeemAdd(Long userId, Long giftCardId, int amount, String reason) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "gift redeem amount must be positive");
        }
        if (giftCardId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card id is required");
        }
        String idempotencyKey = "GIFT_CARD_REDEEM:" + giftCardId;
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
                    normalizeReason(reason, "Gift card redeem"),
                    idempotencyKey
            );
        } catch (DuplicateKeyException ignored) {
            return account(userId);
        }
        if (!creditMapper.giftRedeemAdd(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "credit account is unavailable");
        }
        creditChangeNotifier.notifyAfterCommit(userId);
        return account(userId);
    }

    @Override
    @Transactional
    public CreditAccountResponse membershipRechargeAdd(Long userId, Long rechargeOrderId, int amount, String reason) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "membership recharge amount must be positive");
        }
        String idempotencyKey = "MEMBERSHIP_ORDER:" + rechargeOrderId;
        CreditAccount before = creditMapper.getOrCreateAccount(userId);
        try {
            insertLog(
                    before, null, null, CreditLogType.RECHARGE.name(), amount, 0,
                    before.getBalance() + amount, before.getFrozen(), "PAYMENT", null,
                    normalizeReason(reason, "Membership credits"), idempotencyKey
            );
        } catch (DuplicateKeyException ignored) {
            return account(userId);
        }
        if (!creditMapper.membershipRechargeAdd(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "credit account is unavailable");
        }
        creditChangeNotifier.notifyAfterCommit(userId);
        return account(userId);
    }

    @Override
    @Transactional
    public void expireMembershipIfNeeded(Long userId) {
        UserMembership observed = membershipMapper.findByUserId(userId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (observed == null || !"ACTIVE".equals(observed.getStatus())
                || observed.getExpiresAt() == null || observed.getExpiresAt().isAfter(now)) {
            return;
        }
        UserMembership membership = membershipMapper.lockByUserId(userId);
        if (membership == null || !"ACTIVE".equals(membership.getStatus())
                || membership.getExpiresAt() == null || membership.getExpiresAt().isAfter(now)) {
            return;
        }
        CreditAccount account = lockedAccount(userId);
        int membershipBalance = nz(account.getMembershipBalance());
        int membershipFrozen = Math.min(membershipBalance, nz(account.getMembershipFrozen()));
        int expiredAvailable = membershipBalance - membershipFrozen;
        account.setBalance(nz(account.getBalance()) - expiredAvailable);
        account.setMembershipBalance(0);
        account.setMembershipFrozen(0);
        account.setExpiredMembershipFrozen(nz(account.getExpiredMembershipFrozen()) + membershipFrozen);
        account.setTotalExpired(nz(account.getTotalExpired()) + membershipBalance);
        creditMapper.updateById(account);

        membership.setStatus("EXPIRED");
        membership.setUpdatedAt(now);
        membershipMapper.updateState(membership);
        creditChangeNotifier.notifyAfterCommit(userId);
    }

    @Override
    @Transactional
    public CreditAccountResponse referralBonusAdd(Long userId, Long rechargeOrderId, int amount, String reason) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "referral bonus amount must be positive");
        }
        if (rechargeOrderId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order id is required");
        }
        String idempotencyKey = "REFERRAL_REWARD_ORDER:" + rechargeOrderId;
        expireMembershipIfNeeded(userId);
        CreditAccount before = lockedAccount(userId);
        try {
            insertLog(
                    before,
                    null,
                    null,
                    CreditLogType.REFERRAL_BONUS.name(),
                    amount,
                    0,
                    before.getBalance() + amount,
                    before.getFrozen(),
                    "REFERRAL",
                    null,
                    normalizeReason(reason, "Referral recharge reward"),
                    idempotencyKey
            );
        } catch (DuplicateKeyException ignored) {
            return account(userId);
        }
        if (!creditMapper.referralBonusAdd(before.getId(), amount)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "credit account is unavailable");
        }
        creditChangeNotifier.notifyAfterCommit(userId);
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

    private CreditAccount lockedAccount(Long userId) {
        CreditAccount account = creditMapper.getOrCreateAccount(userId);
        CreditAccount locked = creditMapper.lockById(account.getId());
        if (locked == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "credit account lock unavailable");
        }
        return locked;
    }

    private boolean allocateFreeze(CreditAccount account, int amount) {
        int membershipAvailable = nz(account.getMembershipBalance()) - nz(account.getMembershipFrozen());
        int permanentAvailable = nz(account.getPermanentBalance()) - nz(account.getPermanentFrozen());
        int giftAvailable = nz(account.getGiftBalance()) - nz(account.getGiftFrozen());
        if (membershipAvailable + permanentAvailable + giftAvailable < amount) {
            return false;
        }
        int membership = Math.min(amount, membershipAvailable);
        int remaining = amount - membership;
        int permanent = Math.min(remaining, permanentAvailable);
        int gift = remaining - permanent;
        account.setMembershipFrozen(nz(account.getMembershipFrozen()) + membership);
        account.setPermanentFrozen(nz(account.getPermanentFrozen()) + permanent);
        account.setGiftFrozen(nz(account.getGiftFrozen()) + gift);
        account.setFrozen(nz(account.getFrozen()) + amount);
        return true;
    }

    private boolean settleFrozen(CreditAccount account, int amount) {
        if (nz(account.getFrozen()) < amount || nz(account.getBalance()) < amount) {
            return false;
        }
        int remaining = amount;
        int expired = Math.min(remaining, nz(account.getExpiredMembershipFrozen()));
        remaining -= expired;
        int membership = Math.min(remaining, nz(account.getMembershipFrozen()));
        remaining -= membership;
        int permanent = Math.min(remaining, nz(account.getPermanentFrozen()));
        remaining -= permanent;
        int gift = Math.min(remaining, nz(account.getGiftFrozen()));
        remaining -= gift;
        if (remaining != 0) {
            return false;
        }
        account.setExpiredMembershipFrozen(nz(account.getExpiredMembershipFrozen()) - expired);
        account.setMembershipFrozen(nz(account.getMembershipFrozen()) - membership);
        account.setPermanentFrozen(nz(account.getPermanentFrozen()) - permanent);
        account.setGiftFrozen(nz(account.getGiftFrozen()) - gift);
        account.setMembershipBalance(nz(account.getMembershipBalance()) - membership);
        account.setPermanentBalance(nz(account.getPermanentBalance()) - permanent);
        account.setGiftBalance(nz(account.getGiftBalance()) - gift);
        account.setBalance(nz(account.getBalance()) - amount);
        account.setFrozen(nz(account.getFrozen()) - amount);
        account.setTotalConsumed(nz(account.getTotalConsumed()) + amount);
        return true;
    }

    private int deductAvailableBuckets(CreditAccount account, int requested) {
        int available = nz(account.getBalance()) - nz(account.getFrozen());
        int amount = Math.min(Math.max(requested, 0), available);
        int membershipAvailable = nz(account.getMembershipBalance()) - nz(account.getMembershipFrozen());
        int permanentAvailable = nz(account.getPermanentBalance()) - nz(account.getPermanentFrozen());
        int membership = Math.min(amount, membershipAvailable);
        int remaining = amount - membership;
        int permanent = Math.min(remaining, permanentAvailable);
        int gift = remaining - permanent;
        account.setMembershipBalance(nz(account.getMembershipBalance()) - membership);
        account.setPermanentBalance(nz(account.getPermanentBalance()) - permanent);
        account.setGiftBalance(nz(account.getGiftBalance()) - gift);
        account.setBalance(nz(account.getBalance()) - amount);
        account.setTotalConsumed(nz(account.getTotalConsumed()) + amount);
        return amount;
    }

    private boolean releaseFrozen(CreditAccount account, int amount) {
        if (nz(account.getFrozen()) < amount) {
            return false;
        }
        int remaining = amount;
        int expired = Math.min(remaining, nz(account.getExpiredMembershipFrozen()));
        remaining -= expired;
        int membership = Math.min(remaining, nz(account.getMembershipFrozen()));
        remaining -= membership;
        int permanent = Math.min(remaining, nz(account.getPermanentFrozen()));
        remaining -= permanent;
        int gift = Math.min(remaining, nz(account.getGiftFrozen()));
        remaining -= gift;
        if (remaining != 0) {
            return false;
        }
        account.setExpiredMembershipFrozen(nz(account.getExpiredMembershipFrozen()) - expired);
        account.setMembershipFrozen(nz(account.getMembershipFrozen()) - membership);
        account.setPermanentFrozen(nz(account.getPermanentFrozen()) - permanent);
        account.setGiftFrozen(nz(account.getGiftFrozen()) - gift);
        account.setBalance(nz(account.getBalance()) - expired);
        account.setFrozen(nz(account.getFrozen()) - amount);
        return true;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
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

    private void insertIdempotentLog(CreditAccount before,
                                     CreditSourceType sourceType,
                                     Long sourceId,
                                     String logType,
                                     int amount,
                                     int frozenAmount,
                                     int balanceAfter,
                                     int frozenAfter,
                                     String idempotencyKey,
                                     String reason) {
        CreditLog log = new CreditLog();
        log.setUserId(before.getUserId());
        log.setAccountId(before.getId());
        log.setTaskId(taskId(sourceType, sourceId));
        log.setAgentRunId(agentRunId(sourceType, sourceId));
        log.setSourceType(sourceType.name());
        log.setSourceRef(sourceId);
        log.setLogType(logType);
        log.setAmount(amount);
        log.setFrozenAmount(frozenAmount);
        log.setBalanceBefore(before.getBalance());
        log.setBalanceAfter(balanceAfter);
        log.setFrozenBefore(before.getFrozen());
        log.setFrozenAfter(frozenAfter);
        log.setIdempotencyKey(idempotencyKey);
        log.setOperatorType("SYSTEM");
        log.setReason(reason);
        creditLogMapper.insert(log);
    }

    private CreditAccount requireAccountForUpdate(Long userId) {
        CreditAccount account = creditMapper.selectByUserIdForUpdate(userId);
        if (account == null) {
            throw new IllegalStateException("Credit account is missing");
        }
        return account;
    }

    private void requireIdempotentArguments(Long userId,
                                            CreditSourceType sourceType,
                                            Long sourceId,
                                            int amount,
                                            String idempotencyKey) {
        if (userId == null || sourceType == null || sourceId == null) {
            throw new IllegalArgumentException("Credit operation source is required");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("Credit operation amount cannot be negative");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("A valid credit idempotency key is required");
        }
    }

    private void validateIdempotentLog(CreditLog log,
                                       Long userId,
                                       CreditSourceType sourceType,
                                       Long sourceId,
                                       String logType,
                                       int amount,
                                       int frozenAmount) {
        boolean matches = userId.equals(log.getUserId())
                && sourceType.name().equals(log.getSourceType())
                && sourceId.equals(log.getSourceRef())
                && logType.equals(log.getLogType())
                && Integer.valueOf(amount).equals(log.getAmount())
                && Integer.valueOf(frozenAmount).equals(log.getFrozenAmount());
        if (!matches) {
            throw new IllegalStateException("Credit idempotency key conflicts with an existing operation");
        }
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
            case WORKFLOW_STEP -> "Workflow step";
            default -> "任务";
        };
    }
}
