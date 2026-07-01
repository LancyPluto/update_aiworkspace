import type { TaskDetail, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"

const DEFAULT_RECOMMENDATION_LIMIT = 8

type ToolUsageTask = Pick<TaskDetail, "toolCode">

export function buildToolUsageCounts(tasks: ToolUsageTask[]): Map<string, number> {
  const counts = new Map<string, number>()
  for (const task of tasks) {
    const code = normalizeToolCode(task.toolCode)
    if (!code) continue
    counts.set(code, (counts.get(code) || 0) + 1)
  }
  return counts
}

export function recommendToolsForAsset(
  asset: AssetPreviewItem,
  tools: ToolSummary[],
  options: {
    tasks?: ToolUsageTask[]
    usageCounts?: Map<string, number>
    fallbackTools?: ToolSummary[]
    limit?: number
  } = {},
): AssetPreviewRecommendation[] {
  const usageCounts = options.usageCounts ?? buildToolUsageCounts(options.tasks ?? [])
  const indexedTools = tools.map((tool, index) => ({ tool, index }))
  const matches = indexedTools.filter(({ tool }) => supportsAssetInput(tool, asset))
  const fallbackCodes = new Set((options.fallbackTools ?? []).map((tool) => normalizeToolCode(tool.toolCode)))
  const fallback = indexedTools.filter(({ tool }) => fallbackCodes.has(normalizeToolCode(tool.toolCode)))
  const source = matches.length ? matches : fallback.length ? fallback : indexedTools

  return [...source]
    .sort((a, b) => {
      const used = toolUsageCount(b.tool, usageCounts) - toolUsageCount(a.tool, usageCounts)
      return used || a.index - b.index
    })
    .slice(0, options.limit ?? DEFAULT_RECOMMENDATION_LIMIT)
    .map(({ tool }) => tool)
}

function supportsAssetInput(tool: ToolSummary, asset: AssetPreviewItem): boolean {
  const target = assetInputTarget(asset)
  const input = normalizeModality(tool.inputModality)
  const text = `${tool.toolName} ${tool.description || ""} ${cleanToolDisplayText(tool.configNote)} ${tool.toolCode}`
  return (
    (target && (input.includes(target) || input.includes("MULTIMODAL") || input.includes("FILE"))) ||
    assetKeyword(asset).test(text)
  )
}

function assetInputTarget(asset: AssetPreviewItem): string {
  if (asset.kind === "image") return "IMAGE"
  if (asset.kind === "video") return "VIDEO"
  if (asset.kind === "audio") return "AUDIO"
  return ""
}

function assetKeyword(asset: AssetPreviewItem): RegExp {
  if (asset.kind === "image") return /图|图片|影像|photo|image|img|改图|参考/i
  if (asset.kind === "video") return /视频|短片|video|clip|movie/i
  if (asset.kind === "audio") return /音频|音乐|audio|voice|tts/i
  return /创作|生成|工具|model|tool/i
}

function toolUsageCount(tool: ToolSummary, usageCounts: Map<string, number>): number {
  return usageCounts.get(normalizeToolCode(tool.toolCode)) || 0
}

function normalizeToolCode(value?: string | null): string {
  return (value || "").trim().toLowerCase()
}

function normalizeModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}
