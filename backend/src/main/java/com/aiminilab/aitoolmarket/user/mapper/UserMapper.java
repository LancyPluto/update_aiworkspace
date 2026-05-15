package com.aiminilab.aitoolmarket.user.mapper;

import com.aiminilab.aitoolmarket.user.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface UserMapper extends BaseMapper<User> {

    default Optional<User> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    @Select("""
            SELECT *
            FROM users
            WHERE username = #{username} AND is_deleted = 0
            LIMIT 1
            """)
    User selectByUsername(@Param("username") String username);

    default Optional<User> findByUsername(String username) {
        return Optional.ofNullable(selectByUsername(username));
    }

    @Select("""
            SELECT *
            FROM users
            WHERE phone = #{phone} AND is_deleted = 0
            LIMIT 1
            """)
    User selectByPhone(@Param("phone") String phone);

    default Optional<User> findByPhone(String phone) {
        return Optional.ofNullable(selectByPhone(phone));
    }

    @Select("""
            SELECT *
            FROM users
            WHERE (username = #{account} OR phone = #{account}) AND is_deleted = 0
            LIMIT 1
            """)
    User selectByUsernameOrPhone(@Param("account") String account);

    default Optional<User> findByUsernameOrPhone(String account) {
        return Optional.ofNullable(selectByUsernameOrPhone(account));
    }

    default Long insertAndReturnId(User user) {
        insert(user);
        return user.getId();
    }

    @Select("""
            <script>
            SELECT *
            FROM users
            WHERE is_deleted = 0
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                LOWER(username) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(nickname) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR phone LIKE CONCAT('%', #{keyword}, '%')
                OR LOWER(email) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            <if test="status != null and status.trim() != ''">
              AND status = #{status}
            </if>
            <if test="userType != null and userType.trim() != ''">
              AND user_type = #{userType}
            </if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<User> findForAdmin(@Param("keyword") String keyword,
                            @Param("status") String status,
                            @Param("userType") String userType,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM users
            WHERE is_deleted = 0
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                LOWER(username) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(nickname) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR phone LIKE CONCAT('%', #{keyword}, '%')
                OR LOWER(email) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            <if test="status != null and status.trim() != ''">
              AND status = #{status}
            </if>
            <if test="userType != null and userType.trim() != ''">
              AND user_type = #{userType}
            </if>
            </script>
            """)
    long countForAdmin(@Param("keyword") String keyword,
                       @Param("status") String status,
                       @Param("userType") String userType);

    @Update("""
            UPDATE users
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{userId} AND is_deleted = 0
            """)
    void updateStatus(@Param("userId") Long userId, @Param("status") String status);
}
