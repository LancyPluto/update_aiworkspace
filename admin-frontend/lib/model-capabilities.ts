import type { AgentModelConfig } from "@/lib/api/types"

export const fallbackProviderCapabilities: Record<string, string[]> = {
  mock: ["TEXT_GENERATION"],
  local_media_mock: ["IMAGE_GENERATION", "VIDEO_GENERATION"],
  openai_compatible: ["TEXT_GENERATION"],
  anthropic_compatible: ["TEXT_GENERATION"],
  minimax: ["TEXT_GENERATION"],
  qwen_compatible: ["TEXT_GENERATION"],
  qwen: ["TEXT_GENERATION", "VISION_INPUT"],
  glm_compatible: ["TEXT_GENERATION"],
  deepseek_compatible: ["TEXT_GENERATION"],
  deepseek: ["TEXT_GENERATION"],
  moonshot_compatible: ["TEXT_GENERATION"],
  siliconflow: ["IMAGE_GENERATION"],
  siliconflow_images: ["IMAGE_GENERATION"],
  volcengine_images: ["IMAGE_GENERATION"],
  minimax_music: ["MUSIC_GENERATION"],
  suno_music: ["MUSIC_GENERATION"],
  minimax_speech: ["TEXT_TO_SPEECH"],
  siliconflow_speech: ["TEXT_TO_SPEECH"],
  siliconflow_asr: ["SPEECH_TO_TEXT"],
  seedance: ["VIDEO_GENERATION"],
  infinitetalk: ["VIDEO_GENERATION"],
  kling_video: ["VIDEO_GENERATION", "IMAGE_GENERATION"],
  bailian_happyhorse: ["VIDEO_GENERATION"],
  ofox_openai_images: ["IMAGE_GENERATION"],
  openai_images_gateway: ["IMAGE_GENERATION"],
  worker_video: ["VIDEO_GENERATION"],
  agnes_chat: ["TEXT_GENERATION"],
  agnes_images: ["IMAGE_GENERATION"],
  agnes_video: ["VIDEO_GENERATION"],
  vidu_video: ["VIDEO_GENERATION", "IMAGE_GENERATION"],
  vidu_audio: ["AUDIO_GENERATION"],
}

const capabilityLabels: Record<string, string> = {
  TEXT_GENERATION: "文本生成",
  IMAGE_GENERATION: "文生图",
  IMAGE_TO_IMAGE: "图生图",
  VIDEO_GENERATION: "视频生成",
  TEXT_TO_SPEECH: "文字转语音",
  SPEECH_TO_TEXT: "语音转文字",
  MUSIC_GENERATION: "音乐生成",
  AUDIO_GENERATION: "音频生成",
  VISION_INPUT: "图片视觉",
  EMBEDDING: "Embedding",
  RERANK: "Rerank",
}

const LEGACY_HIDDEN_CAPABILITIES = new Set(["DIGITAL_HUMAN"])
const DIGITAL_HUMAN_EXECUTION_PROVIDERS = new Set(["seedance", "infinitetalk"])
const EXECUTION_HANDLERS = new Set([
  "TEXT_GENERATION",
  "IMAGE_GENERATION",
  "MUSIC_GENERATION",
  "TEXT_TO_SPEECH",
  "VIDEO_GENERATION",
  "DIGITAL_HUMAN",
])
const KNOWN_MODEL_CAPABILITIES = new Set([
  "TEXT_GENERATION",
  "VISION_INPUT",
  "IMAGE_GENERATION",
  "VIDEO_GENERATION",
  "TEXT_TO_SPEECH",
  "SPEECH_TO_TEXT",
  "MUSIC_GENERATION",
  "AUDIO_GENERATION",
  "EMBEDDING",
  "RERANK",
])

export function normalizeModelCapabilities(capabilities: readonly string[] | null | undefined): string[] {
  const normalized: string[] = []
  const seen = new Set<string>()
  for (const capability of capabilities || []) {
    const value = capability?.trim().toUpperCase()
    if (!value || LEGACY_HIDDEN_CAPABILITIES.has(value) || seen.has(value)) continue
    seen.add(value)
    normalized.push(value)
  }
  return normalized
}

export function normalizeRequiredModelCapabilities(
  capabilities: readonly string[] | null | undefined,
): string[] {
  const normalized: string[] = []
  const seen = new Set<string>()
  for (const capability of capabilities || []) {
    const value = capability?.trim().toUpperCase()
    if (!value || seen.has(value)) continue
    seen.add(value)
    normalized.push(value)
  }
  return normalized
}

