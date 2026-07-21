package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ModelVendorAccountMapper extends BaseMapper<ModelVendorAccount> {

    @Select("""
            SELECT account.*,
                   pool.pool_name AS routing_pool_name,
                   COALESCE(route.in_flight_count, 0) AS routing_in_flight_count,
                   COALESCE(route.circuit_status, 'CLOSED') AS routing_circuit_status,
                   route.cooldown_until AS routing_cooldown_until
            FROM model_vendor_accounts account
            LEFT JOIN (
                SELECT vendor_account_id,
                       SUM(in_flight_count) AS in_flight_count,
                       CASE
                           WHEN MAX(CASE WHEN circuit_status = 'OPEN' THEN 1 ELSE 0 END) = 1 THEN 'OPEN'
                           WHEN MAX(CASE WHEN circuit_status = 'HALF_OPEN' THEN 1 ELSE 0 END) = 1 THEN 'HALF_OPEN'
                           ELSE 'CLOSED'
                       END AS circuit_status,
                       MAX(cooldown_until) AS cooldown_until
                FROM account_model_route_state
                GROUP BY vendor_account_id
            ) route ON route.vendor_account_id = account.id
            LEFT JOIN model_account_routing_pools pool ON pool.id = account.routing_pool_id
            WHERE COALESCE(account.is_deleted, 0) = 0
            ORDER BY account.vendor_code ASC, account.id ASC
            """)
    List<ModelVendorAccount> findAllActive();

    @Select("""
            SELECT account.*,
                   pool.pool_name AS routing_pool_name,
                   COALESCE(route.in_flight_count, 0) AS routing_in_flight_count,
                   COALESCE(route.circuit_status, 'CLOSED') AS routing_circuit_status,
                   route.cooldown_until AS routing_cooldown_until
            FROM model_vendor_accounts account
            LEFT JOIN (
                SELECT vendor_account_id,
                       SUM(in_flight_count) AS in_flight_count,
                       CASE
                           WHEN MAX(CASE WHEN circuit_status = 'OPEN' THEN 1 ELSE 0 END) = 1 THEN 'OPEN'
                           WHEN MAX(CASE WHEN circuit_status = 'HALF_OPEN' THEN 1 ELSE 0 END) = 1 THEN 'HALF_OPEN'
                           ELSE 'CLOSED'
                       END AS circuit_status,
                       MAX(cooldown_until) AS cooldown_until
                FROM account_model_route_state
                WHERE vendor_account_id = #{id}
                GROUP BY vendor_account_id
            ) route ON route.vendor_account_id = account.id
            LEFT JOIN model_account_routing_pools pool ON pool.id = account.routing_pool_id
            WHERE account.id = #{id}
              AND COALESCE(account.is_deleted, 0) = 0
            """)
    ModelVendorAccount findActiveById(@Param("id") Long id);

    @Update("""
            UPDATE model_vendor_accounts
            SET load_balance_enabled = #{enabled},
                load_balance_weight = #{weight},
                routing_pool_id = #{routingPoolId},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND COALESCE(is_deleted, 0) = 0
            """)
    int updateRouting(@Param("id") Long id,
                      @Param("enabled") boolean enabled,
                      @Param("weight") int weight,
                      @Param("routingPoolId") Long routingPoolId);

    @Select("""
            SELECT *
            FROM model_vendor_accounts
            WHERE id = #{id}
              AND COALESCE(is_deleted, 0) = 0
            FOR UPDATE
            """)
    ModelVendorAccount findActiveByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT account.*,
                   pool.pool_name AS routing_pool_name,
                   COALESCE(route.in_flight_count, 0) AS routing_in_flight_count,
                   COALESCE(route.circuit_status, 'CLOSED') AS routing_circuit_status,
                   route.cooldown_until AS routing_cooldown_until
            FROM model_vendor_accounts account
            LEFT JOIN (
                SELECT vendor_account_id,
                       SUM(in_flight_count) AS in_flight_count,
                       CASE
                           WHEN MAX(CASE WHEN circuit_status = 'OPEN' THEN 1 ELSE 0 END) = 1 THEN 'OPEN'
                           WHEN MAX(CASE WHEN circuit_status = 'HALF_OPEN' THEN 1 ELSE 0 END) = 1 THEN 'HALF_OPEN'
                           ELSE 'CLOSED'
                       END AS circuit_status,
                       MAX(cooldown_until) AS cooldown_until
                FROM account_model_route_state
                GROUP BY vendor_account_id
            ) route ON route.vendor_account_id = account.id
            LEFT JOIN model_account_routing_pools pool ON pool.id = account.routing_pool_id
            WHERE account.vendor_code = #{vendorCode}
              AND COALESCE(account.is_deleted, 0) = 0
            ORDER BY account.id ASC
            """)
    List<ModelVendorAccount> findActiveByVendorCode(@Param("vendorCode") String vendorCode);

    @Select("""
            SELECT *
            FROM model_vendor_accounts
            WHERE vendor_code = #{vendorCode}
              AND account_name = #{accountName}
              AND COALESCE(is_deleted, 0) = 0
            LIMIT 1
            """)
    ModelVendorAccount findActiveByVendorCodeAndAccountName(@Param("vendorCode") String vendorCode,
                                                            @Param("accountName") String accountName);

    @Select("""
            SELECT COUNT(1)
            FROM agent_model_configs
            WHERE vendor_account_id = #{accountId}
              AND COALESCE(is_deleted, 0) = 0
            """)
    int countActiveModelsByAccountId(@Param("accountId") Long accountId);

    @Insert("""
            INSERT INTO model_vendor_accounts(vendor_code, account_name, base_url, api_key, extra_auth_json,
                                              console_url, balance_url, console_cookie, console_cookie_status,
                                              balance_query_mode, balance_amount,
                                              balance_currency, balance_status, balance_low_threshold,
                                              balance_updated_at, balance_error_message, health_status,
                                              health_message, health_checked_at,
                                              enabled, is_deleted, created_at, updated_at)
            VALUES(#{account.vendorCode}, #{account.accountName}, #{account.baseUrl}, #{account.apiKey},
                   #{account.extraAuthJson}, #{account.consoleUrl}, #{account.balanceUrl},
                   #{account.consoleCookie}, #{account.consoleCookieStatus},
                   #{account.balanceQueryMode}, #{account.balanceAmount}, #{account.balanceCurrency},
                   #{account.balanceStatus}, #{account.balanceLowThreshold}, #{account.balanceUpdatedAt},
                   #{account.balanceErrorMessage}, #{account.healthStatus},
                   #{account.healthMessage}, #{account.healthCheckedAt}, #{account.enabled},
                   COALESCE(#{account.deleted}, 0), #{account.createdAt}, #{account.updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "account.id")
    void insertAccount(@Param("account") ModelVendorAccount account);

    @Update("""
            UPDATE model_vendor_accounts
            SET vendor_code = #{account.vendorCode},
                account_name = #{account.accountName},
                base_url = #{account.baseUrl},
                api_key = #{account.apiKey},
                extra_auth_json = #{account.extraAuthJson},
                console_url = #{account.consoleUrl},
                balance_url = #{account.balanceUrl},
                console_cookie = #{account.consoleCookie},
                console_cookie_status = #{account.consoleCookieStatus},
                balance_query_mode = #{account.balanceQueryMode},
                balance_amount = #{account.balanceAmount},
                balance_currency = #{account.balanceCurrency},
                balance_status = #{account.balanceStatus},
                balance_low_threshold = #{account.balanceLowThreshold},
                balance_updated_at = #{account.balanceUpdatedAt},
                balance_error_message = #{account.balanceErrorMessage},
                health_status = #{account.healthStatus},
                health_message = #{account.healthMessage},
                health_checked_at = #{account.healthCheckedAt},
                enabled = #{account.enabled},
                updated_at = #{account.updatedAt}
            WHERE id = #{account.id}
            """)
    void updateAccount(@Param("account") ModelVendorAccount account);

    @Update("""
            UPDATE model_vendor_accounts
            SET is_deleted = 1,
                enabled = 0,
                updated_at = NOW()
            WHERE id = #{id}
            """)
    void softDelete(@Param("id") Long id);

    @Update("""
            UPDATE model_vendor_accounts
            SET is_deleted = 1,
                enabled = 0,
                updated_at = NOW()
            WHERE vendor_code = #{vendorCode}
              AND COALESCE(is_deleted, 0) = 0
            """)
    int softDeleteByVendorCode(@Param("vendorCode") String vendorCode);
}
