package com.aiminilab.aitoolmarket.user.mapper;

import com.aiminilab.aitoolmarket.user.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserMapper {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<User> rowMapper = (rs, rowNum) -> {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setPhone(rs.getString("phone"));
        user.setEmail(rs.getString("email"));
        user.setNickname(rs.getString("nickname"));
        user.setUserType(rs.getString("user_type"));
        user.setStatus(rs.getString("status"));
        user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        user.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        user.setDeleted(rs.getInt("is_deleted") == 1);
        return user;
    };

    public UserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(Long id) {
        List<User> users = jdbcTemplate.query("""
                SELECT * FROM users
                WHERE id = ? AND is_deleted = 0
                """, rowMapper, id);
        return users.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> users = jdbcTemplate.query("""
                SELECT * FROM users
                WHERE username = ? AND is_deleted = 0
                """, rowMapper, username);
        return users.stream().findFirst();
    }

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

    private Long generatedId(KeyHolder keyHolder) {
        Number key = null;
        if (!keyHolder.getKeyList().isEmpty()) {
            Object value = keyHolder.getKeyList().get(0).values().stream().findFirst().orElse(null);
            if (value instanceof Number number) {
                key = number;
            }
        }
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Generated id is missing");
        }
        return key.longValue();
    }
}
