import type { ToolField, ToolFrontendStyle } from "./types"

export type CapabilityType =
  | "imageGeneration"
  | "fileReading"
  | "webSearch"
  | "codeExecution"
  | "voiceInput"

export interface Capability {
  type: CapabilityType
  config: Record<string, unknown>
}

export interface AITool {
  id: string
  name: string
  iconUrl: string
  description?: string
  enabled: boolean
  order: number
  primaryColor?: string
  welcomeMessage?: string
  mediaDisplayMode?: "icon" | "effect" | "comparison"
  modelIconUrl?: string
  comparisonOriginalUrl?: string
  comparisonEffectUrl?: string
  frontendStyle?: ToolFrontendStyle | null
  heroTitle?: string | null
  heroSubtitle?: string | null
  demoThumbnails?: string[] | null
  useCases?: string[] | null
  steps?: string[] | null
  recommendedToolCodes?: string[] | null
  beforeVideoUrl?: string | null
  afterVideoUrl?: string | null
  modelConfigName?: string | null
  modelName?: string | null
  capabilities: Capability[]
  inputModality?: string | null
  outputModality?: string | null
  toolKind?: "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other" | string | null
  fields?: ToolField[]
  estimatedCreditCost?: number
  /** 工作流类工具：按每次实际调用模型成本 ×1.2 动态计费 */
  variableCreditPricing?: boolean | null
}

export interface ChatSession {
  id: string
  title: string
  toolId: string
  createdAt: number
  updatedAt: number
}

export interface ChatAttachment {
  fileId: string
  name: string
  size: number
  type: string
}

export interface ChatMessage {
  id: string
  role: "user" | "assistant"
  content: string
  timestamp: number
  attachments?: ChatAttachment[]
  params?: Record<string, unknown>
}

export interface ChatRequest {
  toolId: string
  sessionId: string
  content: string
  attachments?: string[]
  params?: Record<string, unknown>
}

export interface ChatResponse {
  userMessage: ChatMessage
  assistantMessage: ChatMessage
}

export interface FileUploadResult {
  fileId: string
  url: string
}

export const CAPABILITY_LABELS: Record<CapabilityType, string> = {
  imageGeneration: "图片生成",
  fileReading: "文件阅读",
  webSearch: "联网搜索",
  codeExecution: "代码执行",
  voiceInput: "语音输入",
}
