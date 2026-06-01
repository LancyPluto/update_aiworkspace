import type { AgentModelConfig } from "@/api/types"

export type AgentModelGroupKind = "vendor" | "relay"

export interface AgentModelGroupMeta {
  key: string
  label: string
  mark: string
  iconUrl: string | null
  kind: AgentModelGroupKind
}

export interface AgentModelGroup extends AgentModelGroupMeta {
  models: AgentModelConfig[]
}

type CatalogEntry = {
  key: string
  label: string
  mark: string
  iconAsset: string
  patterns: string[]
}

/** Vite publicDir=asset，静态资源路径为 /assets/vendor-icons */
export const VENDOR_ICON_BASE = "/assets/vendor-icons"

/** 中转站：优先按 baseUrl 域名匹配 */
const relayCatalog: CatalogEntry[] = [
  { key: "relay:siliconflow", label: "硅基流动", mark: "SF", iconAsset: "siliconflow", patterns: ["siliconflow"] },
  { key: "relay:openrouter", label: "OpenRouter", mark: "OR", iconAsset: "openrouter", patterns: ["openrouter"] },
  { key: "relay:ofox", label: "oFox", mark: "OX", iconAsset: "ofox", patterns: ["ofox.ai", "ofox"] },
  { key: "relay:api2d", label: "API2D", mark: "2D", iconAsset: "api2d", patterns: ["api2d", "openai.api2d"] },
  { key: "relay:closeai", label: "CloseAI", mark: "CA", iconAsset: "closeai", patterns: ["closeai", "close-ai"] },
  { key: "relay:newapi", label: "New API", mark: "N", iconAsset: "newapi", patterns: ["new-api", "newapi", "one-api", "oneapi"] },
  { key: "relay:oneapi", label: "One API", mark: "1", iconAsset: "oneapi", patterns: ["oneapi"] },
  { key: "relay:aiproxy", label: "AI Proxy", mark: "PX", iconAsset: "aiproxy", patterns: ["aiproxy", "ai-proxy"] },
  { key: "relay:groq", label: "Groq", mark: "GQ", iconAsset: "groq", patterns: ["groq.com"] },
  { key: "relay:together", label: "Together AI", mark: "TG", iconAsset: "together", patterns: ["together.ai", "together.xyz"] },
  { key: "relay:azure", label: "Azure OpenAI", mark: "Az", iconAsset: "azure", patterns: ["openai.azure.com", ".azure.com/openai"] },
]

/** 模型厂商：按模型名 / 官方 API 域名识别 */
const vendorCatalog: CatalogEntry[] = [
  { key: "vendor:openai", label: "OpenAI", mark: "OA", iconAsset: "openai", patterns: ["api.openai.com", "openai.com/v1", "gpt-", "o1-", "o3-", "chatgpt"] },
  { key: "vendor:deepseek", label: "DeepSeek", mark: "DS", iconAsset: "deepseek", patterns: ["deepseek"] },
  {
    key: "vendor:doubao",
    label: "豆包",
    mark: "DB",
    iconAsset: "doubao",
    patterns: ["doubao", "seedance", "seedream", "bytedance"],
  },
  {
    key: "vendor:volcengine",
    label: "火山引擎",
    mark: "VE",
    iconAsset: "volcengine",
    patterns: ["volc", "volces.com", "volcengine", "ark.cn-beijing"],
  },
  {
    key: "vendor:qwen",
    label: "通义千问",
    mark: "QW",
    iconAsset: "qwen",
    patterns: ["qwen", "tongyi", "dashscope", "aliyuncs.com"],
  },
  { key: "vendor:zhipu", label: "智谱 GLM", mark: "Z", iconAsset: "zhipu", patterns: ["zhipu", "glm", "chatglm", "bigmodel.cn"] },
  { key: "vendor:moonshot", label: "Moonshot / Kimi", mark: "K", iconAsset: "moonshot", patterns: ["moonshot", "kimi"] },
  { key: "vendor:minimax", label: "MiniMax", mark: "MM", iconAsset: "minimax", patterns: ["minimax"] },
  { key: "vendor:baidu", label: "文心 ERNIE", mark: "BD", iconAsset: "baidu", patterns: ["ernie", "wenxin", "baidu"] },
  { key: "vendor:google", label: "Google Gemini", mark: "G", iconAsset: "gemini", patterns: ["gemini", "generativelanguage.googleapis", "google.ai"] },
  { key: "vendor:claude", label: "Claude", mark: "C", iconAsset: "claude", patterns: ["claude"] },
  { key: "vendor:anthropic", label: "Anthropic", mark: "A", iconAsset: "anthropic", patterns: ["anthropic"] },
  { key: "vendor:alibabacloud", label: "阿里云", mark: "ALI", iconAsset: "alibabacloud", patterns: ["alibabacloud", "alibaba cloud"] },
  { key: "vendor:tencent", label: "腾讯混元", mark: "HY", iconAsset: "tencent", patterns: ["hunyuan", "tencent"] },
  { key: "vendor:kling", label: "可灵 Kling", mark: "KL", iconAsset: "kling", patterns: ["kling"] },
  { key: "vendor:stepfun", label: "阶跃 StepFun", mark: "SF", iconAsset: "stepfun", patterns: ["stepfun", "step-"] },
  { key: "vendor:yi", label: "零一万物 Yi", mark: "Y", iconAsset: "yi", patterns: ["yi-lightning", "01.ai", "lingyi"] },
  { key: "vendor:mistral", label: "Mistral", mark: "M", iconAsset: "mistral", patterns: ["mistral"] },
  { key: "vendor:xai", label: "xAI Grok", mark: "X", iconAsset: "xai", patterns: ["grok", "x.ai", "xai"] },
  { key: "vendor:meta", label: "Meta Llama", mark: "Ll", iconAsset: "meta", patterns: ["llama", "meta-llama"] },
  { key: "vendor:cohere", label: "Cohere", mark: "Co", iconAsset: "cohere", patterns: ["cohere"] },
]

