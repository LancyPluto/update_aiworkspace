import type { TaskEstimateResponse, ToolDetail, ToolSummary } from "@/api/types"
import { isWorkflowTool } from "@/adapters/toolPresentationAdapter"

type CreditTool = Pick<ToolSummary, "estimatedCreditCost" | "variableCreditPricing" | "toolCode">

export function usesVariableWorkflowCredits(tool?: CreditTool | null): boolean {
  if (!tool) return false
  if (tool.variableCreditPricing) return true
  return isWorkflowTool(tool) && (tool.estimatedCreditCost == null || tool.estimatedCreditCost <= 0)
}

export function formatToolCreditLabel(tool?: CreditTool | ToolDetail | null): string {
  if (!tool) return "0 算力"
  if (usesVariableWorkflowCredits(tool)) {
    return "算力不详"
  }
  return `${tool.estimatedCreditCost ?? 0} 算力`
}

export function formatToolCreditHint(tool?: CreditTool | ToolDetail | null): string {
  if (usesVariableWorkflowCredits(tool)) {
    return "按每次实际调用的模型成本响应式扣减算力，步骤越多消耗越高"
  }
  return "创建任务时将按预估算力冻结，成功后按实际用量结算"
}

export function formatMarketplaceCostLabel(tool?: CreditTool | ToolDetail | null): string {
  if (!tool) return "算力不详"
  if (usesVariableWorkflowCredits(tool)) return "算力不详"
  if (tool.estimatedCreditCost === 0) return "免费"
  return `约 ${tool.estimatedCreditCost} 算力/次`
}

export interface LiveCreditEstimateView {
  label: string
  insufficient: boolean
  hint?: string
}

/** 将权威预估接口结果格式化为创作区展示文案 */
export function formatLiveCreditEstimate(
  estimate: TaskEstimateResponse | null | undefined,
  options?: { loading?: boolean; fallbackTool?: CreditTool | ToolDetail | null },
): LiveCreditEstimateView {
  const { loading, fallbackTool } = options ?? {}
  if (loading && !estimate) {
    return { label: "估算中…", insufficient: false }
  }
  if (estimate) {
    if (estimate.variable) {
      return {
        label: "算力不详",
        insufficient: false,
        hint: "按每次实际调用的模型成本响应式扣减",
      }
    }
    const breakdownHint = estimate.breakdown
      .filter((item) => item.label && item.credits != null)
      .slice(0, 3)
      .map((item) => `${item.label}${item.detail ? `(${item.detail})` : ""}`)
      .join(" · ")
    return {
      label: `约 ${estimate.estimatedCredits} 算力`,
      insufficient: !estimate.sufficient,
      hint: breakdownHint || undefined,
    }
  }
  if (fallbackTool) {
    return { label: formatToolCreditLabel(fallbackTool), insufficient: false }
  }
  return { label: "", insufficient: false }
}
