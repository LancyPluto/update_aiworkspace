package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.UserMembership;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface UserMembershipMapper extends BaseMapper<UserMembership> {
    @Insert("INSERT INTO user_memberships(user_id, status, created_at, updated_at) " +
            "VALUES(#{userId}, 'NONE', #{now}, #{now}) ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensureSlot(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM user_memberships WHERE user_id = #{userId} FOR UPDATE")
    UserMembership lockByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE user_memberships
            SET status = #{status}, package_id = #{packageId}, package_code = #{packageCode},
                order_id = #{orderId}, started_at = #{startedAt}, expires_at = #{expiresAt}, updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateState(UserMembership membership);

    @Select("SELECT * FROM user_memberships WHERE user_id = #{userId} LIMIT 1")
    UserMembership findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT user_id
            FROM user_memberships
            WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= #{now}
            ORDER BY expires_at, user_id
            LIMIT #{limit}
            """)
    List<Long> findExpiredActiveUserIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
