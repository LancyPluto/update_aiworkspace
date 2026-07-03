package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.service.SmsCodeService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import com.aiminilab.aitoolmarket.user.dto.CancelAccountRequest;
import com.aiminilab.aitoolmarket.user.dto.CommunitySettingsRequest;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserProfileRequest;
import com.aiminilab.aitoolmarket.user.dto.UserAvatarUploadResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.user.mapper.AccountDataCleanupMapper;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.user.service.UserProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final long MAX_AVATAR_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_NICKNAME_LENGTH = 40;
    private static final int MAX_BIO_LENGTH = 280;
    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final DateTimeFormatter AVATAR_FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String CANCEL_ACCOUNT_SMS_SCENE = "CANCEL_ACCOUNT";

    private final UserMapper userMapper;
    private final AssetStorageService assetStorageService;
    private final SmsCodeService smsCodeService;
    private final AccountDataCleanupMapper accountDataCleanupMapper;
    private final CommunityPostMapper communityPostMapper;
    private final CreditRechargeOrderMapper creditRechargeOrderMapper;

    public UserProfileServiceImpl(UserMapper userMapper,
                                  AssetStorageService assetStorageService,
                                  SmsCodeService smsCodeService,
                                  AccountDataCleanupMapper accountDataCleanupMapper,
                                  CommunityPostMapper communityPostMapper,
                                  CreditRechargeOrderMapper creditRechargeOrderMapper) {
        this.userMapper = userMapper;
        this.assetStorageService = assetStorageService;
        this.smsCodeService = smsCodeService;
        this.accountDataCleanupMapper = accountDataCleanupMapper;
        this.communityPostMapper = communityPostMapper;
        this.creditRechargeOrderMapper = creditRechargeOrderMapper;
    }

    private String resolveMembershipPlan(Long userId) {
        return creditRechargeOrderMapper.findCurrentPackageCodeByUserId(userId);
    }

    @Override
    public UserProfileResponse update(Long userId, UpdateUserProfileRequest request) {
        User existing = requireUser(userId);
        String nickname = normalizeNickname(request == null ? null : request.nickname(), existing.getNickname(), existing.getUsername());
        String avatarUrl = normalizeAvatarUrl(request == null ? null : request.avatarUrl(), existing.getAvatarUrl());
        userMapper.updateProfile(userId, nickname, avatarUrl);
        return UserProfileResponse.from(requireUser(userId), resolveMembershipPlan(userId));
    }

    @Override
    public UserAvatarUploadResponse uploadAvatar(Long userId, MultipartFile file) {
        requireUser(userId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的头像");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "头像不能超过 5MB");
        }

        String contentType = normalizeContentType(file.getContentType());
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (extension.isBlank()) {
            extension = extensionFromContentType(contentType);
        }
        if (!AVATAR_CONTENT_TYPES.contains(contentType) || !AVATAR_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "头像仅支持 JPG、PNG 或 WebP 图片");
        }

        String timestamp = LocalDateTime.now().format(AVATAR_FILENAME_TIME);
        String filename = timestamp + "-" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        StoredAsset stored = assetStorageService.storeMultipartPublic("avatars/" + userId + "/" + filename, file);
        String avatarUrl = stored.publicUrl();
        userMapper.updateAvatarUrl(userId, avatarUrl);
        UserProfileResponse user = UserProfileResponse.from(requireUser(userId), resolveMembershipPlan(userId));
        return new UserAvatarUploadResponse(avatarUrl, user);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCommunitySettings(Long userId, CommunitySettingsRequest request) {
        User existing = requireUser(userId);
        String bio = normalizeBio(request == null ? null : request.bio(), existing.getBio());
        boolean autoPublishAssets = request == null || request.autoPublishAssets() == null
                ? Boolean.TRUE.equals(existing.getAutoPublishAssets())
                : Boolean.TRUE.equals(request.autoPublishAssets());
        boolean promptPublicByDefault = request == null || request.promptPublicByDefault() == null
                ? Boolean.TRUE.equals(existing.getPromptPublicByDefault())
                : Boolean.TRUE.equals(request.promptPublicByDefault());
        boolean wasPublicByDefault = Boolean.TRUE.equals(existing.getPromptPublicByDefault());
        userMapper.updateCommunitySettings(userId, bio, autoPublishAssets, promptPublicByDefault);
        // When the user enables "prompt public by default", retroactively make all existing
        // published community posts' prompts visible so they show up in the community feed.
        if (promptPublicByDefault && !wasPublicByDefault) {
            communityPostMapper.updatePromptVisibleByUserId(userId, true);
        }
        return UserProfileResponse.from(requireUser(userId), resolveMembershipPlan(userId));
    }

    @Override
    public SmsCodeResponse sendCancelAccountSmsCode(Long userId) {
        User existing = requireUser(userId);
        String phone = normalizePhone(existing.getPhone());
        return smsCodeService.sendCode(phone, CANCEL_ACCOUNT_SMS_SCENE);
    }

    @Override
    @Transactional
    public void cancelAccount(Long userId, CancelAccountRequest request) {
        User existing = requireUser(userId);
        String phone = normalizePhone(existing.getPhone());
        String smsCode = normalizeBlank(request == null ? null : request.smsCode());
        if (smsCode == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请输入短信验证码");
        }
        smsCodeService.verifyCode(phone, CANCEL_ACCOUNT_SMS_SCENE, smsCode);

        accountDataCleanupMapper.deleteAgentFileChunks(userId);
        accountDataCleanupMapper.deleteAgentFiles(userId);
        accountDataCleanupMapper.deleteAgentPendingToolContext(userId);
        accountDataCleanupMapper.deleteAgentToolCalls(userId);
        accountDataCleanupMapper.deleteAgentRunEvents(userId);
        accountDataCleanupMapper.deleteAgentContextSnapshots(userId);
        accountDataCleanupMapper.deleteAgentMessages(userId);
        accountDataCleanupMapper.deleteAgentRuns(userId);
        accountDataCleanupMapper.deleteAgentWorkspaceMemoryItems(userId);
        accountDataCleanupMapper.deleteAgentWorkspaceMembers(userId);
        accountDataCleanupMapper.deleteAgentWorkspaces(userId);
        accountDataCleanupMapper.deleteAgentToolPreferences(userId);
        accountDataCleanupMapper.deleteMarketMessageAttachments(userId);
        accountDataCleanupMapper.deleteMarketMessages(userId);
        accountDataCleanupMapper.deleteMarketSessions(userId);
        accountDataCleanupMapper.deleteMarketFiles(userId);
        accountDataCleanupMapper.deleteUserUploadAssets(userId);

        String suffix = userId + "_" + System.currentTimeMillis();
        int updated = userMapper.cancelAccount(userId, "cancelled_" + suffix, "CANCELLED:" + UUID.randomUUID());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "账号注销失败，请稍后再试");
        }
    }

    private User requireUser(Long userId) {
        return userMapper.findById(userId)
                .filter(user -> user.getDeleted() == null || !user.getDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private String normalizePhone(String value) {
        String phone = normalizeBlank(value);
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前账号未绑定有效手机号，无法注销");
        }
        return phone;
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeNickname(String value, String current, String fallback) {
        String normalized = value == null ? current : value.trim();
        if (normalized == null || normalized.isBlank()) {
            normalized = fallback;
        }
        if (normalized != null && normalized.length() > MAX_NICKNAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "昵称不能超过 40 个字符");
        }
        return normalized;
    }

    private String normalizeAvatarUrl(String value, String current) {
        if (value == null) {
            return current;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (!normalized.startsWith("/generated/avatars/")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "头像地址无效");
        }
        return normalized;
    }

    private String normalizeBio(String value, String current) {
        String normalized = value == null ? current : value.trim();
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > MAX_BIO_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "简介不能超过 280 个字");
        }
        return normalized;
    }

    private String normalizeContentType(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String safeOriginalName(String value) {
        String normalized = Normalizer.normalize(value == null ? "avatar" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .trim();
        return normalized.isBlank() ? "avatar" : Path.of(normalized).getFileName().toString();
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot >= filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).trim().toLowerCase();
    }

    private String extensionFromContentType(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "";
        };
    }
}