const vendorFallback: AgentModelGroupMeta = {
  key: "vendor:other",
  label: "其他模型",
  mark: "AI",
  iconUrl: `${VENDOR_ICON_BASE}/api.svg`,
  kind: "vendor",
}

const relayFallbackIcon = `${VENDOR_ICON_BASE}/relay.svg`

export function catalogIconUrl(iconAsset: string) {
  return `${VENDOR_ICON_BASE}/${iconAsset}.svg`
}

function toMeta(entry: CatalogEntry, kind: AgentModelGroupKind): AgentModelGroupMeta {
  return {
    key: entry.key,
    label: entry.label,
    mark: entry.mark,
    iconUrl: catalogIconUrl(entry.iconAsset),
    kind,
  }
}

function modelSearchText(model: AgentModelConfig) {
  return [model.displayName, model.modelName, model.configCode, model.provider, model.baseUrl]
    .filter(Boolean)
    .join(" ")
    .toLowerCase()
}

function baseSearchText(model: AgentModelConfig) {
  return `${model.baseUrl || ""} ${model.provider || ""}`.toLowerCase()
}

function matchesAny(text: string, patterns: string[]) {
  return patterns.some((pattern) => text.includes(pattern.toLowerCase()))
}

function findCatalogMatch(text: string, catalog: CatalogEntry[]) {
  return catalog.find((entry) => matchesAny(text, entry.patterns)) ?? null
}

function parseHostname(baseUrl?: string | null) {
  if (!baseUrl?.trim()) return ""
  try {
    return new URL(baseUrl.trim()).hostname.toLowerCase()
  } catch {
    return ""
  }
}

function isOfficialVendorHost(hostname: string) {
  if (!hostname) return false
  const officialHosts = [
    "api.openai.com",
    "api.deepseek.com",
    "dashscope.aliyuncs.com",
    "api.minimaxi.com",
    "open.bigmodel.cn",
    "api.moonshot.cn",
    "ark.cn-beijing.volces.com",
    "api.anthropic.com",
    "generativelanguage.googleapis.com",
    "api-beijing.klingai.com",
    "api.minimax.chat",
  ]
  return officialHosts.some((host) => hostname === host || hostname.endsWith(`.${host}`))
}

function isRelayCompatibleProvider(provider: string) {
  const normalized = provider.toLowerCase()
  return normalized.includes("openai_compatible")
    || normalized.includes("anthropic_compatible")
    || normalized.includes("openai_images")
    || normalized.includes("gateway")
}

function relayLabelFromHost(hostname: string): string {
  const match = findCatalogMatch(hostname, relayCatalog)
  if (match) return match.label
  const short = hostname.replace(/^www\./, "")
  const parts = short.split(".")
  const brand = parts.length >= 2 ? parts[parts.length - 2] : short
  return `中转站 · ${brand}`
}

