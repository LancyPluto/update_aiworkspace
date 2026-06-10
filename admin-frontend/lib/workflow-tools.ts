export const workflowToolCodes = new Set([
  "digital_human_agent",
  "ai_comic_drama_agent",
  "enterprise_diagnosis_agent",
  "social_media_comment_insights_agent",
  "banana_ppt_generator",
  "tts_mm",
])

export interface WorkflowToolLike {
  toolCode?: string | null
  toolName?: string | null
  name?: string | null
  toolType?: string | null
  executionHandler?: string | null
  category?: string | null
  categoryName?: string | null
  inputModality?: string | null
  outputModality?: string | null
  configNote?: string | null
}

function normalize(value?: string | null) {
  return (value || "").trim().toLowerCase()
}

function includesAny(value: string, keywords: string[]) {
  return keywords.some((keyword) => value.includes(keyword))
}

export function isWorkflowTool(tool: WorkflowToolLike) {
  const code = normalize(tool.toolCode)
  if (workflowToolCodes.has(code)) return true

  const type = normalize(tool.toolType).toUpperCase()
  const handler = normalize(tool.executionHandler).toUpperCase()
  const input = normalize(tool.inputModality).toUpperCase()
  const output = normalize(tool.outputModality).toUpperCase()

  if (type === "AGENT" || handler === "DIGITAL_HUMAN") return true
  if (type === "TEXT_TO_SPEECH" || type === "SPEECH_TO_TEXT" || type === "MUSIC_GENERATION") return true
  if (handler === "TEXT_TO_SPEECH" || handler === "SPEECH_TO_TEXT" || handler === "MUSIC_GENERATION") return true
  if (input === "AUDIO" || output === "AUDIO") return true

  const text = [
    tool.toolCode,
    tool.toolName,
    tool.name,
    tool.toolType,
    tool.executionHandler,
    tool.category,
    tool.categoryName,
    tool.configNote,
  ]
    .map((value) => normalize(value))
    .filter(Boolean)
    .join(" ")

  return includesAny(text, [
    "agent",
    "智能体",
    "workflow",
    "工作流",
    "ppt",
    "slide",
    "audio",
    "speech",
    "tts",
    "music",
    "音频",
    "语音",
    "音乐",
  ])
}
