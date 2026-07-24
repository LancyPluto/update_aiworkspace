package com.aiminilab.aitoolmarket.user.service;

import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PublicUserIdentityServiceTest {

    private final PublicUserIdentityService service = new PublicUserIdentityService(mock(UserMapper.class));

    @Test
    void legacyGeneratedNameFallsBackToCurrentPublicCode() {
        User user = user(29L, "49443", "用户018735835", "用户018735835");

        assertEquals("用户49443", service.resolveDisplayName(user));
    }

    @Test
    void canonicalFallbackAndCustomNicknameRemainVisible() {
        assertEquals("用户49443", service.resolveDisplayName(user(29L, "49443", "用户49443", "legacy-login")));
        assertEquals("咕咕嘎嘎", service.resolveDisplayName(user(29L, "49443", "咕咕嘎嘎", "legacy-login")));
    }

    private User user(Long id, String publicCode, String nickname, String username) {
        User user = new User();
        user.setId(id);
        user.setPublicCode(publicCode);
        user.setNickname(nickname);
        user.setUsername(username);
        user.setDeleted(false);
        return user;
    }
}
