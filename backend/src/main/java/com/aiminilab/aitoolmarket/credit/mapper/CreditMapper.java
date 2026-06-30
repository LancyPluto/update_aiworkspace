package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

public interface CreditMapper extends BaseMapper<CreditAccount> {

    int DEFAULT_GRANTED_CREDITS = 200;

    default CreditAccount getOrCreateAccount(Long userId) {
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
            SET balance = balance - #{amount},
                total_consumed = total_consumed + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int deductAvailableRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean deductAvailable(Long accountId, int amount) {
        return deductAvailableRows(accountId, amount) == 1;
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
            SET balance = balance + #{amount},
                total_granted = total_granted + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND status = 'ACTIVE'
            """)
    int rechargeAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean rechargeAdd(Long accountId, int amount) {
        return rechargeAddRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET balance = balance - #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int manualDeductRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean manualDeduct(Long accountId, int amount) {
        return manualDeductRows(accountId, amount) == 1;
    }
}
