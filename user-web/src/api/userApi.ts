import { apiRequest } from "./client"
import type {
  CancelAccountRequest,
  CommunitySettingsRequest,
  SmsCodeResponse,
  UpdateUserProfileRequest,
  UserAvatarUploadResponse,
  UserProfile,
} from "./types"

const P = {
  me: "/api/v1/users/me",
  avatar: "/api/v1/users/me/avatar",
  communitySettings: "/api/v1/users/me/community-settings",
  cancelSmsCode: "/api/v1/users/me/cancel/sms-code",
  cancel: "/api/v1/users/me/cancel",
} as const

/** GET /api/v1/users/me —— 获取当前登录用户信息 */
export async function getCurrentUser(options?: { token?: string | null }): Promise<UserProfile> {
  return apiRequest<UserProfile>("GET", P.me, { token: options?.token })
}

export async function updateCurrentUserProfile(
  body: UpdateUserProfileRequest,
  options?: { token?: string | null },
): Promise<UserProfile> {
  return apiRequest<UserProfile>("PATCH", P.me, {
    body,
    token: options?.token,
  })
}

export async function uploadCurrentUserAvatar(
  file: File,
  options?: { token?: string | null },
): Promise<UserAvatarUploadResponse> {
  const body = new FormData()
  body.append("file", file)
  return apiRequest<UserAvatarUploadResponse>("POST", P.avatar, {
    body,
    token: options?.token,
  })
}

export async function updateCommunitySettings(
  body: CommunitySettingsRequest,
  options?: { token?: string | null },
): Promise<UserProfile> {
  return apiRequest<UserProfile>("PATCH", P.communitySettings, {
    body,
    token: options?.token,
  })
}

export async function sendCancelAccountSmsCode(options?: { token?: string | null }): Promise<SmsCodeResponse> {
  return apiRequest<SmsCodeResponse>("POST", P.cancelSmsCode, {
    token: options?.token,
  })
}

export async function cancelCurrentUserAccount(
  body: CancelAccountRequest,
  options?: { token?: string | null },
): Promise<void> {
  await apiRequest<unknown>("POST", P.cancel, {
    body,
    token: options?.token,
  })
}
