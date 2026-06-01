import { ApiBusinessError } from "@/api/client"

const CREDIT_ERROR_CODES = new Set(["CREDIT_NOT_ENOUGH", "AGENT_CREDIT_NOT_ENOUGH"])

export function isCreditInsufficient(
  error: unknown,
  errorCode?: string | null,
  errorMessage?: string | null,
): boolean {
  if (errorCode && CREDIT_ERROR_CODES.has(errorCode)) {
    return true
  }
  if (error instanceof ApiBusinessError && CREDIT_ERROR_CODES.has(error.code)) {
    return true
  }
  const text = `${errorMessage ?? ""} ${error instanceof Error ? error.message : ""}`.trim()
  if (!text) {
    return false
  }
  if (CREDIT_ERROR_CODES.has(text)) {
    return true
  }
  if (text.includes("可用算力不足") || text.includes("算力不足")) {
    return true
  }
  const codeMatch = text.match(/"code"\s*:\s*"(CREDIT_NOT_ENOUGH|AGENT_CREDIT_NOT_ENOUGH)"/)
  return Boolean(codeMatch)
}
