import type { TaskDetail, ToolSummary } from "@/api/types"

export type CreateToolMode = "video" | "image" | "digitalHuman" | "audio"

export interface CreateTimelineMaterial {
  key: string
  url: string
  kind: "image" | "video" | "audio" | "file"
}

export interface CreateTimelineItem {
  task: TaskDetail
  brandName: string
  mode: CreateToolMode
  typeLabel: string
  modelLabel: string
  startedAtLabel: string
  promptText: string
  materials: CreateTimelineMaterial[]
  sortTime: number
}

export const CREATE_TOOL_MODES: Array<{ key: CreateToolMode; label: string }> = [
  { key: "video", label: "视频" },
  { key: "image", label: "图片" },
  { key: "digitalHuman", label: "数字人" },
  { key: "audio", label: "音频" },
]

const PROMPT_KEYS = [
  "prompt",
  "text",
  "description",
  "content",
  "script",
  "topic",
  "videoTopic",
  "storyTheme",
  "plotOutline",
  "visualRequirements",
]

export function buildCreateTimelineItems(tasks: TaskDetail[], tools: ToolSummary[]): CreateTimelineItem[] {
  const toolMap = new Map(tools.map((tool) => [tool.toolCode, tool]))
  return tasks
    .map((task) => buildCreateTimelineItem(task, toolMap.get(task.toolCode)))
    .sort((a, b) => a.sortTime - b.sortTime || a.task.taskId - b.task.taskId)
}

export function buildCreateTimelineItem(task: TaskDetail, tool?: ToolSummary): CreateTimelineItem {
  const mode = classifyCreateToolMode(task, tool)
  const startTime = task.startedAt || task.createdAt
  const sortTime = Date.parse(startTime || "") || task.taskId
  return {
    task,
    brandName: "科创点AI",
    mode,
    typeLabel: createToolModeLabel(mode),
    modelLabel: resolveTimelineModelLabel(task, tool),
    startedAtLabel: formatTimelineTime(startTime),
    promptText: extractTimelinePrompt(task.params || {}, tool),
    materials: extractTimelineMaterials(task.params || {}),
    sortTime,
  }
}

export function classifyCreateToolMode(
  task: Partial<TaskDetail>,
  tool?: Partial<ToolSummary>,
): CreateToolMode {
  const text = normalizeSearchText([
    task.toolCode,
    task.toolName,
    task.toolType,
    task.inputModality,
    task.outputModality,
    tool?.toolCode,
    tool?.toolName,
    tool?.toolType,
    tool?.inputModality,
    tool?.outputModality,
    tool?.categoryName,
    tool?.toolKind,
  ])
  if (/(digital[_\s-]?human|avatar|presenter|数字人|口播|主播)/i.test(text)) return "digitalHuman"
  if (/(audio|voice|speech|tts|music|音频|语音|声音|配音|音乐)/i.test(text)) return "audio"
  if (/(image|img|picture|photo|poster|图片|图像|生图|海报|封面)/i.test(text)) return "image"
  return "video"
}

export function createToolModeLabel(mode: CreateToolMode): string {
  if (mode === "image") return "生图"
  if (mode === "digitalHuman") return "数字人"
  if (mode === "audio") return "音频"
  return "生视频"
}

export function toolMatchesCreateMode(tool: ToolSummary, mode: CreateToolMode): boolean {
  return classifyCreateToolMode(
    {
      toolCode: tool.toolCode,
      toolName: tool.toolName,
      toolType: tool.toolType || undefined,
      inputModality: tool.inputModality || undefined,
      outputModality: tool.outputModality || undefined,
    },
    tool,
  ) === mode
}

export function resolveTimelineModelLabel(task: TaskDetail, tool?: ToolSummary): string {
  const params = task.params || {}
  return (
    compactText(task.modelConfigName) ||
    compactText(task.modelName) ||
    compactText(params.modelLabel) ||
    compactText(tool?.modelDisplayName) ||
    compactText(params.model) ||
    compactText(params.modelName) ||
    compactText(task.toolName) ||
    compactText(task.toolCode) ||
    "默认模型"
  )
}

export function extractTimelinePrompt(params: Record<string, unknown>, tool?: Partial<ToolSummary>): string {
  for (const key of PROMPT_KEYS) {
    const value = params[key]
    if (typeof value === "string" && value.trim() && !isUrlLike(value)) return value.trim()
  }
  const strings = flattenParamStrings(params)
    .map((item) => item.value.trim())
    .filter((value) => value.length > 0 && !isUrlLike(value))
    .sort((a, b) => b.length - a.length)
  const toolDescription = compactText(tool?.description)
  if (strings[0] || toolDescription) return strings[0] || toolDescription
  return strings[0] || "未记录提示词"
}

export function extractTimelineMaterials(params: Record<string, unknown>): CreateTimelineMaterial[] {
  const seen = new Set<string>()
  const materials: CreateTimelineMaterial[] = []
  for (const item of flattenParamStrings(params)) {
    if (!isUrlLike(item.value) || seen.has(item.value)) continue
    seen.add(item.value)
    materials.push({
      key: item.key,
      url: item.value,
      kind: inferMaterialKind(item.key, item.value),
    })
  }
  return materials
}

export function formatTimelineTime(value?: string | null): string {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return "-"
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  const hour = String(date.getHours()).padStart(2, "0")
  const minute = String(date.getMinutes()).padStart(2, "0")
  return `${month}-${day} ${hour}:${minute}`
}

function flattenParamStrings(value: unknown, key = ""): Array<{ key: string; value: string }> {
  if (typeof value === "string") return [{ key, value }]
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => flattenParamStrings(item, `${key}[${index}]`))
  }
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).flatMap(([childKey, child]) =>
      flattenParamStrings(child, key ? `${key}.${childKey}` : childKey),
    )
  }
  return []
}

function inferMaterialKind(key: string, url: string): CreateTimelineMaterial["kind"] {
  const text = `${key} ${url}`.toLowerCase()
  if (/data:image|image|img|photo|poster|cover|avatar|frame|\.png|\.jpe?g|\.webp|\.gif/.test(text)) return "image"
  if (/data:video|video|clip|movie|\.mp4|\.webm|\.mov/.test(text)) return "video"
  if (/data:audio|audio|voice|speech|music|\.mp3|\.wav|\.m4a|\.aac/.test(text)) return "audio"
  return "file"
}

function isUrlLike(value: string): boolean {
  return /^(https?:\/\/|\/generated\/|\/uploads\/|data:(?:image|audio|video)\/)/i.test(value.trim())
}

function normalizeSearchText(values: Array<unknown>): string {
  return values
    .map((value) => String(value ?? "").trim().toLowerCase())
    .filter(Boolean)
    .join(" ")
}

function compactText(value: unknown): string {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : ""
}
