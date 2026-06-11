export const workflowToolCodes = new Set([
  "digital_human_agent",
  "ai_comic_drama_agent",
  "enterprise_diagnosis_agent",
  "social_media_comment_insights_agent",
  "banana_ppt_generator",
])

/** 单模型调用类工具：应出现在「大模型管理」，不应归入工作流画布。 */
export const modelOnlyToolCodes = new Set([
  "suno_music",
  "suno",
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
  if (modelOnlyToolCodes.has(code)) return false
  if (workflowToolCodes.has(code)) return true

  const type = normalize(tool.toolType).toUpperCase()
  const handler = normalize(tool.executionHandler).toUpperCase()

  if (type === "AGENT" || handler === "DIGITAL_HUMAN") return true

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
    "digital_human",
    "数字人",
    "漫剧",
    "comic",
    "drama",
  ])
}
