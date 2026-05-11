package com.aiminilab.aitoolmarket.auth.service.impl;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public AuthenticatedSession register(RegisterRequest request) {
        userMapper.findByUsername(request.username()).ifPresent(user -> {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
        });

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname() == null || request.nickname().isBlank() ? request.username() : request.nickname());
        user.setUserType(UserType.USER.name());
        user.setStatus(UserStatus.ACTIVE.name());
        Long userId = userMapper.insert(user);
        user.setId(userId);
        return buildLoginResponse(user);
    }

    @Override
    public AuthenticatedSession login(LoginRequest request, boolean adminLogin) {
        User user = userMapper.findByUsername(request.account())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        if (adminLogin && !UserType.ADMIN.name().equals(user.getUserType())) {
            throw new BusinessException(ErrorCode.ADMIN_FORBIDDEN, "管理员无权限");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        return buildLoginResponse(user);
    }

    @Override
    public UserProfileResponse currentUser(Long userId) {
        User user = userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        return UserProfileResponse.from(user);
    }

    private AuthenticatedSession buildLoginResponse(User user) {
        String token = jwtTokenProvider.createToken(new AuthUser(user.getId(), user.getUsername(), user.getUserType()));
        return new AuthenticatedSession(token, new LoginResponse(token, UserProfileResponse.from(user)));
    }
}
