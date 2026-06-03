import type { AgentModelConfig } from "@/api/types"

function toNumber(value: number | string | null | undefined) {
  if (value == null || value === "") return null
  const num = typeof value === "number" ? value : Number(value)
  return Number.isFinite(num) ? num : null
}

function trimAmount(value: number) {
  if (Number.isInteger(value)) return String(value)
  return value.toFixed(4).replace(/\.?0+$/, "")
}

export function formatModelPriceSummary(model: AgentModelConfig): string {
  const unit = (model.billingUnit || "TOKEN_PER_M").toUpperCase()
  if (unit === "PER_CALL") {
    const price = toNumber(model.unitPrice)
    return price != null && price > 0 ? `¥${trimAmount(price)}/次` : "按次计费"
  }
  if (unit === "IMAGE_TOKEN") {
    const price = toNumber(model.unitPrice)
    return price != null && price > 0 ? `¥${trimAmount(price)}/图` : "按图计费"
  }

  const input =
    toNumber(model.inputTokenPricePer1m)
    ?? (toNumber(model.inputTokenPricePer1k) != null ? (toNumber(model.inputTokenPricePer1k) as number) * 1000 : null)
  const output =
    toNumber(model.outputTokenPricePer1m)
    ?? (toNumber(model.outputTokenPricePer1k) != null ? (toNumber(model.outputTokenPricePer1k) as number) * 1000 : null)

  if (input == null && output == null) return "—"
  if (input != null && output != null && input === output) {
    return `¥${trimAmount(input)}/百万 tokens`
  }
  const parts: string[] = []
  if (input != null) parts.push(`入 ¥${trimAmount(input)}`)
  if (output != null) parts.push(`出 ¥${trimAmount(output)}`)
  return `${parts.join(" · ")}/百万 tokens`
}
