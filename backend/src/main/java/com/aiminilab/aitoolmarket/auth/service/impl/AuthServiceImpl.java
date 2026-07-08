package com.aiminilab.aitoolmarket.auth.service.impl;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.auth.dto.ResetPasswordRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsAuthRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.auth.service.HumanCaptchaService;
import com.aiminilab.aitoolmarket.auth.service.SmsCodeService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.service.ReferralService;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL =
            "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/assets/default-user-avatar.svg";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_NAME_DIGITS = 9;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SmsCodeService smsCodeService;
    private final HumanCaptchaService humanCaptchaService;
    private final CreditRechargeOrderMapper creditRechargeOrderMapper;
    private final ReferralService referralService;

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           SmsCodeService smsCodeService,
                           HumanCaptchaService humanCaptchaService,
                           CreditRechargeOrderMapper creditRechargeOrderMapper,
                           ReferralService referralService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.smsCodeService = smsCodeService;
        this.humanCaptchaService = humanCaptchaService;
        this.creditRechargeOrderMapper = creditRechargeOrderMapper;
        this.referralService = referralService;
    }

    @Override
    public AuthenticatedSession register(RegisterRequest request) {
        String username = normalizeBlank(request.username());
        String phone = normalizeBlank(request.phone());
        if (username == null && phone == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请输入用户名或手机号");
        }
        if (phone != null) {
            phone = normalizePhone(phone);
        }

        if (username != null) {
            userMapper.findByUsername(username).ifPresent(user -> {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
            });
        }
        if (phone != null) {
            userMapper.findByUsername(phone).ifPresent(user -> {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
            });
            userMapper.findByPhone(phone).ifPresent(user -> {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "手机号已注册");
            });
        }

        String defaultName = createDefaultDisplayName();
        User user = new User();
        user.setUsername(username == null ? defaultName : username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhone(phone);
        user.setNickname(defaultName);
        user.setAvatarUrl(DEFAULT_AVATAR_URL);
        user.setUserType(UserType.USER.name());
        user.setStatus(UserStatus.ACTIVE.name());
        Long userId = insertUser(user);
        user.setId(userId);
        referralService.bindInviteCode(userId, request.inviteCode());
        return buildLoginResponse(user);
    }

    @Override
    public AuthenticatedSession login(LoginRequest request, boolean adminLogin) {
        User user = userMapper.findByUsernameOrPhone(request.account())
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
    public SmsCodeResponse sendSmsCode(String phone, String scene, String captchaVerifyParam) {
        String normalizedPhone = normalizePhone(phone);
        String normalizedScene = normalizeBlank(scene);
        humanCaptchaService.verify(captchaVerifyParam);
        if ("LOGIN_OR_REGISTER".equalsIgnoreCase(normalizedScene)) {
            return smsCodeService.sendCode(normalizedPhone, "LOGIN_OR_REGISTER");
        }
        if ("REGISTER".equalsIgnoreCase(normalizedScene)) {
            userMapper.findByPhone(normalizedPhone).ifPresent(user -> {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "手机号已注册");
            });
        } else if ("LOGIN".equalsIgnoreCase(normalizedScene)) {
            userMapper.findByPhone(normalizedPhone)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "手机号未注册"));
        } else if ("RESET_PASSWORD".equalsIgnoreCase(normalizedScene)) {
            userMapper.findByPhone(normalizedPhone)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "手机号未注册"));
        }
        return smsCodeService.sendCode(normalizedPhone, normalizedScene);
    }

    @Override
    public AuthenticatedSession registerWithSmsCode(SmsAuthRequest request) {
        String phone = normalizePhone(request.phone());
        smsCodeService.verifyCode(phone, "REGISTER", normalizeBlank(request.code()));
        userMapper.findByPhone(phone).ifPresent(user -> {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "手机号已注册");
        });
        userMapper.findByUsername(phone).ifPresent(user -> {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
        });

        String defaultName = createDefaultDisplayName();
        User user = new User();
        user.setUsername(defaultName);
        String password = normalizeBlank(request.password());
        user.setPasswordHash(passwordEncoder.encode(password == null ? "SMS_LOGIN_ONLY:" + phone + ":" + System.nanoTime() : password));
        user.setPhone(phone);
        user.setNickname(defaultName);
        user.setAvatarUrl(DEFAULT_AVATAR_URL);
        user.setUserType(UserType.USER.name());
        user.setStatus(UserStatus.ACTIVE.name());
        Long userId = insertUser(user);
        user.setId(userId);
        referralService.bindInviteCode(userId, request.inviteCode());
        return buildLoginResponse(user);
    }

    @Override
    public AuthenticatedSession loginWithSmsCode(SmsAuthRequest request) {
        String phone = normalizePhone(request.phone());
        String code = normalizeBlank(request.code());
        User user = userMapper.findByPhone(phone).orElse(null);
        if (user == null) {
            smsCodeService.verifyCode(phone, "LOGIN_OR_REGISTER", code);
            userMapper.findByUsername(phone).ifPresent(existing -> {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
            });
            String defaultName = createDefaultDisplayName();
            User created = new User();
            created.setUsername(defaultName);
            created.setPasswordHash(passwordEncoder.encode("SMS_LOGIN_ONLY:" + phone + ":" + System.nanoTime()));
            created.setPhone(phone);
            created.setNickname(defaultName);
            created.setAvatarUrl(DEFAULT_AVATAR_URL);
            created.setUserType(UserType.USER.name());
            created.setStatus(UserStatus.ACTIVE.name());
            Long userId = insertUser(created);
            created.setId(userId);
            referralService.bindInviteCode(userId, request.inviteCode());
            return buildLoginResponse(created);
        }
        try {
            smsCodeService.verifyCode(phone, "LOGIN", code);
        } catch (BusinessException exception) {
            smsCodeService.verifyCode(phone, "LOGIN_OR_REGISTER", code);
        }
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        return buildLoginResponse(user);
    }

    @Override
    public void resetPasswordWithSmsCode(ResetPasswordRequest request) {
        String phone = normalizePhone(request.phone());
        String password = normalizeBlank(request.password());
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码长度需为 6-64 位");
        }
        smsCodeService.verifyCode(phone, "RESET_PASSWORD", normalizeBlank(request.code()));
        User user = userMapper.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "手机号未注册"));
        userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(password));
    }

    @Override
    public UserProfileResponse currentUser(Long userId) {
        User user = userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        String packageCode = creditRechargeOrderMapper.findCurrentPackageCodeByUserId(userId);
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getAutoPublishAssets(),
                user.getPromptPublicByDefault(),
                user.getUserType(),
                user.getStatus(),
                user.getPhone(),
                user.getEmail(),
                packageCode
        );
    }

    private AuthenticatedSession buildLoginResponse(User user) {
        String token = jwtTokenProvider.createToken(new AuthUser(user.getId(), user.getUsername(), user.getUserType()));
        String packageCode = creditRechargeOrderMapper.findCurrentPackageCodeByUserId(user.getId());
        UserProfileResponse profile = new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getAutoPublishAssets(),
                user.getPromptPublicByDefault(),
                user.getUserType(),
                user.getStatus(),
                user.getPhone(),
                user.getEmail(),
                packageCode
        );
        return new AuthenticatedSession(token, new LoginResponse(token, profile));
    }

    private String createDefaultDisplayName() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String displayName = "用户" + randomDigits(DEFAULT_NAME_DIGITS);
            if (userMapper.findByUsername(displayName).isEmpty()) {
                return displayName;
            }
        }
        return "用户" + System.currentTimeMillis();
    }

    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    private Long insertUser(User user) {
        try {
            return userMapper.insertAndReturnId(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Account already exists");
        }
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizePhone(String value) {
        String phone = normalizeBlank(value);
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "手机号格式不正确");
        }
        return phone;
    }
}