function relayMetaFromHost(hostname: string): AgentModelGroupMeta {
  const match = findCatalogMatch(hostname, relayCatalog)
  if (match) {
    return toMeta(match, "relay")
  }
  return {
    key: `relay:host:${hostname}`,
    label: relayLabelFromHost(hostname),
    mark: hostname.slice(0, 1).toUpperCase(),
    iconUrl: relayFallbackIcon,
    kind: "relay",
  }
}

export function resolveAgentModelGroup(model: AgentModelConfig): AgentModelGroupMeta {
  const allText = modelSearchText(model)
  const baseText = baseSearchText(model)
  const hostname = parseHostname(model.baseUrl)

  const relayFromBase = findCatalogMatch(baseText, relayCatalog)
  if (relayFromBase) {
    return toMeta(relayFromBase, "relay")
  }

  if (hostname && !isOfficialVendorHost(hostname)) {
    const relayFromHost = findCatalogMatch(hostname, relayCatalog)
    if (relayFromHost) {
      return toMeta(relayFromHost, "relay")
    }
    if (isRelayCompatibleProvider(model.provider || "")) {
      return relayMetaFromHost(hostname)
    }
  }

  const vendorFromModel = findCatalogMatch(allText, vendorCatalog)
  if (vendorFromModel) {
    return toMeta(vendorFromModel, "vendor")
  }

  const vendorFromBase = findCatalogMatch(baseText, vendorCatalog)
  if (vendorFromBase) {
    return toMeta(vendorFromBase, "vendor")
  }

  if ((model.provider || "").toLowerCase().includes("mock")) {
    return { ...vendorFallback, key: "vendor:mock", label: "Mock" }
  }

  const provider = model.provider || "unknown"
  return {
    key: `vendor:provider:${provider}`,
    label: provider,
    mark: provider.slice(0, 1).toUpperCase(),
    iconUrl: vendorFallback.iconUrl,
    kind: "vendor",
  }
}

export function groupKeyForModel(model: AgentModelConfig) {
  return resolveAgentModelGroup(model).key
}

/** 模型条目上展示的真实厂商（不受中转站分组影响） */
export function resolveAgentModelVendor(model: AgentModelConfig): AgentModelGroupMeta {
  const allText = modelSearchText(model)
  const baseText = baseSearchText(model)
  const vendorFromModel = findCatalogMatch(allText, vendorCatalog)
  if (vendorFromModel) {
    return toMeta(vendorFromModel, "vendor")
  }
  const vendorFromBase = findCatalogMatch(baseText, vendorCatalog)
  if (vendorFromBase) {
    return toMeta(vendorFromBase, "vendor")
  }
  if ((model.provider || "").toLowerCase().includes("mock")) {
    return { ...vendorFallback, key: "vendor:mock", label: "Mock" }
  }
  const provider = model.provider || "unknown"
  return {
    key: `vendor:provider:${provider}`,
    label: provider,
    mark: provider.slice(0, 1).toUpperCase(),
    iconUrl: vendorFallback.iconUrl,
    kind: "vendor",
  }
}

export function buildAgentModelGroups(models: AgentModelConfig[]): AgentModelGroup[] {
  const groups = new Map<string, AgentModelGroup>()
  for (const model of models) {
    const meta = resolveAgentModelGroup(model)
    const existing = groups.get(meta.key)
    if (existing) {
      existing.models.push(model)
      continue
    }
    groups.set(meta.key, { ...meta, models: [model] })
  }

  return Array.from(groups.values()).sort((a, b) => {
    if (a.kind !== b.kind) {
      return a.kind === "relay" ? -1 : 1
    }
    return a.label.localeCompare(b.label, "zh-CN")
  })
}

export function vendorIconClassForGroup(meta: AgentModelGroupMeta) {
  if (meta.key.includes("deepseek")) return "model-provider-icon--blue"
  if (meta.key.includes("doubao") || meta.key.includes("volc")) return "model-provider-icon--cyan"
  if (meta.key.includes("qwen")) return "model-provider-icon--purple"
  if (meta.key.includes("google") || meta.key.includes("gemini")) return "model-provider-icon--rainbow"
  if (meta.key.includes("openai")) return "model-provider-icon--green"
  if (meta.kind === "relay") return "model-provider-icon--relay"
  return "model-provider-icon--neutral"
}