export function normalizeLegacyRequiredModelCapabilities(
  capabilities: readonly string[] | null | undefined,
): string[] {
  return normalizeRequiredModelCapabilities(
    normalizeRequiredModelCapabilities(capabilities)
      .map((capability) => capability === "DIGITAL_HUMAN" ? "VIDEO_GENERATION" : capability),
  )
}

export function selectableModelCapabilities(
  providerCapabilities: Record<string, string[]> | null | undefined,
): string[] {
  const configured = normalizeModelCapabilities(Object.values(providerCapabilities || {}).flat())
  return configured.length > 0
    ? configured
    : normalizeModelCapabilities(Object.values(fallbackProviderCapabilities).flat())
}

export function capabilityLabel(capability: string): string {
  const value = capability.toUpperCase()
  return capabilityLabels[value] || value
}

export function resolvedModelCapabilities(
  config: AgentModelConfig,
  providerCapabilities?: Record<string, string[]>,
): string[] {
  const provider = (config.provider || "").trim().toLowerCase()
  const catalog = providerCapabilities ?? fallbackProviderCapabilities
  const supported = normalizeModelCapabilities(catalog[provider])
  const configured = normalizeModelCapabilities(config.capabilities)
  if (configured.length === 0) return supported

  const hasProviderMetadata = providerCapabilities !== undefined
    || Object.prototype.hasOwnProperty.call(fallbackProviderCapabilities, provider)
  if (!hasProviderMetadata) return configured

  const allowed = new Set(supported)
  return configured.filter((capability) => allowed.has(capability))
}

export function modelConfigSupportsCapabilities(
  config: AgentModelConfig,
  capabilities: readonly string[],
  providerCapabilities?: Record<string, string[]>,
): boolean {
  const required = normalizeRequiredModelCapabilities(capabilities)
  if (required.length === 0) return false
  const available = new Set(resolvedModelCapabilities(config, providerCapabilities))
  return required.every((capability) => available.has(capability))
}

export function modelConfigSupportsToolRequirements(
  config: AgentModelConfig,
  capabilities: readonly string[],
  providerCapabilities?: Record<string, string[]>,
  executionHandler?: string | null,
): boolean {
  const handler = executionHandler?.trim().toUpperCase()
  const provider = config.provider?.trim().toLowerCase() || ""
  if (handler === "DIGITAL_HUMAN" && !DIGITAL_HUMAN_EXECUTION_PROVIDERS.has(provider)) {
    return false
  }
  return modelConfigSupportsCapabilities(config, capabilities, providerCapabilities)
}

export function modelConfigSupportsCapability(
  config: AgentModelConfig,
  capability: string,
  providerCapabilities?: Record<string, string[]>,
): boolean {
  return modelConfigSupportsCapabilities(config, [capability], providerCapabilities)
}

export function defaultRequiredModelCapabilitiesForTool(
  tool: { toolType?: string | null; executionHandler?: string | null },
): string[] {
  const handler = tool.executionHandler?.trim().toUpperCase()
  if (handler === "DIGITAL_HUMAN") return ["VIDEO_GENERATION"]
  if (handler) return KNOWN_MODEL_CAPABILITIES.has(handler) ? [handler] : ["TEXT_GENERATION"]

  const toolType = tool.toolType?.trim().toUpperCase()
  if (toolType === "IMAGE_TO_IMAGE") return ["IMAGE_GENERATION"]
  if (toolType === "IMAGE_UNDERSTANDING") return ["TEXT_GENERATION", "VISION_INPUT"]
  if (toolType === "AGENT") return ["TEXT_GENERATION"]
  return toolType && KNOWN_MODEL_CAPABILITIES.has(toolType) ? [toolType] : ["TEXT_GENERATION"]
}

export function defaultExecutionHandlerForToolType(toolType: string | null | undefined): string {
  const normalized = toolType?.trim().toUpperCase() || ""
  return EXECUTION_HANDLERS.has(normalized) ? normalized : "TEXT_GENERATION"
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

/** 工作台类工具对应的 integration 插件 ID；标准任务工具返回 null */
export function resolveIntegrationPluginId(tool: {
  toolCode: string
  configNote?: string | null
}): string | null {
  if (isPptWorkspaceTool(tool)) return "ppt"
  return null
}
