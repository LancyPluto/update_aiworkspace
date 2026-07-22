package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.entity.BillingUsageLog;
import com.aiminilab.aitoolmarket.admin.mapper.BillingUsageLogMapper;
import com.aiminilab.aitoolmarket.admin.metrics.BillingMetrics;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.mapper.VendorBalanceAdjustmentMapper;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class BillingServiceImpl implements BillingService {
    private static final String BILLING_UNIT_PER_CALL = "PER_CALL";
    private static final String BILLING_UNIT_PER_SECOND = "PER_SECOND";
    private static final String BILLING_UNIT_PER_CHARACTER = "PER_CHARACTER";
    private static final BigDecimal CREDIT_PRICE_CNY = new BigDecimal("0.01");

    private final BillingUsageLogMapper billingUsageLogMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final VendorBalanceAdjustmentMapper vendorBalanceAdjustmentMapper;
    private final BillingMetrics billingMetrics;

    public BillingServiceImpl(BillingUsageLogMapper billingUsageLogMapper,
                              AgentModelConfigMapper modelConfigMapper,
                              ModelVendorAccountMapper vendorAccountMapper,
                              VendorBalanceAdjustmentMapper vendorBalanceAdjustmentMapper,
                              BillingMetrics billingMetrics) {
        this.billingUsageLogMapper = billingUsageLogMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.vendorAccountMapper = vendorAccountMapper;
        this.vendorBalanceAdjustmentMapper = vendorBalanceAdjustmentMapper;
        this.billingMetrics = billingMetrics;
    }

    @Override
    public BillingOverviewResponse overview(Long userId, Long modelConfigId, String provider, String modelName,
                                            String sourceType, Long sourceId, LocalDate startDate, LocalDate endDate) {
        LocalDate normalizedStart = startDate == null ? LocalDate.now() : startDate;
        LocalDate normalizedEnd = endDate == null ? normalizedStart : endDate;
        if (normalizedEnd.isBefore(normalizedStart)) {
            normalizedEnd = normalizedStart;
        }
        LocalDateTime startAt = normalizedStart.atStartOfDay();
        LocalDateTime endAt = normalizedEnd.plusDays(1).atStartOfDay();
        return new BillingOverviewResponse(
                normalizedStart,
                normalizedEnd,
                billingUsageLogMapper.sumPromptTokens(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.sumCompletionTokens(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.sumTotalTokens(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                zeroIfNull(billingUsageLogMapper.sumCostAmount(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId)),
                billingUsageLogMapper.sumChargedCredits(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.countUsage(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.modelCosts(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.userCosts(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.modalityCosts(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId),
                billingUsageLogMapper.dailyCosts(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId)
        );
    }

    @Override
    public PageResponse<BillingUsageLogResponse> logs(Integer pageNo, Integer pageSize, Long userId, Long modelConfigId,
                                                      String provider, String modelName, String sourceType,
                                                      Long sourceId, LocalDate startDate, LocalDate endDate) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        LocalDateTime startAt = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endAt = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        long total = billingUsageLogMapper.countLogs(startAt, endAt, userId, modelConfigId, clean(provider), clean(modelName), clean(sourceType), sourceId);
        return PageResponse.of(
                billingUsageLogMapper.findLogs(normalizedPageSize, offset, startAt, endAt, userId, modelConfigId,
                        clean(provider), clean(modelName), clean(sourceType), sourceId),
                total,
                pageNo,
                pageSize
        );
    }

    @Override
    @Transactional
    public void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                            Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                            BigDecimal vendorCostAmount, BigDecimal markupRatio) {
        recordUsage(sourceType, sourceId, userId, modelConfig, promptTokens, completionTokens, billableUnits, chargedCredits,
                vendorCostAmount, markupRatio, "SUCCESS", null, null, null, null, true);
    }

    @Override
    @Transactional
    public void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                            Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                            BigDecimal vendorCostAmount, BigDecimal markupRatio,
                            String outcome, String errorCode, String failureStage,
                            String providerErrorCode, String providerRequestId, Boolean providerCharged) {
        recordUsage(sourceType, sourceId, userId, modelConfig, promptTokens, completionTokens,
                billableUnits, chargedCredits, vendorCostAmount, "CNY", markupRatio, outcome,
                errorCode, failureStage, providerErrorCode, providerRequestId, providerCharged);
    }

    @Override
    @Transactional
    public void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                            Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                            BigDecimal vendorCostAmount, String providerCostCurrency, BigDecimal markupRatio,
                            String outcome, String errorCode, String failureStage,
                            String providerErrorCode, String providerRequestId, Boolean providerCharged) {
        recordUsageInternal(null, sourceType, sourceId, userId, modelConfig, promptTokens, completionTokens,
                billableUnits, chargedCredits, vendorCostAmount, providerCostCurrency, markupRatio,
                outcome, errorCode, failureStage,
                providerErrorCode, providerRequestId, providerCharged);
    }

    @Override
    @Transactional
    public Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                                AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                                Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                                BigDecimal markupRatio) {
        return recordUsageOnce(idempotencyKey, sourceType, sourceId, userId, modelConfig,
                promptTokens, completionTokens, billableUnits, chargedCredits, vendorCostAmount,
                markupRatio, "SUCCESS", null, null, null, null, true);
    }

    @Override
    @Transactional
    public Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                                AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                                Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                                BigDecimal markupRatio, String outcome, String errorCode, String failureStage,
                                String providerErrorCode, String providerRequestId, Boolean providerCharged) {
        return recordUsageOnce(idempotencyKey, sourceType, sourceId, userId, modelConfig,
                promptTokens, completionTokens, billableUnits, chargedCredits, vendorCostAmount,
                "CNY", markupRatio, outcome, errorCode, failureStage, providerErrorCode,
                providerRequestId, providerCharged);
    }

    @Override
    @Transactional
    public Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                                AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                                Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                                String providerCostCurrency, BigDecimal markupRatio, String outcome,
                                String errorCode, String failureStage, String providerErrorCode,
                                String providerRequestId, Boolean providerCharged) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("A valid billing usage idempotency key is required");
        }
        BillingUsageLog existing = billingUsageLogMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            validateUsageReplay(existing, sourceType, sourceId, userId, promptTokens, completionTokens,
                    billableUnits, chargedCredits, vendorCostAmount, providerCostCurrency,
                    outcome, errorCode, failureStage,
                    providerErrorCode, providerRequestId, providerCharged);
            return existing.getId();
        }
        try {
            return recordUsageInternal(idempotencyKey, sourceType, sourceId, userId, modelConfig,
                    promptTokens, completionTokens, billableUnits, chargedCredits, vendorCostAmount,
                    providerCostCurrency, markupRatio, outcome, errorCode, failureStage, providerErrorCode,
                    providerRequestId, providerCharged);
        } catch (DuplicateKeyException exception) {
            existing = billingUsageLogMapper.selectByIdempotencyKeyForUpdate(idempotencyKey);
            if (existing == null) {
                throw exception;
            }
            validateUsageReplay(existing, sourceType, sourceId, userId, promptTokens, completionTokens,
                    billableUnits, chargedCredits, vendorCostAmount, providerCostCurrency,
                    outcome, errorCode, failureStage,
                    providerErrorCode, providerRequestId, providerCharged);
            return existing.getId();
        }
    }

    @Override
    @Transactional
    public boolean attachActualProviderAccounting(Long usageId,
                                                  AgentModelConfig modelConfig,
                                                  BigDecimal providerCostAmount,
                                                  String providerCostCurrency,
                                                  String providerRequestId) {
        if (usageId == null || providerCostAmount == null || providerCostAmount.signum() < 0) {
            throw new IllegalArgumentException("A non-negative actual provider cost is required");
        }
        BigDecimal normalizedCost = providerCostAmount.setScale(6, RoundingMode.HALF_UP);
        if (normalizedCost.precision() - normalizedCost.scale() > 12) {
            throw new IllegalArgumentException("Actual provider cost is too large");
        }
        String normalizedCurrency = normalizeProviderCostCurrency(providerCostCurrency);
        String normalizedRequestId = cleanNullable(providerRequestId, 128);
        BillingUsageLog existing = billingUsageLogMapper.selectByIdForUpdate(usageId);
        if (existing == null) {
            throw new IllegalStateException("Billing usage does not exist for provider accounting");
        }
        if (Boolean.TRUE.equals(existing.getProviderCharged())) {
            validateAttachedProviderAccounting(existing, normalizedCost, normalizedCurrency, normalizedRequestId);
            return false;
        }
        boolean unknown = zeroIfNull(existing.getCostAmount()).signum() == 0
                && zeroIfNull(existing.getVendorCostAmount()).signum() == 0
                && "UNKNOWN".equalsIgnoreCase(clean(existing.getProviderCostCurrency()));
        if (!unknown) {
            throw new IllegalStateException("Billing usage provider accounting is not attachable");
        }
        if (existing.getProviderRequestId() != null
                && normalizedRequestId != null
                && !existing.getProviderRequestId().equals(normalizedRequestId)) {
            throw new IllegalStateException("Provider request id conflicts with existing billing usage");
        }
        int customerCredits = nonNegative(existing.getCustomerChargeCredits());
        int marginCredits = Math.max(0, customerCredits - costToCredits(normalizedCost));
        if (billingUsageLogMapper.attachActualProviderAccountingIfUnknown(
                usageId,
                normalizedCost,
                normalizedCurrency,
                normalizedRequestId,
                marginCredits
        ) != 1) {
            BillingUsageLog replay = billingUsageLogMapper.selectByIdForUpdate(usageId);
            validateAttachedProviderAccounting(replay, normalizedCost, normalizedCurrency, normalizedRequestId);
            return false;
        }
        BillingUsageLog updated = billingUsageLogMapper.selectByIdForUpdate(usageId);
        if ("CNY".equals(normalizedCurrency)) {
            billingMetrics.recordLateProviderCost(
                    updated.getSourceType(),
                    updated.getOutcome(),
                    updated.getProvider(),
                    updated.getFailureStage(),
                    updated.getErrorCode(),
                    normalizedCost
            );
        }
        deductManualVendorBalance(updated, modelConfig, normalizedCost);
        return true;
    }

    private Long recordUsageInternal(String idempotencyKey,
                                     String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                                     Integer promptTokens, Integer completionTokens, Integer billableUnits,
                                     Integer chargedCredits, BigDecimal vendorCostAmount,
                                     String providerCostCurrency, BigDecimal markupRatio,
                                     String outcome, String errorCode, String failureStage,
                                     String providerErrorCode, String providerRequestId, Boolean providerCharged) {
        int prompt = nonNegative(promptTokens);
        int completion = nonNegative(completionTokens);
        int units = nonNegative(billableUnits);
        int charged = nonNegative(chargedCredits);
        boolean providerChargeDenied = Boolean.FALSE.equals(providerCharged);
        boolean explicitCostReported = vendorCostAmount != null && !providerChargeDenied;
        BigDecimal explicitVendorCost = vendorCostAmount == null ? BigDecimal.ZERO : vendorCostAmount.max(BigDecimal.ZERO);
        boolean successOutcome = "SUCCESS".equalsIgnoreCase(cleanOutcome(outcome));
        boolean vendorWasCharged = !providerChargeDenied
                && (Boolean.TRUE.equals(providerCharged)
                || successOutcome
                || explicitVendorCost.compareTo(BigDecimal.ZERO) > 0);
        if (prompt == 0 && completion == 0 && units == 0 && charged == 0
                && explicitVendorCost.compareTo(BigDecimal.ZERO) <= 0
                && successOutcome
                && clean(providerRequestId) == null) {
            return null;
        }
        BigDecimal inputPricePer1m = price(modelConfig == null ? null : modelConfig.getInputTokenPricePer1m());
        BigDecimal outputPricePer1m = price(modelConfig == null ? null : modelConfig.getOutputTokenPricePer1m());
        BigDecimal inputPricePer1k = price(modelConfig == null ? null : modelConfig.getInputTokenPricePer1k());
        BigDecimal outputPricePer1k = price(modelConfig == null ? null : modelConfig.getOutputTokenPricePer1k());
        BigDecimal unitPrice = price(modelConfig == null ? null : modelConfig.getUnitPrice());
        String billingUnit = modelConfig == null || modelConfig.getBillingUnit() == null || modelConfig.getBillingUnit().isBlank()
                ? "TOKEN_PER_M"
                : modelConfig.getBillingUnit();
        BillingUsageLog log = new BillingUsageLog();
        log.setIdempotencyKey(idempotencyKey);
        log.setSourceType(sourceType);
        log.setSourceId(sourceId);
        log.setUserId(userId);
        log.setModelConfigId(modelConfig == null ? null : modelConfig.getId());
        log.setProvider(modelConfig == null ? null : modelConfig.getProvider());
        log.setModelName(modelConfig == null ? null : modelConfig.getModelName());
        log.setPromptTokens(prompt);
        log.setCompletionTokens(completion);
        log.setTotalTokens(prompt + completion);
        log.setInputTokenPricePer1k(inputPricePer1k);
        log.setOutputTokenPricePer1k(outputPricePer1k);
        log.setInputTokenPricePer1m(inputPricePer1m);
        log.setOutputTokenPricePer1m(outputPricePer1m);
        log.setBillingUnit(billingUnit);
        log.setBillableUnits(units);
        log.setUnitPrice(unitPrice);
        BigDecimal derivedCost = costPerMillion(prompt, inputPricePer1m)
                .add(costPerMillion(completion, outputPricePer1m))
                .add(perUnitCost(billingUnit, units, unitPrice));
        BigDecimal vendorCost = !vendorWasCharged
                ? BigDecimal.ZERO
                : (explicitCostReported ? explicitVendorCost : (successOutcome ? derivedCost : BigDecimal.ZERO));
        int costCredits = costToCredits(vendorCost);
        // charged_credits now reflects the real user-facing charge (incl. markup) so that revenue
        // and profitability can be aggregated directly; vendor cost stays in the *_cost columns.
        int finalCharge = charged > 0 ? charged : (successOutcome ? costCredits : 0);
        log.setCostAmount(vendorCost);
        log.setVendorCostAmount(vendorCost);
        log.setProviderCostCurrency(normalizeProviderCostCurrency(providerCostCurrency));
        log.setChargedCredits(finalCharge);
        log.setCustomerChargeCredits(finalCharge);
        log.setMarginCredits(Math.max(0, finalCharge - costCredits));
        log.setMarkupRatio(markupRatio == null ? BigDecimal.ZERO : markupRatio);
        log.setOutcome(cleanOutcome(outcome));
        log.setErrorCode(cleanNullable(errorCode, 64));
        log.setFailureStage(cleanNullable(failureStage, 64));
        log.setProviderErrorCode(cleanNullable(providerErrorCode, 128));
        log.setProviderRequestId(cleanNullable(providerRequestId, 128));
        log.setProviderCharged(vendorWasCharged);
        log.setCreatedAt(LocalDateTime.now());
        billingUsageLogMapper.insert(log);
        billingMetrics.recordUsage(sourceType, log.getOutcome(), log.getProvider(), billingUnit, vendorCost,
                finalCharge, log.getFailureStage(), log.getErrorCode(), log.getModelName(), vendorWasCharged);
        if (vendorWasCharged) {
            deductManualVendorBalance(log, modelConfig, vendorCost);
        }
        return log.getId();
    }

    private void validateUsageReplay(BillingUsageLog existing,
                                     String sourceType,
                                     Long sourceId,
                                     Long userId,
                                     Integer promptTokens,
                                     Integer completionTokens,
                                     Integer billableUnits,
                                     Integer chargedCredits,
                                     BigDecimal vendorCostAmount,
                                     String providerCostCurrency,
                                     String outcome,
                                     String errorCode,
                                     String failureStage,
                                     String providerErrorCode,
                                     String providerRequestId,
                                     Boolean providerCharged) {
        boolean providerChargeDenied = Boolean.FALSE.equals(providerCharged);
        BigDecimal expectedCost = providerChargeDenied || vendorCostAmount == null
                ? BigDecimal.ZERO
                : vendorCostAmount.max(BigDecimal.ZERO);
        BigDecimal existingCost = existing.getVendorCostAmount() == null
                ? BigDecimal.ZERO
                : existing.getVendorCostAmount();
        String expectedOutcome = cleanOutcome(outcome);
        boolean expectedProviderCharged = !providerChargeDenied
                && (Boolean.TRUE.equals(providerCharged)
                || "SUCCESS".equals(expectedOutcome)
                || expectedCost.compareTo(BigDecimal.ZERO) > 0);
        boolean matches = java.util.Objects.equals(sourceType, existing.getSourceType())
                && java.util.Objects.equals(sourceId, existing.getSourceId())
                && java.util.Objects.equals(userId, existing.getUserId())
                && nonNegative(promptTokens) == nonNegative(existing.getPromptTokens())
                && nonNegative(completionTokens) == nonNegative(existing.getCompletionTokens())
                && nonNegative(billableUnits) == nonNegative(existing.getBillableUnits())
                && nonNegative(chargedCredits) == nonNegative(existing.getChargedCredits())
                && expectedCost.compareTo(existingCost) == 0
                && java.util.Objects.equals(
                        normalizeProviderCostCurrency(providerCostCurrency),
                        normalizeProviderCostCurrency(existing.getProviderCostCurrency())
                )
                && java.util.Objects.equals(expectedOutcome, existing.getOutcome())
                && java.util.Objects.equals(cleanNullable(errorCode, 64), existing.getErrorCode())
                && java.util.Objects.equals(cleanNullable(failureStage, 64), existing.getFailureStage())
                && java.util.Objects.equals(cleanNullable(providerErrorCode, 128), existing.getProviderErrorCode())
                && java.util.Objects.equals(cleanNullable(providerRequestId, 128), existing.getProviderRequestId())
                && expectedProviderCharged == Boolean.TRUE.equals(existing.getProviderCharged());
        if (!matches) {
            throw new IllegalStateException("Billing usage idempotency key conflicts with an existing record");
        }
    }

    private void validateAttachedProviderAccounting(BillingUsageLog existing,
                                                    BigDecimal providerCostAmount,
                                                    String providerCostCurrency,
                                                    String providerRequestId) {
        boolean matches = existing != null
                && Boolean.TRUE.equals(existing.getProviderCharged())
                && providerCostAmount.compareTo(zeroIfNull(existing.getVendorCostAmount())) == 0
                && providerCostAmount.compareTo(zeroIfNull(existing.getCostAmount())) == 0
                && java.util.Objects.equals(
                        providerCostCurrency,
                        normalizeProviderCostCurrency(existing.getProviderCostCurrency())
                )
                && (providerRequestId == null
                || java.util.Objects.equals(providerRequestId, existing.getProviderRequestId()));
        if (!matches) {
            throw new IllegalStateException("Actual provider accounting conflicts with existing billing usage");
        }
    }

    private void deductManualVendorBalance(BillingUsageLog log, AgentModelConfig modelConfig, BigDecimal vendorCost) {
        if (log == null || log.getId() == null) {
            return;
        }
        Long vendorAccountId = resolveVendorAccountId(modelConfig);
        if (vendorAccountId == null) {
            return;
        }
        BigDecimal deductedAmount = vendorCost == null ? BigDecimal.ZERO : vendorCost.max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
        if (deductedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveByIdForUpdate(vendorAccountId);
        if (account == null || !"MANUAL".equalsIgnoreCase(defaultString(account.getBalanceQueryMode()))) {
            return;
        }
        BigDecimal before = account.getBalanceAmount();
        if (before == null) {
            return;
        }
        BigDecimal after = before.subtract(deductedAmount).setScale(6, RoundingMode.HALF_UP);
        account.setBalanceAmount(after);
        account.setBalanceUpdatedAt(LocalDateTime.now());
        account.setBalanceErrorMessage(null);
        account.setBalanceStatus(resolveBalanceStatus(after, account.getBalanceLowThreshold()));
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        vendorBalanceAdjustmentMapper.insertAdjustment(
                log.getId(),
                account.getId(),
                before,
                after,
                deductedAmount,
                account.getBalanceCurrency() == null || account.getBalanceCurrency().isBlank()
                        ? "CNY"
                        : account.getBalanceCurrency()
        );
    }

    private Long resolveVendorAccountId(AgentModelConfig modelConfig) {
        if (modelConfig == null) {
            return null;
        }
        if (modelConfig.getVendorAccountId() != null) {
            return modelConfig.getVendorAccountId();
        }
        if (modelConfig.getId() == null) {
            return null;
        }
        AgentModelConfig current = modelConfigMapper.findActiveById(modelConfig.getId());
        return current == null ? null : current.getVendorAccountId();
    }

    private String resolveBalanceStatus(BigDecimal amount, BigDecimal lowThreshold) {
        if (amount == null) {
            return "UNKNOWN";
        }
        if (lowThreshold != null && amount.compareTo(lowThreshold) < 0) {
            return "LOW";
        }
        return "OK";
    }

    private BigDecimal costPerMillion(int tokens, BigDecimal pricePer1m) {
        return pricePer1m.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal perUnitCost(String billingUnit, int units, BigDecimal unitPrice) {
        if ((!BILLING_UNIT_PER_CALL.equals(billingUnit)
                && !BILLING_UNIT_PER_SECOND.equals(billingUnit)
                && !BILLING_UNIT_PER_CHARACTER.equals(billingUnit)) || units <= 0) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(units)).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int costToCredits(BigDecimal costAmount) {
        if (costAmount == null || costAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return costAmount.divide(CREDIT_PRICE_CNY, 0, RoundingMode.CEILING).intValue();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String cleanOutcome(String value) {
        String normalized = clean(value);
        return normalized == null ? "SUCCESS" : normalized.toUpperCase();
    }

    private String cleanNullable(String value, int maxLength) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String normalizeProviderCostCurrency(String value) {
        String normalized = clean(value);
        normalized = normalized == null ? "CNY" : normalized.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{3,8}")) {
            throw new IllegalArgumentException("Provider cost currency must be a 3-8 character currency code");
        }
        return normalized;
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }
}
