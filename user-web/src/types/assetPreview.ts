import type { ToolSummary } from "@/api/types"

export type AssetPreviewKind = "image" | "video" | "audio" | "text" | "other"

export interface AssetPreviewItem {
  id: string
  kind: AssetPreviewKind
  source?: "private" | "community"
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
  communityPostId?: number
  promptVisible?: boolean
  modality?: string
  topic?: string | null
  tags?: string[]
  featured?: boolean
  pinned?: boolean
  stats?: {
    views?: number
    likes?: number
    favorites?: number
    sameStyle?: number
  }
  createdAt?: string | null
}

export type AssetPreviewRecommendation = ToolSummary
