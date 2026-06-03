import type { CreditInsufficientDetail } from "./types"
import { ApiBusinessError } from "./client"

export function formatCreditInsufficientMessage(
  detail?: CreditInsufficientDetail,
  fallbackMessage?: string,
): string {
  if (detail) {
    if (detail.toolCode) {
      return (
        `可用算力不足：当前 ${detail.availableCredits}，调用「${detail.toolCode}」至少需要 ${detail.requiredCredits}。` +
        "请前往「会员与算力」充值后再试。"
      )
    }
    return (
      `可用算力不足：当前 ${detail.availableCredits}，至少需要 ${detail.requiredCredits}。` +
      "请前往「会员与算力」充值后再试。"
    )
  }
  if (fallbackMessage?.trim()) {
    return fallbackMessage.trim()
  }
  return "可用算力不足，请前往「会员与算力」充值后再试。"
}

export function formatCreditInsufficientError(error: ApiBusinessError): string {
  return formatCreditInsufficientMessage(error.data, error.message)
}

export function isCreditInsufficientCode(code?: string): boolean {
  return code === "CREDIT_NOT_ENOUGH" || code === "AGENT_CREDIT_NOT_ENOUGH"
}
