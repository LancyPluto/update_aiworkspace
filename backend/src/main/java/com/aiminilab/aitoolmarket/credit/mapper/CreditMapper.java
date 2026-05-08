package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
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

    public boolean deduct(Long accountId, int amount) {
        return jdbcTemplate.update("""
                UPDATE credit_accounts
                SET balance = balance - ?, total_consumed = total_consumed + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND balance >= ? AND status = 'ACTIVE'
                """, amount, amount, accountId, amount) == 1;
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

    private Optional<CreditAccount> findByUserId(Long userId) {
        List<CreditAccount> accounts = jdbcTemplate.query("""
                SELECT * FROM credit_accounts
                WHERE user_id = ?
                """, accountRowMapper, userId);
        return accounts.stream().findFirst();
    }
}
