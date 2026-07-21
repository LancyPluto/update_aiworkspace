package com.aiminilab.aitoolmarket.task.routing.mapper;

import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AccountModelRouteStateMapper extends BaseMapper<AccountModelRouteState> {

    @Insert("""
            INSERT INTO account_model_route_state(
                vendor_account_id, model_config_id, in_flight_count, circuit_status,
                consecutive_failures, version, created_at, updated_at
            ) VALUES(
                #{vendorAccountId}, #{modelConfigId}, 0, 'CLOSED', 0, 0,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON DUPLICATE KEY UPDATE
                vendor_account_id = VALUES(vendor_account_id),
                updated_at = CURRENT_TIMESTAMP
            """)
    int insertIfAbsent(@Param("vendorAccountId") Long vendorAccountId,
                       @Param("modelConfigId") Long modelConfigId);

    @Select("""
            <script>
            SELECT *
            FROM account_model_route_state
            WHERE model_config_id IN
            <foreach collection="modelConfigIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY model_config_id ASC
            FOR UPDATE
            </script>
            """)
    List<AccountModelRouteState> findByModelConfigIdsForUpdate(
            @Param("modelConfigIds") List<Long> modelConfigIds);

    @Update("""
            UPDATE account_model_route_state
            SET in_flight_count = in_flight_count + 1,
                circuit_status = CASE
                    WHEN circuit_status = 'OPEN'
                         AND (cooldown_until IS NULL OR cooldown_until <= CURRENT_TIMESTAMP)
                    THEN 'HALF_OPEN'
                    ELSE circuit_status
                END,
                last_selected_at = CURRENT_TIMESTAMP,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND version = #{expectedVersion}
            """)
    int reserve(@Param("id") Long id, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE account_model_route_state
            SET in_flight_count = GREATEST(0, in_flight_count - 1),
                circuit_status = 'CLOSED',
                consecutive_failures = 0,
                cooldown_until = NULL,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int releaseSuccess(@Param("id") Long id);

    @Update("""
            UPDATE account_model_route_state
            SET in_flight_count = GREATEST(0, in_flight_count - 1),
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int releaseNeutral(@Param("id") Long id);

    @Update("""
            UPDATE account_model_route_state
            SET in_flight_count = GREATEST(0, in_flight_count - 1),
                consecutive_failures = #{consecutiveFailures},
                circuit_status = #{circuitStatus},
                cooldown_until = #{cooldownUntil},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int releaseFailure(@Param("id") Long id,
                       @Param("circuitStatus") String circuitStatus,
                       @Param("cooldownUntil") LocalDateTime cooldownUntil,
                       @Param("consecutiveFailures") int consecutiveFailures);

    @Update("""
            UPDATE account_model_route_state
            SET circuit_status = 'CLOSED',
                consecutive_failures = 0,
                cooldown_until = NULL,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE vendor_account_id = #{vendorAccountId}
              AND (circuit_status <> 'CLOSED'
                   OR consecutive_failures <> 0
                   OR cooldown_until IS NOT NULL)
            """)
    int recoverByVendorAccountId(@Param("vendorAccountId") Long vendorAccountId);

    @Update("""
            UPDATE account_model_route_state
            SET circuit_status = 'CLOSED',
                consecutive_failures = 0,
                cooldown_until = NULL,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE model_config_id = #{modelConfigId}
              AND (circuit_status <> 'CLOSED'
                   OR consecutive_failures <> 0
                   OR cooldown_until IS NOT NULL)
            """)
    int recoverByModelConfigId(@Param("modelConfigId") Long modelConfigId);

    @Update("""
            UPDATE account_model_route_state state
            LEFT JOIN (
                SELECT attempt.model_config_id, COUNT(1) AS active_count
                FROM task_model_route_attempts attempt
                JOIN ai_tasks task ON task.id = attempt.task_id
                WHERE attempt.status = 'ACTIVE'
                  AND task.status NOT IN ('SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')
                GROUP BY attempt.model_config_id
            ) active ON active.model_config_id = state.model_config_id
            SET state.in_flight_count = COALESCE(active.active_count, 0),
                state.version = state.version + 1,
                state.updated_at = CURRENT_TIMESTAMP
            WHERE state.in_flight_count <> COALESCE(active.active_count, 0)
            """)
    int reconcileInFlightCounts();
}
