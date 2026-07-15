import { http } from "./http"

export type ProxySourceType = "SUBSCRIPTION" | "MANUAL"
export type ManualProxyProtocol = "HTTP" | "HTTPS" | "SOCKS5"

export interface ProxyConfig {
  enabled: boolean
  sourceType: ProxySourceType
  displayName: string
  subscriptionConfigured: boolean
  subscriptionUrlMasked: string
  subscriptionUpdateIntervalMinutes: number
  mihomoEndpoint: string
  manualProtocol: ManualProxyProtocol
  manualHost: string
  manualPort: number
  manualUsernameMasked: string
  manualPasswordConfigured: boolean
  noProxyHosts: string
  proxyUrlMasked: string
}

export interface ProxyConfigUpdate {
  enabled: boolean
  sourceType: ProxySourceType
  displayName: string
  subscriptionUrl: string
  subscriptionUpdateIntervalMinutes: number
  mihomoEndpoint: string
  manualProtocol: ManualProxyProtocol
  manualHost: string
  manualPort: number
  manualUsername: string
  manualPassword: string
  noProxyHosts: string
}

export interface ProxyTestResult {
  success: boolean
  latencyMs: number
  message: string
  checkedTarget: string
}

export interface MihomoRuntimeStatus {
  managed: boolean
  available: boolean
  version: string
  lastAppliedAt: string | null
  message: string
}

export function fetchProxyConfig() {
  return http.get<ProxyConfig>("/api/admin/v1/proxy-config")
}

export function updateProxyConfig(config: ProxyConfigUpdate) {
  return http.put<ProxyConfig>("/api/admin/v1/proxy-config", config)
}

export function testProxyConnection() {
  return http.post<ProxyTestResult>("/api/admin/v1/proxy-config/test")
}

export function fetchMihomoRuntime() {
  return http.get<MihomoRuntimeStatus>("/api/admin/v1/proxy-config/runtime")
}

export function applyMihomoConfig() {
  return http.post<MihomoRuntimeStatus>("/api/admin/v1/proxy-config/apply")
}
