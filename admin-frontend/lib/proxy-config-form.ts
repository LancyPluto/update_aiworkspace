import type {
  ManualProxyProtocol,
  ProxyConfig,
  ProxyConfigUpdate,
  ProxySourceType,
} from "./api/proxy-config"

export interface ProxyConfigFormState {
  enabled: boolean
  sourceType: ProxySourceType
  displayName: string
  subscriptionUrl: string
  subscriptionConfigured: boolean
  subscriptionUrlMasked: string
  subscriptionUpdateIntervalMinutes: string
  mihomoEndpoint: string
  manualProtocol: ManualProxyProtocol
  manualHost: string
  manualPort: string
  manualUsername: string
  manualUsernameMasked: string
  manualPassword: string
  manualPasswordConfigured: boolean
  noProxyHosts: string
  proxyUrlMasked: string
}

export type ProxyFormErrors = Partial<Record<keyof ProxyConfigFormState, string>>

export const defaultProxyFormState: ProxyConfigFormState = {
  enabled: false,
  sourceType: "SUBSCRIPTION",
  displayName: "默认代理",
  subscriptionUrl: "",
  subscriptionConfigured: false,
  subscriptionUrlMasked: "",
  subscriptionUpdateIntervalMinutes: "360",
  mihomoEndpoint: "http://host.docker.internal:7890",
  manualProtocol: "HTTP",
  manualHost: "",
  manualPort: "7890",
  manualUsername: "",
  manualUsernameMasked: "",
  manualPassword: "",
  manualPasswordConfigured: false,
  noProxyHosts: "localhost,127.0.0.1,::1,0.0.0.0,backend,host.docker.internal",
  proxyUrlMasked: "",
}

export function proxyConfigToFormState(config: ProxyConfig): ProxyConfigFormState {
  return {
    enabled: config.enabled,
    sourceType: config.sourceType,
    displayName: config.displayName,
    subscriptionUrl: "",
    subscriptionConfigured: config.subscriptionConfigured,
    subscriptionUrlMasked: config.subscriptionUrlMasked,
    subscriptionUpdateIntervalMinutes: String(config.subscriptionUpdateIntervalMinutes),
    mihomoEndpoint: config.mihomoEndpoint,
    manualProtocol: config.manualProtocol,
    manualHost: config.manualHost,
    manualPort: String(config.manualPort),
    manualUsername: "",
    manualUsernameMasked: config.manualUsernameMasked,
    manualPassword: "",
    manualPasswordConfigured: config.manualPasswordConfigured,
    noProxyHosts: config.noProxyHosts,
    proxyUrlMasked: config.proxyUrlMasked,
  }
}

export function validateProxyForm(form: ProxyConfigFormState): ProxyFormErrors {
  const errors: ProxyFormErrors = {}
  if (!form.displayName.trim()) errors.displayName = "请输入配置名称"
  if (!form.noProxyHosts.trim()) errors.noProxyHosts = "请输入至少一个直连主机"

  if (form.sourceType === "SUBSCRIPTION") {
    if (!form.subscriptionConfigured && !form.subscriptionUrl.trim()) {
      errors.subscriptionUrl = "请输入机场订阅链接"
    } else if (form.subscriptionUrl.trim() && !isHttpUrl(form.subscriptionUrl)) {
      errors.subscriptionUrl = "订阅链接必须是有效的 HTTP/HTTPS 地址"
    }
    const interval = Number(form.subscriptionUpdateIntervalMinutes)
    if (!Number.isInteger(interval) || interval < 15 || interval > 10_080) {
      errors.subscriptionUpdateIntervalMinutes = "更新间隔需在 15 到 10080 分钟之间"
    }
    if (!isProxyEndpoint(form.mihomoEndpoint)) {
      errors.mihomoEndpoint = "请输入包含协议、主机和端口的 Mihomo 出口地址"
    }
  } else {
    if (!isPublicIpv4(form.manualHost)) {
      errors.manualHost = "请输入可路由的公网 IPv4 地址"
    }
    const port = Number(form.manualPort)
    if (!Number.isInteger(port) || port < 1 || port > 65_535) {
      errors.manualPort = "端口需在 1 到 65535 之间"
    }
  }
  return errors
}

export function proxyFormToUpdate(form: ProxyConfigFormState): ProxyConfigUpdate {
  return {
    enabled: form.enabled,
    sourceType: form.sourceType,
    displayName: form.displayName.trim(),
    subscriptionUrl: form.subscriptionUrl.trim(),
    subscriptionUpdateIntervalMinutes: Number(form.subscriptionUpdateIntervalMinutes),
    mihomoEndpoint: form.mihomoEndpoint.trim(),
    manualProtocol: form.manualProtocol,
    manualHost: form.manualHost.trim(),
    manualPort: Number(form.manualPort),
    manualUsername: form.manualUsername.trim(),
    manualPassword: form.manualPassword,
    noProxyHosts: form.noProxyHosts.trim(),
  }
}

function isHttpUrl(value: string) {
  try {
    const url = new URL(value.trim())
    return (url.protocol === "http:" || url.protocol === "https:") && Boolean(url.hostname)
  } catch {
    return false
  }
}

function isProxyEndpoint(value: string) {
  try {
    const url = new URL(value.trim())
    return ["http:", "https:", "socks5:"].includes(url.protocol) && Boolean(url.hostname) && Boolean(url.port)
  } catch {
    return false
  }
}

function isPublicIpv4(value: string) {
  const parts = value.trim().split(".")
  if (parts.length !== 4 || parts.some((part) => !/^\d{1,3}$/.test(part))) return false
  const numbers = parts.map(Number)
  if (numbers.some((part) => part < 0 || part > 255)) return false
  const [a, b] = numbers
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false
  if (a === 100 && b >= 64 && b <= 127) return false
  if (a === 169 && b === 254) return false
  if (a === 172 && b >= 16 && b <= 31) return false
  if (a === 192 && b === 168) return false
  return true
}
