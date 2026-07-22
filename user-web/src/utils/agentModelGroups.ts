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

/** Vite publicDir=asset锛岄潤鎬佽祫婧愯矾寰勪负 /assets/vendor-icons */
export const VENDOR_ICON_BASE = "/assets/vendor-icons"

/** 妯″瀷鍘傚晢锛氭寜妯″瀷鍚?/ 瀹樻柟 API 鍩熷悕璇嗗埆 */
const vendorCatalog: CatalogEntry[] = [
  { key: "vendor:openai", label: "OpenAI", mark: "OA", iconAsset: "openai", patterns: ["api.openai.com", "openai.com/v1", "gpt-", "o1-", "o3-", "chatgpt"] },
  { key: "vendor:deepseek", label: "DeepSeek", mark: "DS", iconAsset: "deepseek", patterns: ["deepseek"] },
  {
    key: "vendor:volcengine",
    label: "火山引擎 / 豆包",
    mark: "VE",
    iconAsset: "doubao",
    patterns: ["doubao", "seedance", "seedream", "bytedance", "volc", "volces.com", "volcengine", "ark.cn-beijing"],
  },
  {
    key: "vendor:qwen",
    label: "閫氫箟鍗冮棶",
    mark: "QW",
    iconAsset: "qwen",
    patterns: ["qwen", "tongyi", "dashscope", "aliyuncs.com"],
  },
  { key: "vendor:zhipu", label: "鏅鸿氨 GLM", mark: "Z", iconAsset: "zhipu", patterns: ["zhipu", "glm", "chatglm", "bigmodel.cn"] },
  { key: "vendor:moonshot", label: "Moonshot / Kimi", mark: "K", iconAsset: "moonshot", patterns: ["moonshot", "kimi"] },
  { key: "vendor:minimax", label: "MiniMax", mark: "MM", iconAsset: "minimax", patterns: ["minimax"] },
  { key: "vendor:baidu", label: "鏂囧績 ERNIE", mark: "BD", iconAsset: "baidu", patterns: ["ernie", "wenxin", "baidu"] },
  { key: "vendor:google", label: "Google Gemini", mark: "G", iconAsset: "gemini", patterns: ["gemini", "generativelanguage.googleapis", "google.ai"] },
  { key: "vendor:claude", label: "Claude", mark: "C", iconAsset: "claude", patterns: ["claude"] },
  { key: "vendor:anthropic", label: "Anthropic", mark: "A", iconAsset: "anthropic", patterns: ["anthropic"] },
  { key: "vendor:alibabacloud", label: "阿里云", mark: "ALI", iconAsset: "alibabacloud", patterns: ["alibabacloud", "alibaba cloud"] },
  { key: "vendor:tencent", label: "鑵捐娣峰厓", mark: "HY", iconAsset: "tencent", patterns: ["hunyuan", "tencent"] },
  { key: "vendor:kling", label: "鍙伒 Kling", mark: "KL", iconAsset: "kling", patterns: ["kling"] },
  { key: "vendor:agnes", label: "Agnes AI", mark: "A", iconAsset: "agnes", patterns: ["agnes", "apihub.agnes-ai.com", "agnes-ai.com"] },
  { key: "vendor:mineru", label: "MinerU", mark: "M", iconAsset: "mineru", patterns: ["mineru"] },
  { key: "vendor:stepfun", label: "阶跃 StepFun", mark: "SF", iconAsset: "stepfun", patterns: ["stepfun", "step-"] },
  { key: "vendor:yi", label: "闆朵竴涓囩墿 Yi", mark: "Y", iconAsset: "yi", patterns: ["yi-lightning", "01.ai", "lingyi"] },
  { key: "vendor:mistral", label: "Mistral", mark: "M", iconAsset: "mistral", patterns: ["mistral"] },
  { key: "vendor:xai", label: "xAI Grok", mark: "X", iconAsset: "xai", patterns: ["grok", "x.ai", "xai"] },
  { key: "vendor:meta", label: "Meta Llama", mark: "Ll", iconAsset: "meta", patterns: ["llama", "meta-llama"] },
  { key: "vendor:cohere", label: "Cohere", mark: "Co", iconAsset: "cohere", patterns: ["cohere"] },
]

const vendorFallback: AgentModelGroupMeta = {
  key: "vendor:other",
  label: "鍏朵粬妯″瀷",
  mark: "AI",
  iconUrl: `${VENDOR_ICON_BASE}/api.svg`,
  kind: "vendor",
}

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
  return [model.displayName, model.channelCode, model.channelLabel]
    .filter(Boolean)
    .join(" ")
    .toLowerCase()
}

function matchesAny(text: string, patterns: string[]) {
  return patterns.some((pattern) => text.includes(pattern.toLowerCase()))
}

function findCatalogMatch(text: string, catalog: CatalogEntry[]) {
  return catalog.find((entry) => matchesAny(text, entry.patterns)) ?? null
}

export function resolveAgentModelGroup(model: AgentModelConfig): AgentModelGroupMeta {
  if (model.channelCode?.trim() || model.channelLabel?.trim() || model.channelIconAsset?.trim()) {
    const key = model.channelCode?.trim() || model.channelIconAsset?.trim() || "channel"
    const iconAsset = model.channelIconAsset?.trim() || key
    return {
      key: `vendor:${key}`,
      label: model.channelLabel?.trim() || key,
      mark: (model.channelLabel?.trim() || key).slice(0, 1).toUpperCase(),
      iconUrl: catalogIconUrl(iconAsset),
      kind: "vendor",
    }
  }
  const allText = modelSearchText(model)
  const vendorFromModel = findCatalogMatch(allText, vendorCatalog)
  if (vendorFromModel) {
    return toMeta(vendorFromModel, "vendor")
  }
  return vendorFallback
}

export function groupKeyForModel(model: AgentModelConfig) {
  return resolveAgentModelGroup(model).key
}

/** 妯″瀷鏉＄洰涓婂睍绀虹殑鐪熷疄鍘傚晢锛堜笉鍙椾腑杞珯鍒嗙粍褰卞搷锛?*/
export function resolveAgentModelVendor(model: AgentModelConfig): AgentModelGroupMeta {
  if (model.channelCode?.trim() || model.channelLabel?.trim() || model.channelIconAsset?.trim()) {
    const key = model.channelCode?.trim() || model.channelIconAsset?.trim() || "channel"
    const iconAsset = model.channelIconAsset?.trim() || key
    return {
      key: `vendor:${key}`,
      label: model.channelLabel?.trim() || key,
      mark: (model.channelLabel?.trim() || key).slice(0, 1).toUpperCase(),
      iconUrl: catalogIconUrl(iconAsset),
      kind: "vendor",
    }
  }
  const allText = modelSearchText(model)
  const vendorFromModel = findCatalogMatch(allText, vendorCatalog)
  if (vendorFromModel) {
    return toMeta(vendorFromModel, "vendor")
  }
  return vendorFallback
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
