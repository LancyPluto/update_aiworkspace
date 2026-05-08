package com.aiminilab.aitoolmarket.common.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("service", "backend");
        status.put("mysql", checkMysql());
        status.put("redis", checkRedis());
        return ApiResponse.success(status);
    }

    @GetMapping("/api/v1/ping")
    public ApiResponse<Map<String, String>> userPing() {
        return ApiResponse.success(Map.of("scope", "user", "status", "ok"));
    }

    @GetMapping("/api/admin/v1/ping")
    public ApiResponse<Map<String, String>> adminPing() {
        return ApiResponse.success(Map.of("scope", "admin", "status", "ok"));
    }

    private String checkMysql() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result) ? "ok" : "error";
        } catch (Exception exception) {
            return "error";
        }
    }

    private String checkRedis() {
        try {
            String result = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(result) ? "ok" : "error";
        } catch (Exception exception) {
            return "error";
        }
    }
}
