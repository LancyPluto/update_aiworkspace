import type { ComicEpisodeDetail, ComicShot, ComicShotAttempt } from "@/api/comicProjectApi"

export type ComicWorkspaceStage = "script" | "storyboard" | "assets" | "shots" | "delivery"
export type ComicEpisodeGenerationStatus = "SCRIPT_GENERATING" | "STORYBOARD_GENERATING"

const COMIC_EPISODE_GENERATION_STATUSES = new Set<ComicEpisodeGenerationStatus>([
  "SCRIPT_GENERATING",
  "STORYBOARD_GENERATING",
])

export const COMIC_WORKSPACE_STAGES: Array<{
  id: ComicWorkspaceStage
  label: string
  shortLabel: string
}> = [
  { id: "script", label: "剧本", shortLabel: "剧本" },
  { id: "storyboard", label: "分镜", shortLabel: "分镜" },
  { id: "assets", label: "角色与场景", shortLabel: "资产" },
  { id: "shots", label: "镜头生成", shortLabel: "镜头" },
  { id: "delivery", label: "合成交付", shortLabel: "交付" },
]

export function stageIndex(stage: ComicWorkspaceStage): number {
  return COMIC_WORKSPACE_STAGES.findIndex((item) => item.id === stage)
}

export function comicEpisodeGenerationStatus(status?: string | null): ComicEpisodeGenerationStatus | null {
  const normalized = String(status ?? "").toUpperCase() as ComicEpisodeGenerationStatus
  return COMIC_EPISODE_GENERATION_STATUSES.has(normalized) ? normalized : null
}

export function currentComicStage(episode?: ComicEpisodeDetail | null): ComicWorkspaceStage {
  if (!episode) return "script"
  if (comicEpisodeGenerationStatus(episode.status) === "STORYBOARD_GENERATING") return "storyboard"
  if (episode.latestAssemblyBatch?.status === "SUCCESS") return "delivery"
  if (episode.latestAssemblyBatch || episode.latestGenerationBatch?.status === "SUCCESS") return "delivery"
  if (episode.assetsConfirmed) return "shots"
  if (episode.storyboardLocked) return "assets"
  if ((episode.shots?.length ?? 0) > 0) return "storyboard"
  return "script"
}

export function isComicStageAccessible(stage: ComicWorkspaceStage, episode?: ComicEpisodeDetail | null): boolean {
  if (stage === "script") return true
  if (!episode) return false
  if (stage === "storyboard") return Boolean(episode.scriptText?.trim())
  if (stage === "assets") return Boolean(episode.storyboardLocked)
  if (stage === "shots") return Boolean(episode.assetsConfirmed)
  return Boolean(episode.assetsConfirmed && episode.latestGenerationBatch?.status === "SUCCESS")
}

export function resequenceComicShots(shots: ComicShot[]): ComicShot[] {
  return shots.map((shot, index) => ({ ...shot, sequenceNo: index + 1 }))
}

export function moveComicShot(shots: ComicShot[], fromIndex: number, toIndex: number): ComicShot[] {
  if (fromIndex < 0 || fromIndex >= shots.length || toIndex < 0 || toIndex >= shots.length || fromIndex === toIndex) {
    return resequenceComicShots(shots)
  }
  const copy = shots.map((shot) => ({ ...shot }))
  const [moved] = copy.splice(fromIndex, 1)
  if (moved) copy.splice(toIndex, 0, moved)
  return resequenceComicShots(copy)
}

export interface ComicStoryboardValidation {
  valid: boolean
  errors: string[]
  shotCount: number
  totalDurationMs: number
}

export function validateComicStoryboard(shots: ComicShot[]): ComicStoryboardValidation {
  const errors: string[] = []
  const totalDurationMs = shots.reduce((sum, shot) => sum + Math.max(0, Number(shot.durationMs) || 0), 0)
  if (shots.length < 6 || shots.length > 18) errors.push("分镜数量需为 6 至 18 个")
  if (totalDurationMs < 30_000 || totalDurationMs > 90_000) errors.push("总时长需为 30 至 90 秒")
  shots.forEach((shot, index) => {
    if (!shot.visualDescription?.trim()) errors.push(`分镜 ${index + 1} 缺少画面描述`)
    if (shot.durationMs < 1_000 || shot.durationMs > 15_000) errors.push(`分镜 ${index + 1} 时长需为 1 至 15 秒`)
  })
  return { valid: errors.length === 0, errors, shotCount: shots.length, totalDurationMs }
}

function parseOutput(output: ComicShotAttempt["outputJson"]): Record<string, unknown> | null {
  if (!output) return null
  if (typeof output === "object") return output
  try {
    const parsed = JSON.parse(output) as unknown
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

function findTextDeep(source: unknown, keys: string[], depth = 0): string | null {
  if (!source || typeof source !== "object" || depth > 5) return null
  const record = source as Record<string, unknown>
  const direct = firstText(record, keys)
  if (direct) return direct
  for (const value of Object.values(record)) {
    const found = findTextDeep(value, keys, depth + 1)
    if (found) return found
  }
  return null
}

function firstText(source: Record<string, unknown> | null, keys: string[]): string | null {
  for (const key of keys) {
    const value = source?.[key]
    if (typeof value === "string" && value.trim()) return value.trim()
  }
  return null
}

export function comicAttemptMedia(attempt?: ComicShotAttempt | null): {
  videoUrl: string | null
  imageUrl: string | null
  prompt: string | null
} {
  const output = parseOutput(attempt?.outputJson) ?? attempt?.result ?? null
  return {
    videoUrl: findTextDeep(output, ["videoUrl", "finalVideoUrl", "url"]),
    imageUrl: findTextDeep(output, ["imageUrl", "coverUrl"]),
    prompt: findTextDeep(output, ["prompt", "videoPrompt"]),
  }
}

export function formatComicDuration(durationMs: number): string {
  const seconds = Math.max(0, Math.round(durationMs / 100) / 10)
  return `${seconds.toLocaleString("zh-CN", { maximumFractionDigits: 1 })} 秒`
}
