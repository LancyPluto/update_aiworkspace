import type { AgentModelConfig } from "@/lib/api/types"

export const fallbackProviderCapabilities: Record<string, string[]> = {
  mock: ["TEXT_GENERATION"],
  openai_compatible: ["TEXT_GENERATION"],
  anthropic_compatible: ["TEXT_GENERATION"],
  minimax: ["TEXT_GENERATION"],
  qwen_compatible: ["TEXT_GENERATION"],
  glm_compatible: ["TEXT_GENERATION"],
  deepseek_compatible: ["TEXT_GENERATION"],
  moonshot_compatible: ["TEXT_GENERATION"],
  siliconflow: ["IMAGE_GENERATION", "DIGITAL_HUMAN"],
  siliconflow_images: ["IMAGE_GENERATION", "DIGITAL_HUMAN"],
  volcengine_images: ["IMAGE_GENERATION"],
  volcengine: ["TEXT_GENERATION", "IMAGE_GENERATION", "VIDEO_GENERATION"],
  seedance: ["VIDEO_GENERATION", "DIGITAL_HUMAN"],
  minimax_speech: ["TEXT_TO_SPEECH"],
  siliconflow_speech: ["TEXT_TO_SPEECH"],
  siliconflow_asr: ["SPEECH_TO_TEXT"],
  worker_video: ["VIDEO_GENERATION"],
}

const capabilityLabels: Record<string, string> = {
  TEXT_GENERATION: "文本生成",
  IMAGE_GENERATION: "文生图",
  IMAGE_TO_IMAGE: "图生图",
  VIDEO_GENERATION: "视频生成",
  TEXT_TO_SPEECH: "文字转语音",
  SPEECH_TO_TEXT: "语音转文字",
  DIGITAL_HUMAN: "数字人",
  EMBEDDING: "Embedding",
  RERANK: "Rerank",
}

export function capabilityLabel(capability: string): string {
  const value = capability.toUpperCase()
  return capabilityLabels[value] || value
}

export function resolvedModelCapabilities(
  config: AgentModelConfig,
  providerCapabilities: Record<string, string[]> = fallbackProviderCapabilities,
): string[] {
  if (config.capabilities && config.capabilities.length > 0) {
    return config.capabilities
      .filter((capability) => capability && capability.trim())
      .map((capability) => capability.trim().toUpperCase())
  }
  const provider = (config.provider || "").trim().toLowerCase()
  return (providerCapabilities[provider] || fallbackProviderCapabilities[provider] || [])
    .filter((capability) => capability && capability.trim())
    .map((capability) => capability.trim().toUpperCase())
}

export function modelConfigSupportsCapability(
  config: AgentModelConfig,
  capability: string,
  providerCapabilities: Record<string, string[]> = fallbackProviderCapabilities,
): boolean {
  const caps = resolvedModelCapabilities(config, providerCapabilities)
  if (caps.length === 0) return false
  const want = capability.toUpperCase()
  return caps.some((c) => (c || "").toUpperCase() === want)
}

export function isPptWorkspaceTool(tool: { toolCode: string; configNote?: string | null }): boolean {
  if (tool.toolCode === "banana_ppt_generator") return true
  const note = tool.configNote || ""
  return (
    note.includes("ppt-workflow:") ||
    note.includes("PPT_WORKSPACE") ||
    note.includes('"integrationMode":"ppt_workspace"')
  )
}
