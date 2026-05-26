import { http } from "./http"

/** 与后端 {@code EngineApiSettingKeys} 一致 */
export const ENGINE_API_SETTING_KEYS = {
  mineruApiBase: "engine.mineru.apiBase",
  mineruToken: "engine.mineru.token",
  baiduApiKey: "engine.baidu.apiKey",
} as const

export type EngineApiSettingsMap = Record<string, string>

export function fetchEngineApiSettings() {
  return http.get<EngineApiSettingsMap>("/api/admin/v1/settings/engine-api")
}

export function updateEngineApiSettings(settings: EngineApiSettingsMap) {
  return http.put<EngineApiSettingsMap>("/api/admin/v1/settings/engine-api", { settings })
}

/** catalog 字段 key → system_settings 键 */
export const CATALOG_FIELD_TO_SETTING_KEY: Record<string, string> = {
  mineru_api_base: ENGINE_API_SETTING_KEYS.mineruApiBase,
  mineru_token: ENGINE_API_SETTING_KEYS.mineruToken,
  baidu_api_key: ENGINE_API_SETTING_KEYS.baiduApiKey,
}
