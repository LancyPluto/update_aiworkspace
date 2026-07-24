package com.aiminilab.aitoolmarket.user.service;

import com.aiminilab.aitoolmarket.common.error.ApiErrors;
import com.aiminilab.aitoolmarket.common.exception.BizException;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class PublicUserIdentityService {

    private static final int CODE_MIN = 10_000;
    private static final int CODE_SPACE = 90_000;
    private static final int MAX_ATTEMPTS = 100;

    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    public PublicUserIdentityService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void assignPublicCode(User user) {
        if (user.getPublicCode() != null && user.getPublicCode().matches("[1-9]\\d{4}")) {
            return;
        }
        user.setPublicCode(nextAvailableCode());
    }

    public void backfillMissingCodes() {
        for (User user : userMapper.findUsersMissingPublicCode()) {
            boolean assigned = false;
            for (int attempt = 0; attempt < MAX_ATTEMPTS && !assigned; attempt++) {
                String candidate = randomCode();
                if (userMapper.findAnyByPublicCode(candidate).isPresent()) {
                    continue;
                }
                try {
                    assigned = userMapper.updatePublicCodeIfMissing(user.getId(), candidate) == 1;
                } catch (DuplicateKeyException ignored) {
                    // Another insert or startup assigned the same candidate first.
                }
            }
            if (!assigned && userMapper.findAnyById(user.getId()).map(User::getPublicCode).orElse(null) == null) {
                throw new IllegalStateException("Unable to allocate a unique public user code");
            }
        }
    }

    public User requirePublicUser(String publicCode) {
        String normalized = normalizePublicCode(publicCode);
        return userMapper.findByPublicCode(normalized)
                .orElseThrow(() -> userNotFound("No active user matched the public code"));
    }

    public String resolveDisplayName(User user) {
        if (user == null) {
            return "用户";
        }
        String nickname = safePublicName(user.getNickname(), user.getId(), user.getPublicCode());
        if (nickname != null) {
            return nickname;
        }
        String username = safePublicName(user.getUsername(), user.getId(), user.getPublicCode());
        if (username != null) {
            return username;
        }
        return fallbackDisplayName(user.getPublicCode());
    }

    public String fallbackDisplayName(String publicCode) {
        return "用户" + normalizePublicCode(publicCode);
    }

    private String nextAvailableCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (userMapper.findAnyByPublicCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique public user code");
    }

    private String randomCode() {
        return Integer.toString(CODE_MIN + random.nextInt(CODE_SPACE));
    }

    private String normalizePublicCode(String publicCode) {
        String normalized = publicCode == null ? "" : publicCode.trim();
        if (!normalized.matches("[1-9]\\d{4}")) {
            throw userNotFound("Public user code must contain exactly five digits");
        }
        return normalized;
    }

    private BizException userNotFound(String developerMessage) {
        return new BizException(ApiErrors.RESOURCE_NOT_FOUND, "用户不存在", developerMessage);
    }

    private String safePublicName(String value, Long internalUserId, String publicCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (internalUserId != null && trimmed.equals("用户" + internalUserId)) {
            return null;
        }
        if (trimmed.matches("^用户\\d+$")
                && publicCode != null
                && publicCode.matches("[1-9]\\d{4}")
                && !trimmed.equals("用户" + publicCode)) {
            return null;
        }
        String normalized = trimmed.replaceAll("[\\s-]", "");
        if (normalized.startsWith("+86")) {
            normalized = normalized.substring(3);
        } else if (normalized.startsWith("86") && normalized.length() == 13) {
            normalized = normalized.substring(2);
        }
        return normalized.matches("1\\d{10}") ? null : trimmed;
    }
}
