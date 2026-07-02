const OSS_PUBLIC_ASSET_BASE_URL = "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com"

/**
 * Prefer CDN to reduce OSS egress. Must include scheme, no trailing slash.
 * Example: https://cdn.example.com
 */
export const PUBLIC_ASSET_BASE_URL =
  (import.meta.env.VITE_PUBLIC_ASSET_BASE_URL as string | undefined)?.replace(/\/$/, "") ||
  OSS_PUBLIC_ASSET_BASE_URL

export const CREDIT_POWER_ICON_URL = `${PUBLIC_ASSET_BASE_URL}/icons/credit-power-icon.png`
