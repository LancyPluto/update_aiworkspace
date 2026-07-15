package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
        account.setPermanentBalance(DEFAULT_GRANTED_CREDITS);
        account.setMembershipBalance(0);
        account.setGiftBalance(0);
        account.setFrozen(0);
        account.setPermanentFrozen(0);
        account.setMembershipFrozen(0);
        account.setGiftFrozen(0);
        account.setExpiredMembershipFrozen(0);
        account.setTotalExpired(0);
        account.setBucketSchemaVersion(2);
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
            SET balance = balance + #{amount},
                permanent_balance = permanent_balance + #{amount},
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
                permanent_balance = permanent_balance + #{amount},
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
            SET balance = balance + #{amount},
                membership_balance = membership_balance + #{amount},
                total_granted = total_granted + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND status = 'ACTIVE'
            """)
    int membershipRechargeAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean membershipRechargeAdd(Long accountId, int amount) {
        return membershipRechargeAddRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET balance = balance + #{amount},
                gift_balance = gift_balance + #{amount},
                total_granted = total_granted + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND status = 'ACTIVE'
            """)
    int giftRedeemAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean giftRedeemAdd(Long accountId, int amount) {
        return giftRedeemAddRows(accountId, amount) == 1;
    }

    @Select("SELECT * FROM credit_accounts WHERE id = #{accountId} FOR UPDATE")
    CreditAccount lockById(@Param("accountId") Long accountId);

    @Update("""
            UPDATE credit_accounts
            SET balance = balance + #{amount},
                permanent_balance = permanent_balance + #{amount},
                total_granted = total_granted + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND status = 'ACTIVE'
            """)
    int referralBonusAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean referralBonusAdd(Long accountId, int amount) {
        return referralBonusAddRows(accountId, amount) == 1;
    }

}
