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

export type ProxyPatternType = "EXACT" | "SUFFIX" | "WILDCARD"
export type ProxyRouteStrategy = "DIRECT" | "PROXY" | "AUTO"

export interface ProxyRoutingRule {
  id: string
  patternType: ProxyPatternType
  pattern: string
  strategy: ProxyRouteStrategy
  priority: number
  enabled: boolean
  note: string
  probeUrl: string
}

export interface ProxyAutoSettings {
  timeoutMs: number
  sampleSize: number
  switchThresholdMs: number
  hysteresisMs: number
  cooldownSeconds: number
}

export interface ProxyRoutingConfig {
  rules: ProxyRoutingRule[]
  autoSettings: ProxyAutoSettings
  fallbackStrategy: "DIRECT"
  warnings: string[]
  testResults: Record<string, ProxyDomainTestSummary>
}

export interface ProxyPathProbeResult {
  path: "DIRECT" | "PROXY"
  success: boolean
  dnsMs: number
  tcpMs: number
  tlsMs: number
  httpMs: number
  totalMs: number
  httpStatus: number | null
  error: string
  successRate: number
  sampleCount: number
}

export interface ProxyAutoDecision {
  selectedPath: "DIRECT" | "PROXY"
  reason: string
  sampleCount: number
  timeoutMs: number
  switchThresholdMs: number
  hysteresisMs: number
  cooldownSeconds: number
}

export interface ProxyDomainTestResult {
  domain: string
  matchedRuleId: string
  matchedStrategy: ProxyRouteStrategy
  probeMethod: "HEAD"
  direct: ProxyPathProbeResult
  proxy: ProxyPathProbeResult
  autoDecision: ProxyAutoDecision
  testedAt: string
}

export interface ProxyDomainTestSummary {
  success: boolean
  latencyMs: number
  testedAt: string
}

export interface MihomoNodeItem {
  name: string
  type: string
  available: boolean
  latencyMs: number
  selected: boolean
}

export interface MihomoNodeList {
  managed: boolean
  available: boolean
  sourceType: ProxySourceType
  selectionMode: "AUTO" | "MANUAL"
  selectedNode: string
  activeNode: string
  nodes: MihomoNodeItem[]
  checkedAt: string
  message: string
}

export interface MihomoNodeActionResult {
  success: boolean
  message: string
}

export interface MihomoNodeTestResult {
  nodeName: string
  available: boolean
  latencyMs: number
  testedAt: string
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

export function fetchProxyRoutingConfig() {
  return http.get<ProxyRoutingConfig>("/api/admin/v1/proxy-config/routing")
}

export function updateProxyRoutingConfig(config: Pick<ProxyRoutingConfig, "rules" | "autoSettings">) {
  return http.put<ProxyRoutingConfig>("/api/admin/v1/proxy-config/routing", config)
}

export function testProxyDomain(domain: string, probeUrl = "") {
  return http.post<ProxyDomainTestResult>("/api/admin/v1/proxy-config/routing/test", { domain, probeUrl })
}

export function fetchMihomoNodes() {
  return http.get<MihomoNodeList>("/api/admin/v1/proxy-config/nodes")
}

export function refreshMihomoSubscription() {
  return http.post<MihomoNodeActionResult>("/api/admin/v1/proxy-config/nodes/refresh")
}

export function testAllMihomoNodes() {
  return http.post<MihomoNodeActionResult>("/api/admin/v1/proxy-config/nodes/test-all")
}

export function testMihomoNode(nodeName: string) {
  return http.post<MihomoNodeTestResult>("/api/admin/v1/proxy-config/nodes/test", { nodeName })
}

export function selectMihomoNode(mode: "AUTO" | "MANUAL", nodeName = "") {
  return http.put<MihomoNodeActionResult>("/api/admin/v1/proxy-config/nodes/selection", { mode, nodeName })
}
