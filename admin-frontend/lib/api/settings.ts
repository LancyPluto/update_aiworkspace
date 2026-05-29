import { http } from './http'

export type SettingsMap = Record<string, string>

export function fetchSettings() {
  return http.get<SettingsMap>('/api/admin/v1/settings')
}

export function updateSettings(settings: SettingsMap) {
  return http.put<SettingsMap>('/api/admin/v1/settings', { settings })
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
