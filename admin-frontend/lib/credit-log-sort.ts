/** 算力流水：按创建时间倒序（新在前），禁止用流水 ID 字符串排序。 */
export function compareCreditLogsByCreatedAtDesc(
  a: { createdAt?: string | null; id?: number },
  b: { createdAt?: string | null; id?: number },
): number {
  const timeA = a.createdAt ? Date.parse(a.createdAt) : 0
  const timeB = b.createdAt ? Date.parse(b.createdAt) : 0
  const safeA = Number.isNaN(timeA) ? 0 : timeA
  const safeB = Number.isNaN(timeB) ? 0 : timeB
  if (safeB !== safeA) {
    return safeB - safeA
  }
  return (b.id ?? 0) - (a.id ?? 0)
}
