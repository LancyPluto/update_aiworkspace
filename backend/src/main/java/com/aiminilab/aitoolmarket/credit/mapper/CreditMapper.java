package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
<<<<<<< HEAD
import com.aiminilab.aitoolmarket.task.dto.CreditLogResponse;
=======
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
>>>>>>> origin/feature/backend-core
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

public interface CreditMapper extends BaseMapper<CreditAccount> {

    int DEFAULT_GRANTED_CREDITS = 100;

<<<<<<< HEAD
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
=======
    default CreditAccount getOrCreateAccount(Long userId) {
>>>>>>> origin/feature/backend-core
        Optional<CreditAccount> existing = findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        CreditAccount account = new CreditAccount();
        account.setUserId(userId);
        account.setBalance(DEFAULT_GRANTED_CREDITS);
        account.setFrozen(0);
        account.setTotalGranted(DEFAULT_GRANTED_CREDITS);
        account.setTotalConsumed(0);
        account.setStatus("ACTIVE");
        try {
            insert(account);
        } catch (DuplicateKeyException ignored) {
            // Another request created the account first; read it below.
        }
        return findByUserId(userId).orElseThrow(() -> new IllegalStateException("Credit account is missing"));
    }

<<<<<<< HEAD
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
=======
    default Optional<CreditAccount> findByUserId(Long userId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<CreditAccount>()
                .eq(CreditAccount::getUserId, userId)
                .last("LIMIT 1")));
    }

    @Update("""
            UPDATE credit_accounts
            SET frozen = frozen + #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int freezeRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean freeze(Long accountId, int amount) {
        return freezeRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET balance = balance - #{amount},
                frozen = frozen - #{amount},
                total_consumed = total_consumed + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND frozen >= #{amount} AND balance >= #{amount} AND status = 'ACTIVE'
            """)
    int settleRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean settle(Long accountId, int amount) {
        return settleRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET frozen = frozen - #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int releaseRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean release(Long accountId, int amount) {
        return releaseRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET balance = balance + #{amount},
                total_granted = total_granted + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND status = 'ACTIVE'
            """)
    int manualAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean manualAdd(Long accountId, int amount) {
        return manualAddRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET balance = balance - #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int manualDeductRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean manualDeduct(Long accountId, int amount) {
        return manualDeductRows(accountId, amount) == 1;
>>>>>>> origin/feature/backend-core
    }
}
