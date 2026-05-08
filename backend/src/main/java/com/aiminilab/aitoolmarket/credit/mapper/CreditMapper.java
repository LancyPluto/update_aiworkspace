package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.task.dto.CreditLogResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    private final RowMapper<CreditLogResponse> logRowMapper = (rs, rowNum) -> new CreditLogResponse(
            rs.getLong("id"),
            rs.getString("log_type"),
            rs.getInt("amount"),
            rs.getInt("balance_before"),
            rs.getInt("balance_after"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

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

    public Optional<CreditAccount> findOptionalAccountByUserId(Long userId) {
        return findByUserId(userId);
    }

    public boolean deduct(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance - ?, total_consumed = total_consumed + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND balance >= ? AND status = 'ACTIVE'
                """, amount, amount, accountId, amount) == 1;
    }

    public void increaseBalance(Long accountId, int amount) {
        jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance + ?, total_granted = total_granted + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, amount, amount, accountId);
    }

    public void insertDeductLog(CreditAccount before, Long taskId, int amount) {
        jdbcTemplate.update("""
                INSERT INTO credit_logs
                  (user_id, account_id, task_id, log_type, amount, frozen_amount,
                   balance_before, balance_after, frozen_before, frozen_after,
                   operator_type, reason)
                VALUES (?, ?, ?, 'DEDUCT', ?, 0, ?, ?, ?, ?, 'SYSTEM', ?)
                """,
                before.getUserId(),
                before.getId(),
                taskId,
                amount,
                before.getBalance(),
                before.getBalance() - amount,
                before.getFrozen(),
                before.getFrozen(),
                "Create AI task");
    }

    public void insertManualAddLog(CreditAccount before, int amount, Long operatorId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO credit_logs
                  (user_id, account_id, task_id, log_type, amount, frozen_amount,
                   balance_before, balance_after, frozen_before, frozen_after,
                   operator_type, operator_id, reason)
                VALUES (?, ?, NULL, 'MANUAL_ADD', ?, 0, ?, ?, ?, ?, 'ADMIN', ?, ?)
                """,
                before.getUserId(),
                before.getId(),
                amount,
                before.getBalance(),
                before.getBalance() + amount,
                before.getFrozen(),
                before.getFrozen(),
                operatorId,
                reason);
    }

    public List<CreditLogResponse> findLogsByTaskId(Long taskId) {
        return jdbcTemplate.query("""
                SELECT id, log_type, amount, balance_before, balance_after, reason, created_at
                FROM credit_logs
                WHERE task_id = ?
                ORDER BY id ASC
                """, logRowMapper, taskId);
    }

    public Integer findConsumedByTaskId(Long taskId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM credit_logs
                WHERE task_id = ? AND log_type = 'DEDUCT'
                """, Integer.class, taskId);
        return total == null ? 0 : total;
    }

    private Optional<CreditAccount> findByUserId(Long userId) {
        List<CreditAccount> accounts = jdbcTemplate.query("""
                SELECT * FROM credit_accounts
                WHERE user_id = ?
                """, accountRowMapper, userId);
        return accounts.stream().findFirst();
    }
}
