import { http } from './http'
import type { ConfigBundle, ConfigBundleImportResult } from './types'

export function exportConfigBundle() {
  return http.get<ConfigBundle>('/api/admin/v1/config-bundles/export')
}

export function importConfigBundle(bundle: ConfigBundle) {
  return http.post<ConfigBundleImportResult>('/api/admin/v1/config-bundles/import', bundle)
}

export function downloadConfigBundle(bundle: ConfigBundle) {
  const text = JSON.stringify(bundle, null, 2)
  const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const date = new Date().toISOString().slice(0, 10)
  const link = document.createElement('a')
  link.href = url
  link.download = `ai-tool-market-config-${date}.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

export function readConfigBundleFile(file: File): Promise<ConfigBundle> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      try {
        resolve(JSON.parse(String(reader.result || '{}')) as ConfigBundle)
      } catch (error) {
        reject(error)
      }
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file, 'utf-8')
  })
}
