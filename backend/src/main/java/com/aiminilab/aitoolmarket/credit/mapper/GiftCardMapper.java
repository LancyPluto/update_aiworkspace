package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.GiftCard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface GiftCardMapper extends BaseMapper<GiftCard> {

    default List<GiftCard> findByOwnerAndStatus(Long userId, String status) {
        LambdaQueryWrapper<GiftCard> wrapper = new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOwnerUserId, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(GiftCard::getStatus, status);
        }
        wrapper.orderByDesc(GiftCard::getCreatedAt);
        return selectList(wrapper);
    }

    default GiftCard findByCardCode(String cardCode) {
        return selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getCardCode, cardCode)
                .last("LIMIT 1"));
    }

    default GiftCard findByIdAndOwner(Long id, Long ownerUserId) {
        return selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getId, id)
                .eq(GiftCard::getOwnerUserId, ownerUserId)
                .last("LIMIT 1"));
    }

    @Update("""
            UPDATE gift_cards
            SET owner_user_id = #{newOwnerId},
                gifted_from_user_id = #{fromUserId},
                gifted_at = #{eventAt},
                updated_at = #{eventAt}
            WHERE id = #{cardId}
              AND owner_user_id = #{fromUserId}
              AND status = 'UNUSED'
            """)
    int transferOwnership(@Param("cardId") Long cardId,
                          @Param("newOwnerId") Long newOwnerId,
                          @Param("fromUserId") Long fromUserId,
                          @Param("eventAt") LocalDateTime eventAt);

    @Update("""
            UPDATE gift_cards
            SET status = 'USED',
                redeemed_at = #{eventAt},
                updated_at = #{eventAt}
            WHERE id = #{cardId}
              AND owner_user_id = #{userId}
              AND status = 'UNUSED'
            """)
    int markRedeemed(@Param("cardId") Long cardId,
                     @Param("userId") Long userId,
                     @Param("eventAt") LocalDateTime eventAt);
}
