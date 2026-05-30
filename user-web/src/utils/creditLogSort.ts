import type { CreditLog } from "@/api/types"

/** 算力流水按 createdAt 倒序展示（与后端一致，不依赖流水 ID 字符串）。 */
export function sortCreditLogsByCreatedAtDesc(logs: CreditLog[]): CreditLog[] {
  return [...logs].sort((a, b) => {
    const timeA = Date.parse(a.createdAt)
    const timeB = Date.parse(b.createdAt)
    const safeA = Number.isNaN(timeA) ? 0 : timeA
    const safeB = Number.isNaN(timeB) ? 0 : timeB
    if (safeB !== safeA) {
      return safeB - safeA
    }
    return b.id - a.id
  })
}
