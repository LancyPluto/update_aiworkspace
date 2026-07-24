package com.aiminilab.aitoolmarket.user.service.impl;

import com.aiminilab.aitoolmarket.auth.service.SmsCodeService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.credit.service.impl.MembershipService;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.user.dto.CancelAccountRequest;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserProfileRequest;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.AccountDataCleanupMapper;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聚焦头像地址校验逻辑：本地存储返回 {@code /generated/avatars/...}，
 * OSS 存储返回 {@code {publicBaseUrl}/avatars/...} 绝对地址，两者都应被接受。
 */
class UserProfileServiceImplTest {

    private static final Long USER_ID = 7L;

    private UserMapper userMapper;
    private SmsCodeService smsCodeService;
    private CommunityPostMapper communityPostMapper;

    private UserProfileServiceImpl buildService(String publicBaseUrl, String currentAvatarUrl) {
        userMapper = mock(UserMapper.class);
        AssetStorageService assetStorageService = mock(AssetStorageService.class);
        smsCodeService = mock(SmsCodeService.class);
        AccountDataCleanupMapper accountDataCleanupMapper = mock(AccountDataCleanupMapper.class);
        communityPostMapper = mock(CommunityPostMapper.class);
        MembershipService membershipService = mock(MembershipService.class);

        when(assetStorageService.getPublicBaseUrl()).thenReturn(publicBaseUrl);

        User existing = mock(User.class);
        when(existing.getId()).thenReturn(USER_ID);
        when(existing.getUsername()).thenReturn("alice");
        when(existing.getNickname()).thenReturn("Alice");
        when(existing.getPhone()).thenReturn("13800138000");
        when(existing.getAvatarUrl()).thenReturn(currentAvatarUrl);
        when(existing.getDeleted()).thenReturn(false);
        when(userMapper.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(userMapper.updateProfile(eq(USER_ID), anyString(), any())).thenReturn(1);
        when(userMapper.cancelAccount(eq(USER_ID), anyString(), anyString())).thenReturn(1);
        when(membershipService.current(USER_ID)).thenReturn(null);

        return new UserProfileServiceImpl(userMapper, assetStorageService, smsCodeService,
                accountDataCleanupMapper, communityPostMapper, membershipService);
    }

    @Test
    void acceptsLocalGeneratedAvatarUrl() {
        String avatar = "/generated/avatars/7/20260706120000-abc.png";
        UserProfileServiceImpl service = buildService("/generated", null);

        service.update(USER_ID, new UpdateUserProfileRequest("Alice", avatar));

        verify(userMapper).updateProfile(USER_ID, "Alice", avatar);
    }

    @Test
    void acceptsOssAvatarUrl() {
        // 生产 OSS/CDN 模式下头像上传返回绝对地址，此前被硬编码前缀校验拒绝。
        String publicBase = "https://cdn.wlcloudai.com";
        String avatar = publicBase + "/avatars/7/20260706120000-abc.png";
        UserProfileServiceImpl service = buildService(publicBase, null);

        service.update(USER_ID, new UpdateUserProfileRequest("Alice", avatar));

        verify(userMapper).updateProfile(USER_ID, "Alice", avatar);
    }

    @Test
    void rejectsUnmanagedAvatarUrl() {
        UserProfileServiceImpl service = buildService("https://cdn.wlcloudai.com", null);

        assertThrows(BusinessException.class, () ->
                service.update(USER_ID, new UpdateUserProfileRequest("Alice", "https://evil.com/avatars/7/x.png")));
    }

    @Test
    void keepsCurrentAvatarWhenNullProvided() {
        String currentAvatar = "https://cdn.wlcloudai.com/avatars/7/old.png";
        UserProfileServiceImpl service = buildService("https://cdn.wlcloudai.com", currentAvatar);

        service.update(USER_ID, new UpdateUserProfileRequest("Alice", null));

        verify(userMapper).updateProfile(USER_ID, "Alice", currentAvatar);
    }

    @Test
    void keepsCommunityPostsPublishedWhenCancellingAccount() {
        UserProfileServiceImpl service = buildService("https://cdn.wlcloudai.com", null);

        service.cancelAccount(USER_ID, new CancelAccountRequest("123456"));

        InOrder inOrder = inOrder(smsCodeService, userMapper);
        inOrder.verify(smsCodeService).verifyCode("13800138000", "CANCEL_ACCOUNT", "123456");
        inOrder.verify(userMapper).cancelAccount(eq(USER_ID), anyString(), anyString());
        verify(communityPostMapper, never()).unpublishByUserId(USER_ID);
    }
}
