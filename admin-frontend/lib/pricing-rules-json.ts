import type { PricingRule } from "@/lib/api/pricing"

export interface PricingRuleJsonItem {
  paramKey: string
  ruleType: string
  matchOp: string
  matchValue?: string | null
  factor?: number
  extraCredits?: number
  priority?: number
  enabled?: boolean
  remark?: string | null
}

export const HAPPYHORSE_PRICING_RULES_EXAMPLE: PricingRuleJsonItem[] = [
  {
    paramKey: "resolution",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "1080P",
    factor: 1.7778,
    extraCredits: 0,
    priority: 50,
    enabled: true,
    remark: "HappyHorse 1080P 1.6元/秒 vs 720P 0.9元/秒",
  },
]

/** OpenAI GPT Image 2 official per-image rates (1024x1024): low $0.006, medium $0.053, high $0.211 */
export const GPT_IMAGE2_PRICING_RULES_EXAMPLE: PricingRuleJsonItem[] = [
  {
    paramKey: "count",
    ruleType: "MULTIPLIER",
    matchOp: "VALUE",
    factor: 1,
    extraCredits: 0,
    priority: 40,
    enabled: true,
    remark: "按生成数量 count 倍率（参数值即倍数）",
  },
  {
    paramKey: "quality",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "medium",
    factor: 8.8333,
    extraCredits: 0,
    priority: 50,
    enabled: true,
    remark: "GPT Image 2 medium $0.053 vs low $0.006 (1024x1024)",
  },
  {
    paramKey: "quality",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "high",
    factor: 35.1667,
    extraCredits: 0,
    priority: 51,
    enabled: true,
    remark: "GPT Image 2 high $0.211 vs low $0.006 (1024x1024)",
  },
]

export const KLING_VIDEO_PRICING_RULES_EXAMPLE: PricingRuleJsonItem[] = [
  {
    paramKey: "mode",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "pro",
    factor: 1.5,
    extraCredits: 0,
    priority: 50,
    enabled: true,
    remark: "pro 相对 std 倍率（duration 由 PER_SECOND 自动乘算）",
  },
  {
    paramKey: "sound",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "on",
    factor: 1.2,
    extraCredits: 0,
    priority: 51,
    enabled: true,
    remark: "开启声音",
  },
  {
    paramKey: "model",
    ruleType: "MULTIPLIER",
    matchOp: "EQ",
    matchValue: "kling-v2-5-turbo",
    factor: 0.8,
    extraCredits: 0,
    priority: 60,
    enabled: true,
    remark: "Turbo 版本折价示例",
  },
]

export const KLING_IMAGE_PRICING_RULES_EXAMPLE: PricingRuleJsonItem[] = [
  {
    paramKey: "count",
    ruleType: "MULTIPLIER",
    matchOp: "VALUE",
    factor: 1,
    extraCredits: 0,
    priority: 40,
    enabled: true,
    remark: "按生成数量 count 倍率",
  },
]

export function pricingRulesForModelExport(rules: PricingRule[], modelConfigId: number): PricingRuleJsonItem[] {
  return rules
    .filter((rule) => rule.scopeType === "MODEL" && (rule.scopeRef ?? 0) === modelConfigId)
    .map((rule) => ({
      paramKey: rule.paramKey,
      ruleType: rule.ruleType,
      matchOp: rule.matchOp,
      matchValue: rule.matchValue ?? undefined,
      factor: rule.factor,
      extraCredits: rule.extraCredits,
      priority: rule.priority,
      enabled: rule.enabled,
      remark: rule.remark ?? undefined,
    }))
}

export function parsePricingRulesJson(text: string): PricingRuleJsonItem[] {
  const trimmed = text.trim()
  if (!trimmed) return []
  const parsed = JSON.parse(trimmed) as unknown
  if (!Array.isArray(parsed)) {
    throw new Error("定价规则须为 JSON 数组")
  }
  return parsed.map((item, index) => validatePricingRuleJsonItem(item, index))
}

function validatePricingRuleJsonItem(item: unknown, index: number): PricingRuleJsonItem {
  if (!item || typeof item !== "object") {
    throw new Error(`定价规则第 ${index + 1} 项无效`)
  }
  const row = item as Record<string, unknown>
  const paramKey = typeof row.paramKey === "string" ? row.paramKey.trim() : ""
  if (!paramKey) {
    throw new Error(`定价规则第 ${index + 1} 项缺少 paramKey`)
  }
  return {
    paramKey,
    ruleType: typeof row.ruleType === "string" ? row.ruleType : "MULTIPLIER",
    matchOp: typeof row.matchOp === "string" ? row.matchOp : "EQ",
    matchValue: typeof row.matchValue === "string" ? row.matchValue : row.matchValue == null ? undefined : String(row.matchValue),
    factor: typeof row.factor === "number" ? row.factor : Number(row.factor ?? 1),
    extraCredits: typeof row.extraCredits === "number" ? row.extraCredits : Number(row.extraCredits ?? 0),
    priority: typeof row.priority === "number" ? row.priority : Number(row.priority ?? 100),
    enabled: row.enabled === undefined ? true : Boolean(row.enabled),
    remark: typeof row.remark === "string" ? row.remark : undefined,
  }
}

export function pricingRuleJsonToPayload(modelConfigId: number, item: PricingRuleJsonItem) {
  return {
    scopeType: "MODEL" as const,
    scopeRef: modelConfigId,
    paramKey: item.paramKey,
    ruleType: item.ruleType || "MULTIPLIER",
    matchOp: item.matchOp || "EQ",
    matchValue: item.matchValue ?? null,
    factor: item.factor ?? 1,
    extraCredits: item.extraCredits ?? 0,
    priority: item.priority ?? 100,
    enabled: item.enabled ?? true,
    remark: item.remark ?? "",
  }
}
