import type { ToolField } from "./types"

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
  mediaDisplayMode?: "icon" | "effect"
  modelIconUrl?: string
  modelConfigName?: string | null
  modelName?: string | null
  capabilities: Capability[]
  inputModality?: string | null
  outputModality?: string | null
  fields?: ToolField[]
  estimatedCreditCost?: number
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
