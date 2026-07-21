package com.aiminilab.aitoolmarket.workflow.mapper;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepCharge;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkflowStepChargeMapper extends BaseMapper<WorkflowStepCharge> {

    @Select("SELECT * FROM workflow_step_charges WHERE attempt_id = #{attemptId} LIMIT 1")
    WorkflowStepCharge selectByAttemptId(@Param("attemptId") Long attemptId);

    @Select("SELECT * FROM workflow_step_charges WHERE attempt_id = #{attemptId} LIMIT 1 FOR UPDATE")
    WorkflowStepCharge selectByAttemptIdForUpdate(@Param("attemptId") Long attemptId);

    @Select("SELECT * FROM workflow_step_charges WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    WorkflowStepCharge selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE workflow_step_charges
            SET attempt_id = #{attemptId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId} AND attempt_id IS NULL AND status = 'RESERVED'
            """)
    int bindAttempt(@Param("chargeId") Long chargeId, @Param("attemptId") Long attemptId);

    @Update("""
            UPDATE workflow_step_charges
            SET status = 'AWAITING_FUNDS',
                settlement_payload_json = #{settlementPayloadJson},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId} AND status = 'RESERVED'
            """)
    int markAwaitingFunds(@Param("chargeId") Long chargeId,
                          @Param("settlementPayloadJson") String settlementPayloadJson);

    @Update("""
            UPDATE workflow_step_charges
            SET status = 'CAPTURED',
                charged_credits = #{chargedCredits},
                provider_cost = #{providerCost},
                provider_cost_currency = #{providerCostCurrency},
                billing_usage_id = #{billingUsageId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId} AND status IN ('RESERVED', 'AWAITING_FUNDS')
            """)
    int markCaptured(@Param("chargeId") Long chargeId,
                     @Param("chargedCredits") int chargedCredits,
                     @Param("providerCost") java.math.BigDecimal providerCost,
                     @Param("providerCostCurrency") String providerCostCurrency,
                     @Param("billingUsageId") Long billingUsageId);

    @Update("""
            UPDATE workflow_step_charges
            SET provider_cost = #{providerCost},
                provider_cost_currency = #{providerCostCurrency},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId}
              AND status = 'CAPTURED'
              AND billing_usage_id = #{billingUsageId}
              AND provider_cost IS NULL
              AND UPPER(TRIM(provider_cost_currency)) = 'UNKNOWN'
            """)
    int attachActualProviderAccountingToCaptured(@Param("chargeId") Long chargeId,
                                                  @Param("billingUsageId") Long billingUsageId,
                                                  @Param("providerCost") BigDecimal providerCost,
                                                  @Param("providerCostCurrency") String providerCostCurrency);

    @Update("""
            UPDATE workflow_step_charges
            SET status = 'RELEASED',
                provider_cost = #{providerCost},
                provider_cost_currency = CASE
                  WHEN #{billingUsageId} IS NOT NULL AND provider_cost_currency IS NULL
                    THEN #{providerCostCurrency}
                  WHEN #{providerCost} IS NULL THEN provider_cost_currency
                  ELSE #{providerCostCurrency}
                END,
                billing_usage_id = #{billingUsageId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId} AND status IN ('RESERVED', 'AWAITING_FUNDS')
            """)
    int markReleased(@Param("chargeId") Long chargeId,
                     @Param("providerCost") java.math.BigDecimal providerCost,
                     @Param("providerCostCurrency") String providerCostCurrency,
                     @Param("billingUsageId") Long billingUsageId);

    @Update("""
            UPDATE workflow_step_charges
            SET provider_cost_currency = CASE
                  WHEN provider_cost IS NULL
                       AND (#{providerCost} IS NOT NULL OR #{billingUsageId} IS NOT NULL)
                    THEN #{providerCostCurrency}
                  ELSE provider_cost_currency
                END,
                provider_cost = COALESCE(provider_cost, #{providerCost}),
                billing_usage_id = COALESCE(billing_usage_id, #{billingUsageId}),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{chargeId} AND status = 'RELEASED'
            """)
    int attachProviderCostToReleased(@Param("chargeId") Long chargeId,
                                     @Param("providerCost") java.math.BigDecimal providerCost,
                                     @Param("providerCostCurrency") String providerCostCurrency,
                                     @Param("billingUsageId") Long billingUsageId);

    @Select("""
            SELECT COALESCE(SUM(charged_credits), 0)
            FROM workflow_step_charges
            WHERE run_id = #{runId} AND status = 'CAPTURED'
            """)
    int sumCapturedCredits(@Param("runId") Long runId);

    @Select("""
            SELECT COALESCE(SUM(usage_log.charged_credits), 0)
            FROM billing_usage_logs usage_log
            JOIN workflow_step_charges charge_log
              ON charge_log.billing_usage_id = usage_log.id
             AND charge_log.status = 'CAPTURED'
            WHERE usage_log.user_id = #{userId}
              AND usage_log.source_type = 'WORKFLOW_STEP'
              AND usage_log.created_at >= #{dayStart}
              AND usage_log.created_at < #{nextDayStart}
            """)
    long sumCapturedCreditsForUserBetween(@Param("userId") Long userId,
                                          @Param("dayStart") LocalDateTime dayStart,
                                          @Param("nextDayStart") LocalDateTime nextDayStart);

    @Select("""
            SELECT
              COALESCE((
                SELECT SUM(usage_log.charged_credits)
                FROM billing_usage_logs usage_log
                JOIN workflow_step_charges captured_charge
                  ON captured_charge.billing_usage_id = usage_log.id
                 AND captured_charge.status = 'CAPTURED'
                WHERE captured_charge.user_id = #{userId}
                  AND usage_log.user_id = #{userId}
                  AND usage_log.source_type = 'WORKFLOW_STEP'
                  AND usage_log.created_at >= #{dayStart}
                  AND usage_log.created_at < #{nextDayStart}
              ), 0)
              + COALESCE((
                SELECT SUM(reserved_charge.reserved_credits)
                FROM workflow_step_charges reserved_charge
                WHERE reserved_charge.user_id = #{userId}
                  AND reserved_charge.status IN ('RESERVED', 'AWAITING_FUNDS')
              ), 0)
            """)
    long sumCommittedCreditsForUserBetween(@Param("userId") Long userId,
                                           @Param("dayStart") LocalDateTime dayStart,
                                           @Param("nextDayStart") LocalDateTime nextDayStart);

    @Select("""
            SELECT COALESCE(SUM(usage_log.vendor_cost_amount), 0)
            FROM billing_usage_logs usage_log
            WHERE usage_log.source_type = 'WORKFLOW_STEP'
              AND usage_log.provider_charged = 1
              AND UPPER(TRIM(usage_log.provider_cost_currency)) = 'CNY'
              AND usage_log.created_at >= #{dayStart}
              AND usage_log.created_at < #{nextDayStart}
            """)
    BigDecimal sumProviderCostBetween(@Param("dayStart") LocalDateTime dayStart,
                                      @Param("nextDayStart") LocalDateTime nextDayStart);

    @Select("""
            SELECT COUNT(*)
            FROM billing_usage_logs usage_log
            WHERE usage_log.source_type = 'WORKFLOW_STEP'
              AND usage_log.provider_charged = 1
              AND (
                usage_log.provider_cost_currency IS NULL
                OR TRIM(usage_log.provider_cost_currency) = ''
                OR UPPER(TRIM(usage_log.provider_cost_currency)) <> 'CNY'
              )
              AND usage_log.created_at >= #{dayStart}
              AND usage_log.created_at < #{nextDayStart}
            """)
    int countUnsupportedProviderCostCurrenciesBetween(@Param("dayStart") LocalDateTime dayStart,
                                                       @Param("nextDayStart") LocalDateTime nextDayStart);

    @Select("""
            SELECT COUNT(*)
            FROM billing_usage_logs usage_log
            WHERE usage_log.source_type = 'WORKFLOW_STEP'
              AND usage_log.outcome IN ('SUCCESS', 'CANCELLED_LATE_SUCCESS')
              AND (
                usage_log.provider_charged = 0
                OR usage_log.provider_cost_currency IS NULL
                OR TRIM(usage_log.provider_cost_currency) = ''
                OR UPPER(TRIM(usage_log.provider_cost_currency)) = 'UNKNOWN'
              )
              AND usage_log.created_at >= #{dayStart}
              AND usage_log.created_at < #{nextDayStart}
            """)
    int countUnknownProviderCostsBetween(@Param("dayStart") LocalDateTime dayStart,
                                         @Param("nextDayStart") LocalDateTime nextDayStart);

    @Select("""
            SELECT CASE WHEN EXISTS (
              SELECT 1
              FROM workflow_runs run_log
              WHERE run_log.id = #{runId}
                AND run_log.provider_cost_reserved_cny > 0
                AND (
                    SELECT COALESCE(SUM(usage_log.vendor_cost_amount), 0)
                    FROM workflow_step_charges charge_log
                    JOIN billing_usage_logs usage_log
                      ON usage_log.id = charge_log.billing_usage_id
                     AND usage_log.source_type = 'WORKFLOW_STEP'
                     AND usage_log.provider_charged = 1
                     AND UPPER(TRIM(usage_log.provider_cost_currency)) = 'CNY'
                    WHERE charge_log.run_id = run_log.id
                ) > run_log.provider_cost_reserved_cny
            ) THEN 1 ELSE 0 END
            """)
    int countProviderCostReservationOverruns(@Param("runId") Long runId);

    @Select("""
            SELECT COUNT(*)
            FROM workflow_step_charges
            WHERE run_id = #{runId} AND status IN ('RESERVED', 'AWAITING_FUNDS')
            """)
    int countReserved(@Param("runId") Long runId);

    @Select("""
            SELECT COUNT(*)
            FROM workflow_step_attempts attempt_log
            JOIN workflow_run_steps step_log ON step_log.id = attempt_log.step_id
            WHERE step_log.run_id = #{runId}
              AND attempt_log.status = 'LOST'
            """)
    int countLostAttempts(@Param("runId") Long runId);

    @Select("""
            SELECT COALESCE(SUM(usage_log.charged_credits), 0)
            FROM workflow_step_charges charge_log
            JOIN billing_usage_logs usage_log ON usage_log.id = charge_log.billing_usage_id
            WHERE charge_log.run_id = #{runId}
            """)
    int sumUsageCredits(@Param("runId") Long runId);

    @Select("""
            SELECT COUNT(*)
            FROM workflow_step_charges charge_log
            LEFT JOIN workflow_step_attempts attempt_log ON attempt_log.id = charge_log.attempt_id
            WHERE charge_log.run_id = #{runId}
              AND (
                (charge_log.status IN ('RESERVED', 'AWAITING_FUNDS') AND (
                  charge_log.billing_usage_id IS NOT NULL
                  OR charge_log.charged_credits <> 0
                  OR charge_log.provider_cost IS NOT NULL
                  OR (charge_log.status = 'RESERVED' AND charge_log.settlement_payload_json IS NOT NULL)
                  OR (charge_log.status = 'AWAITING_FUNDS' AND (
                    charge_log.settlement_payload_json IS NULL
                    OR TRIM(charge_log.settlement_payload_json) = ''
                    OR COALESCE(attempt_log.status, '') <> 'SUCCESS'
                  ))
                  OR EXISTS (
                    SELECT 1 FROM billing_usage_logs unexpected_usage
                    WHERE unexpected_usage.idempotency_key = CONCAT(charge_log.idempotency_key, ':usage')
                  )
                ))
                OR (charge_log.status = 'CAPTURED' AND (
                  charge_log.billing_usage_id IS NULL
                  OR NOT EXISTS (
                    SELECT 1 FROM billing_usage_logs usage_log
                    WHERE usage_log.id = charge_log.billing_usage_id
                      AND usage_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':usage')
                      AND usage_log.user_id = charge_log.user_id
                      AND usage_log.source_type = 'WORKFLOW_STEP'
                      AND usage_log.source_id = charge_log.step_id
                      AND usage_log.charged_credits = charge_log.charged_credits
                      AND usage_log.customer_charge_credits = charge_log.charged_credits
                      AND usage_log.outcome = 'SUCCESS'
                      AND (charge_log.provider_cost IS NULL OR usage_log.provider_charged = 1)
                      AND COALESCE(usage_log.cost_amount, 0) = COALESCE(charge_log.provider_cost, 0)
                      AND COALESCE(usage_log.vendor_cost_amount, 0) = COALESCE(charge_log.provider_cost, 0)
                      AND COALESCE(usage_log.provider_cost_currency, '') = COALESCE(charge_log.provider_cost_currency, '')
                      AND (attempt_log.provider_request_id IS NULL
                           OR usage_log.provider_request_id = attempt_log.provider_request_id)
                  )
                  OR (charge_log.provider_cost IS NOT NULL
                      AND COALESCE(charge_log.provider_cost_currency, '') = '')
                ))
                OR (charge_log.status = 'RELEASED' AND (
                  charge_log.charged_credits <> 0
                  OR (charge_log.billing_usage_id IS NULL AND (
                    charge_log.provider_cost IS NOT NULL
                    OR EXISTS (
                      SELECT 1 FROM billing_usage_logs unexpected_usage
                      WHERE unexpected_usage.idempotency_key = CONCAT(charge_log.idempotency_key, ':usage')
                    )
                  ))
                  OR (charge_log.billing_usage_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM billing_usage_logs usage_log
                    WHERE usage_log.id = charge_log.billing_usage_id
                      AND usage_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':usage')
                      AND usage_log.user_id = charge_log.user_id
                      AND usage_log.source_type = 'WORKFLOW_STEP'
                      AND usage_log.source_id = charge_log.step_id
                      AND usage_log.charged_credits = 0
                      AND usage_log.customer_charge_credits = 0
                      AND usage_log.outcome IN ('FAILED', 'CANCELLED_LATE_SUCCESS')
                      AND (charge_log.provider_cost IS NULL OR usage_log.provider_charged = 1)
                      AND COALESCE(usage_log.cost_amount, 0) = COALESCE(charge_log.provider_cost, 0)
                      AND COALESCE(usage_log.vendor_cost_amount, 0) = COALESCE(charge_log.provider_cost, 0)
                      AND COALESCE(usage_log.provider_cost_currency, '') = COALESCE(charge_log.provider_cost_currency, '')
                      AND (attempt_log.provider_request_id IS NULL
                           OR usage_log.provider_request_id = attempt_log.provider_request_id)
                  ))
                  OR (charge_log.provider_cost IS NOT NULL
                      AND COALESCE(charge_log.provider_cost_currency, '') = '')
                ))
              )
            """)
    int countInvalidUsageBindings(@Param("runId") Long runId);

    @Select("""
            SELECT COALESCE(SUM(
              COALESCE((
                SELECT capture_log.amount
                FROM credit_logs capture_log
                WHERE capture_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':capture')
              ), 0)
              + COALESCE((
                SELECT shortfall_log.amount
                FROM credit_logs shortfall_log
                WHERE shortfall_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':shortfall')
              ), 0)
            ), 0)
            FROM workflow_step_charges charge_log
            WHERE charge_log.run_id = #{runId}
            """)
    int sumCreditDeductions(@Param("runId") Long runId);

    @Select("""
            SELECT COUNT(*)
            FROM workflow_step_charges charge_log
            WHERE charge_log.run_id = #{runId}
              AND (
                NOT EXISTS (
                  SELECT 1 FROM credit_logs reserve_log
                  WHERE reserve_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':reserve')
                    AND reserve_log.user_id = charge_log.user_id
                    AND reserve_log.source_type = 'WORKFLOW_STEP'
                    AND reserve_log.source_ref = charge_log.step_id
                    AND reserve_log.log_type = 'FREEZE'
                    AND reserve_log.amount = 0
                    AND reserve_log.frozen_amount = charge_log.reserved_credits
                    AND reserve_log.frozen_after - reserve_log.frozen_before = charge_log.reserved_credits
                )
                OR (charge_log.status = 'CAPTURED' AND NOT EXISTS (
                  SELECT 1 FROM credit_logs capture_log
                  WHERE capture_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':capture')
                    AND capture_log.user_id = charge_log.user_id
                    AND capture_log.source_type = 'WORKFLOW_STEP'
                    AND capture_log.source_ref = charge_log.step_id
                    AND capture_log.log_type = 'DEDUCT'
                    AND capture_log.amount = LEAST(charge_log.charged_credits, charge_log.reserved_credits)
                    AND capture_log.frozen_amount = -charge_log.reserved_credits
                    AND capture_log.frozen_after - capture_log.frozen_before = -charge_log.reserved_credits
                    AND capture_log.balance_before - capture_log.balance_after = LEAST(charge_log.charged_credits, charge_log.reserved_credits)
                ))
                OR (charge_log.status = 'CAPTURED'
                    AND charge_log.charged_credits > charge_log.reserved_credits
                    AND NOT EXISTS (
                      SELECT 1 FROM credit_logs shortfall_log
                      WHERE shortfall_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':shortfall')
                        AND shortfall_log.user_id = charge_log.user_id
                        AND shortfall_log.source_type = 'WORKFLOW_STEP'
                        AND shortfall_log.source_ref = charge_log.step_id
                        AND shortfall_log.log_type = 'DEDUCT'
                        AND shortfall_log.amount = charge_log.charged_credits - charge_log.reserved_credits
                        AND shortfall_log.balance_before - shortfall_log.balance_after = shortfall_log.amount
                        AND (
                          (shortfall_log.frozen_amount = 0
                           AND shortfall_log.frozen_before = shortfall_log.frozen_after)
                          OR (
                            shortfall_log.frozen_amount = -shortfall_log.amount
                            AND shortfall_log.frozen_before - shortfall_log.frozen_after = shortfall_log.amount
                            AND EXISTS (
                              SELECT 1 FROM credit_logs shortfall_reserve_log
                              WHERE shortfall_reserve_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':shortfall-reserve')
                                AND shortfall_reserve_log.user_id = charge_log.user_id
                                AND shortfall_reserve_log.source_type = 'WORKFLOW_STEP'
                                AND shortfall_reserve_log.source_ref = charge_log.step_id
                                AND shortfall_reserve_log.log_type = 'FREEZE'
                                AND shortfall_reserve_log.amount = 0
                                AND shortfall_reserve_log.frozen_amount = shortfall_log.amount
                                AND shortfall_reserve_log.frozen_after - shortfall_reserve_log.frozen_before = shortfall_log.amount
                            )
                          )
                        )
                    ))
                OR (charge_log.status = 'RELEASED' AND NOT EXISTS (
                  SELECT 1 FROM credit_logs release_log
                  WHERE release_log.idempotency_key = CONCAT(charge_log.idempotency_key, ':release')
                    AND release_log.user_id = charge_log.user_id
                    AND release_log.source_type = 'WORKFLOW_STEP'
                    AND release_log.source_ref = charge_log.step_id
                    AND release_log.log_type = 'RELEASE'
                    AND release_log.amount = 0
                    AND release_log.frozen_amount = -charge_log.reserved_credits
                    AND release_log.frozen_after - release_log.frozen_before = -charge_log.reserved_credits
                    AND release_log.balance_before = release_log.balance_after
                ))
                OR charge_log.status NOT IN ('RESERVED', 'AWAITING_FUNDS', 'CAPTURED', 'RELEASED')
              )
            """)
    int countInvalidCreditTransitions(@Param("runId") Long runId);
}
