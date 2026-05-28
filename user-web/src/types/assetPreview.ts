import type { ToolSummary } from "@/api/types"

export type AssetPreviewKind = "image" | "video" | "audio" | "text" | "other"

export interface AssetPreviewItem {
  id: string
  kind: AssetPreviewKind
  title: string
  subtitle?: string
  url?: string
  urls?: string[]
  prompt?: string
  rawText?: string
  taskId?: number
  taskNo?: string
  toolName?: string
  toolCode?: string
  createdAt?: string | null
}

export type AssetPreviewRecommendation = ToolSummary
