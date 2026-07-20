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

    @Select("SELECT * FROM credit_accounts WHERE user_id = #{userId} FOR UPDATE")
    CreditAccount selectByUserIdForUpdate(@Param("userId") Long userId);

    @Update("""
            UPDATE credit_accounts
            SET gift_frozen = gift_frozen + LEAST(
                    GREATEST(0, gift_balance - gift_frozen),
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))
                        - LEAST(
                            GREATEST(0, #{amount}
                                - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                            GREATEST(0, permanent_balance - permanent_frozen)
                        ))
                ),
                permanent_frozen = permanent_frozen + LEAST(
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                    GREATEST(0, permanent_balance - permanent_frozen)
                ),
                membership_frozen = membership_frozen
                    + LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen)),
                frozen = frozen + #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int freezeRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean freeze(Long accountId, int amount) {
        return freezeRows(accountId, amount) == 1;
    }

    default int settleRows(@Param("accountId") Long accountId, @Param("amount") int amount) {
        return captureReservedRows(accountId, amount, amount);
    }

    default boolean settle(Long accountId, int amount) {
        return settleRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET gift_balance = gift_balance - LEAST(
                    GREATEST(0, gift_balance - gift_frozen),
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))
                        - LEAST(
                            GREATEST(0, #{amount}
                                - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                            GREATEST(0, permanent_balance - permanent_frozen)
                        ))
                ),
                permanent_balance = permanent_balance - LEAST(
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                    GREATEST(0, permanent_balance - permanent_frozen)
                ),
                membership_balance = membership_balance
                    - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen)),
                balance = balance - #{amount},
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
            SET balance = balance - LEAST(#{amount}, expired_membership_frozen),
                gift_frozen = gift_frozen - LEAST(
                    gift_frozen,
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{amount} - LEAST(#{amount}, expired_membership_frozen)), membership_frozen)
                        - LEAST(
                            GREATEST(0, #{amount}
                                - LEAST(#{amount}, expired_membership_frozen)
                                - LEAST(GREATEST(0, #{amount} - LEAST(#{amount}, expired_membership_frozen)), membership_frozen)),
                            permanent_frozen
                        ))
                ),
                permanent_frozen = permanent_frozen - LEAST(
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{amount} - LEAST(#{amount}, expired_membership_frozen)), membership_frozen)),
                    permanent_frozen
                ),
                membership_frozen = membership_frozen - LEAST(
                    GREATEST(0, #{amount} - LEAST(#{amount}, expired_membership_frozen)),
                    membership_frozen
                ),
                expired_membership_frozen = expired_membership_frozen
                    - LEAST(#{amount}, expired_membership_frozen),
                frozen = frozen - #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int releaseRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean release(Long accountId, int amount) {
        return releaseRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET gift_balance = gift_balance - LEAST(
                    gift_balance,
                    gift_frozen,
                    GREATEST(0, #{actualAmount}
                        - LEAST(#{actualAmount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{actualAmount} - LEAST(#{actualAmount}, expired_membership_frozen)), membership_frozen)
                        - LEAST(
                            GREATEST(0, #{actualAmount}
                                - LEAST(#{actualAmount}, expired_membership_frozen)
                                - LEAST(GREATEST(0, #{actualAmount} - LEAST(#{actualAmount}, expired_membership_frozen)), membership_frozen)),
                            permanent_frozen
                        ))
                ),
                permanent_balance = permanent_balance - LEAST(
                    permanent_balance,
                    permanent_frozen,
                    GREATEST(0, #{actualAmount}
                        - LEAST(#{actualAmount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{actualAmount} - LEAST(#{actualAmount}, expired_membership_frozen)), membership_frozen))
                ),
                membership_balance = membership_balance - LEAST(
                    membership_balance,
                    membership_frozen,
                    GREATEST(0, #{actualAmount} - LEAST(#{actualAmount}, expired_membership_frozen))
                ),
                gift_frozen = gift_frozen - LEAST(
                    gift_frozen,
                    GREATEST(0, #{reservedAmount}
                        - LEAST(#{reservedAmount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{reservedAmount} - LEAST(#{reservedAmount}, expired_membership_frozen)), membership_frozen)
                        - LEAST(
                            GREATEST(0, #{reservedAmount}
                                - LEAST(#{reservedAmount}, expired_membership_frozen)
                                - LEAST(GREATEST(0, #{reservedAmount} - LEAST(#{reservedAmount}, expired_membership_frozen)), membership_frozen)),
                            permanent_frozen
                        ))
                ),
                permanent_frozen = permanent_frozen - LEAST(
                    permanent_frozen,
                    GREATEST(0, #{reservedAmount}
                        - LEAST(#{reservedAmount}, expired_membership_frozen)
                        - LEAST(GREATEST(0, #{reservedAmount} - LEAST(#{reservedAmount}, expired_membership_frozen)), membership_frozen))
                ),
                membership_frozen = membership_frozen - LEAST(
                    membership_frozen,
                    GREATEST(0, #{reservedAmount} - LEAST(#{reservedAmount}, expired_membership_frozen))
                ),
                expired_membership_frozen = expired_membership_frozen
                    - LEAST(#{reservedAmount}, expired_membership_frozen),
                balance = balance - #{actualAmount},
                frozen = frozen - #{reservedAmount},
                total_consumed = total_consumed + #{actualAmount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId}
              AND frozen >= #{reservedAmount}
              AND balance >= #{actualAmount}
              AND status = 'ACTIVE'
            """)
    int captureReservedRows(@Param("accountId") Long accountId,
                            @Param("reservedAmount") int reservedAmount,
                            @Param("actualAmount") int actualAmount);

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
    int referralRegistrationBonusAddRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean referralRegistrationBonusAdd(Long accountId, int amount) {
        return referralRegistrationBonusAddRows(accountId, amount) == 1;
    }

    @Update("""
            UPDATE credit_accounts
            SET gift_balance = gift_balance - LEAST(
                    GREATEST(0, gift_balance - gift_frozen),
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))
                        - LEAST(
                            GREATEST(0, #{amount}
                                - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                            GREATEST(0, permanent_balance - permanent_frozen)
                        ))
                ),
                permanent_balance = permanent_balance - LEAST(
                    GREATEST(0, #{amount}
                        - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen))),
                    GREATEST(0, permanent_balance - permanent_frozen)
                ),
                membership_balance = membership_balance
                    - LEAST(#{amount}, GREATEST(0, membership_balance - membership_frozen)),
                balance = balance - #{amount},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND balance - frozen >= #{amount} AND status = 'ACTIVE'
            """)
    int manualDeductRows(@Param("accountId") Long accountId, @Param("amount") int amount);

    default boolean manualDeduct(Long accountId, int amount) {
        return manualDeductRows(accountId, amount) == 1;
    }
}
