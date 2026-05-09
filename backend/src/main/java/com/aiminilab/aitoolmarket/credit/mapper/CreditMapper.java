package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CreditMapper {

    public static final int DEFAULT_GRANTED_CREDITS = 100;

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<CreditAccount> accountRowMapper = (rs, rowNum) -> {
        CreditAccount account = new CreditAccount();
        account.setId(rs.getLong("id"));
        account.setUserId(rs.getLong("user_id"));
        account.setBalance(rs.getInt("balance"));
        account.setFrozen(rs.getInt("frozen"));
        account.setTotalGranted(rs.getInt("total_granted"));
        account.setTotalConsumed(rs.getInt("total_consumed"));
        account.setStatus(rs.getString("status"));
        return account;
    };

    private final RowMapper<CreditLogResponse> logRowMapper = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CreditLogResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getObject("task_id", Long.class),
                rs.getString("log_type"),
                rs.getInt("amount"),
                rs.getInt("frozen_amount"),
                rs.getInt("balance_before"),
                rs.getInt("balance_after"),
                rs.getInt("frozen_before"),
                rs.getInt("frozen_after"),
                rs.getString("operator_type"),
                rs.getObject("operator_id", Long.class),
                rs.getString("reason"),
                createdAt == null ? null : createdAt.toLocalDateTime()
        );
    };

    public CreditMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CreditAccount getOrCreateAccount(Long userId) {
        Optional<CreditAccount> existing = findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO credit_accounts
                      (user_id, balance, frozen, total_granted, total_consumed, status)
                    VALUES (?, ?, 0, ?, 0, 'ACTIVE')
                    """, userId, DEFAULT_GRANTED_CREDITS, DEFAULT_GRANTED_CREDITS);
        } catch (DuplicateKeyException ignored) {
            // Another request created the account first; read it below.
        }
        return findByUserId(userId).orElseThrow(() -> new IllegalStateException("Credit account is missing"));
    }

    public boolean freeze(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET frozen = frozen + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND balance - frozen >= ? AND status = 'ACTIVE'
                """, amount, accountId, amount) == 1;
    }

    public boolean settle(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance - ?, frozen = frozen - ?, total_consumed = total_consumed + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND frozen >= ? AND balance >= ? AND status = 'ACTIVE'
                """, amount, amount, amount, accountId, amount, amount) == 1;
    }

    public boolean release(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET frozen = frozen - ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND frozen >= ? AND status = 'ACTIVE'
                """, amount, accountId, amount) == 1;
    }

    public boolean manualAdd(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance + ?, total_granted = total_granted + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, amount, amount, accountId) == 1;
    }

    public boolean manualDeduct(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance - ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND balance - frozen >= ? AND status = 'ACTIVE'
                """, amount, accountId, amount) == 1;
    }

    public List<CreditLogResponse> findLogs(Long userId, String logType, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM credit_logs
                WHERE user_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (logType != null && !logType.isBlank()) {
            sql.append(" AND log_type = ?");
            params.add(logType.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), logRowMapper, params.toArray());
    }

    public long countLogs(Long userId, String logType) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM credit_logs
                WHERE user_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (logType != null && !logType.isBlank()) {
            sql.append(" AND log_type = ?");
            params.add(logType.trim());
        }
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    public void insertLog(CreditAccount before, Long taskId, String logType, int amount, int frozenAmount,
                          int balanceAfter, int frozenAfter, String operatorType, Long operatorId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO credit_logs
                  (user_id, account_id, task_id, log_type, amount, frozen_amount,
                   balance_before, balance_after, frozen_before, frozen_after,
                   operator_type, operator_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                before.getUserId(),
                before.getId(),
                taskId,
                logType,
                amount,
                frozenAmount,
                before.getBalance(),
                balanceAfter,
                before.getFrozen(),
                frozenAfter,
                operatorType,
                operatorId,
                reason);
    }

    private Optional<CreditAccount> findByUserId(Long userId) {
        List<CreditAccount> accounts = jdbcTemplate.query("""
                SELECT * FROM credit_accounts
                WHERE user_id = ?
                """, accountRowMapper, userId);
        return accounts.stream().findFirst();
    }
}
