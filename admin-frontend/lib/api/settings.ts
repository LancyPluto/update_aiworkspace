import { http } from './http'

export type SettingsMap = Record<string, string>

export type SettingVersion = {
  id: number
  settingKey: string
  settingValue: string
  operatorId?: number | null
  createdAt?: string | null
}

export function fetchSettings() {
  return http.get<SettingsMap>('/api/admin/v1/settings')
}

export function updateSettings(settings: SettingsMap) {
  return http.put<SettingsMap>('/api/admin/v1/settings', { settings })
}

export function fetchSettingVersions(key: string) {
  return http.get<SettingVersion[]>(`/api/admin/v1/settings/${encodeURIComponent(key)}/versions`)
}

export function restoreDefaultSetting(key: string) {
  return http.post<SettingsMap>(`/api/admin/v1/settings/${encodeURIComponent(key)}/restore-default`, {})
}

export function restoreAgentDefaults() {
  return restoreDefaultSetting('agent')
}

export interface CustomerServiceQrUploadResult {
  url: string
  filename: string
  contentType: string
  fileSize: number
}

export function uploadCustomerServiceQr(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http.postForm<CustomerServiceQrUploadResult>('/api/admin/v1/settings/customer-service/qr-upload', formData)
}
