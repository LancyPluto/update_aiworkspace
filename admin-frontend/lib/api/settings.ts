import { http } from './http'

export type SettingsMap = Record<string, string>

export function fetchSettings() {
  return http.get<SettingsMap>('/api/admin/v1/settings')
}

export function updateSettings(settings: SettingsMap) {
  return http.put<SettingsMap>('/api/admin/v1/settings', { settings })
}
