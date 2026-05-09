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

    default Long insertAndReturnId(User user) {
        insert(user);
        return user.getId();
    }

<<<<<<< HEAD
    public List<User> findAllActive() {
        return jdbcTemplate.query("""
                SELECT * FROM users
                WHERE is_deleted = 0
                ORDER BY id DESC
                """, rowMapper);
    }

    public Long insert(User user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO users (username, password_hash, phone, email, nickname, user_type, status, is_deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getPhone());
            ps.setString(4, user.getEmail());
            ps.setString(5, user.getNickname());
            ps.setString(6, user.getUserType());
            ps.setString(7, user.getStatus());
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }
=======
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
>>>>>>> origin/feature/backend-core

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
