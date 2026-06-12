import type { ToolDetail, ToolSummary } from "@/api/types"
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
    return "按每次实际调用的模型成本 ×1.2 扣减算力，步骤越多消耗越高"
  }
  return "创建任务时将按预估算力冻结，成功后结算"
}
