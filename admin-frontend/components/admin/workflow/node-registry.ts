import {
  Bot,
  CircleDot,
  FileInput,
  FileOutput,
  FileText,
  GitBranch,
  Image,
  Mic,
  Play,
  UserRoundPen,
  Video,
  Wrench,
  type LucideIcon as LucideIconType,
} from "lucide-react"
import type { AgentModelConfig } from "@/lib/api/types"

export interface NodeTypeDefinition {
  type: string
  category: string
  displayName: string
  description: string
  icon: LucideIconType
  iconName: string
  color: string
  defaultWidth: number
  defaultHeight: number
  defaultData: Partial<{
    title: string
    detail: string
    kind: string
    iconName: string
    color: string
    config: AgentModelConfig | null
    parameters: Record<string, unknown>
  }>
  inputSlots: Array<{ name: string; type: string; label: string }>
  outputSlots: Array<{ name: string; type: string; label: string }>
  parameters?: Array<{
    name: string
    label: string
    type: "string" | "number" | "boolean" | "select" | "model_selector"
    default?: unknown
    options?: Array<{ label: string; value: string }>
  }>
}

export const NODE_TYPES: NodeTypeDefinition[] = [
  {
    type: "field_input",
    category: "输入",
    displayName: "用户输入参数",
    description: "配置用户侧表单字段、填写方式、选项和必填规则。",
    icon: FileInput,
    iconName: "file-input",
    color: "#475569",
    defaultWidth: 320,
    defaultHeight: 220,
    defaultData: { kind: "input", iconName: "file-input" },
    inputSlots: [],
    outputSlots: [{ name: "params", type: "json", label: "用户填写参数" }],
  },
  {
    type: "prompt_template",
    category: "编排",
    displayName: "提示词组装",
    description: "把用户参数整理成后续模型或工具需要的提示词。",
    icon: FileText,
    iconName: "file-text",
    color: "#b45309",
    defaultWidth: 280,
    defaultHeight: 150,
    defaultData: { kind: "prompt", iconName: "file-text" },
    inputSlots: [{ name: "params", type: "json", label: "用户填写参数" }],
    outputSlots: [{ name: "prompt", type: "text", label: "组装后提示词" }],
    parameters: [
      { name: "template", label: "提示词模板", type: "string", default: "{{用户输入参数}}" },
      { name: "progressStep", label: "进度文案", type: "string", default: "整理用户输入" },
    ],
  },
  {
    type: "llm_model",
    category: "模型",
    displayName: "大模型统一节点",
    description: "选择系统配置里的模型，执行文本、图片、语音或视频类生成能力。",
    icon: Bot,
    iconName: "bot",
    color: "#2563eb",
    defaultWidth: 300,
    defaultHeight: 170,
    defaultData: { kind: "model", iconName: "bot" },
    inputSlots: [{ name: "prompt", type: "text", label: "模型输入" }],
    outputSlots: [{ name: "result", type: "any", label: "模型输出" }],
    parameters: [
      { name: "modelConfigId", label: "使用模型", type: "model_selector", default: null },
      { name: "progressStep", label: "进度文案", type: "string", default: "调用大模型" },
    ],
  },
  {
    type: "backend_tool",
    category: "工具",
    displayName: "后端工具统一节点",
    description: "调用 worker 或后端内置工具，例如字幕、音视频合成、报告导出。",
    icon: Wrench,
    iconName: "wrench",
    color: "#ea580c",
    defaultWidth: 300,
    defaultHeight: 160,
    defaultData: { kind: "tool", iconName: "wrench" },
    inputSlots: [{ name: "input", type: "any", label: "工具输入" }],
    outputSlots: [{ name: "output", type: "any", label: "工具输出" }],
    parameters: [
      { name: "toolName", label: "工具名称", type: "string", default: "后端工具" },
      { name: "progressStep", label: "进度文案", type: "string", default: "执行后端工具" },
    ],
  },
  {
    type: "final_output",
    category: "输出",
    displayName: "输出节点",
    description: "定义用户侧最终看到的文本、图片、视频或文件结果。",
    icon: FileOutput,
    iconName: "file-output",
    color: "#dc2626",
    defaultWidth: 280,
    defaultHeight: 140,
    defaultData: { kind: "output", iconName: "file-output" },
    inputSlots: [{ name: "result", type: "any", label: "最终结果" }],
    outputSlots: [],
    parameters: [
      {
        name: "displayMode",
        label: "展示方式",
        type: "select",
        default: "mixed",
        options: [
          { label: "文本", value: "text" },
          { label: "图片", value: "image" },
          { label: "音频", value: "audio" },
          { label: "视频", value: "video" },
          { label: "文件", value: "file" },
          { label: "混合结果", value: "mixed" },
        ],
      },
    ],
  },
  {
    type: "start",
    category: "编排",
    displayName: "开始",
    description: "工作流入口，传递会话上下文。",
    icon: Play,
    iconName: "play",
    color: "#10b981",
    defaultWidth: 180,
    defaultHeight: 100,
    defaultData: { kind: "start", iconName: "play" },
    inputSlots: [],
    outputSlots: [{ name: "context", type: "json", label: "会话上下文" }],
  },
  {
    type: "user_input",
    category: "输入",
    displayName: "用户补充输入",
    description: "分步收集脚本、分镜、场景图、BGM 等阶段意见。",
    icon: UserRoundPen,
    iconName: "user-round-pen",
    color: "#0ea5e9",
    defaultWidth: 260,
    defaultHeight: 150,
    defaultData: { kind: "input", iconName: "user-round-pen" },
    inputSlots: [{ name: "upstream", type: "any", label: "上一步结果" }],
    outputSlots: [{ name: "feedback", type: "text", label: "用户意见" }],
    parameters: [
      { name: "fieldKey", label: "字段键", type: "string", default: "scriptFeedback" },
      { name: "stageLabel", label: "阶段文案", type: "string", default: "脚本意见" },
    ],
  },
  {
    type: "llm_text",
    category: "模型",
    displayName: "剧本规划器",
    description: "根据初始表单生成剧本与分镜 JSON。",
    icon: Bot,
    iconName: "bot",
    color: "#3b82f6",
    defaultWidth: 300,
    defaultHeight: 170,
    defaultData: { kind: "model", iconName: "bot" },
    inputSlots: [{ name: "form", type: "json", label: "初始表单" }],
    outputSlots: [{ name: "script", type: "json", label: "剧本分镜" }],
    parameters: [
      { name: "modelConfigId", label: "使用模型", type: "model_selector", default: null },
      { name: "role", label: "节点角色", type: "string", default: "comic_script_planner" },
      { name: "progressStep", label: "进度文案", type: "string", default: "生成剧本与分镜" },
    ],
  },
  {
    type: "image_model",
    category: "模型",
    displayName: "关键帧生图",
    description: "根据剧本分镜生成电影感关键帧。",
    icon: Image,
    iconName: "image",
    color: "#ec4899",
    defaultWidth: 300,
    defaultHeight: 170,
    defaultData: { kind: "model", iconName: "image" },
    inputSlots: [
      { name: "script", type: "json", label: "剧本分镜" },
      { name: "form", type: "json", label: "表单参数" },
    ],
    outputSlots: [{ name: "keyframe", type: "image", label: "关键帧" }],
    parameters: [
      { name: "modelConfigId", label: "使用模型", type: "model_selector", default: null },
      { name: "progressStep", label: "进度文案", type: "string", default: "生成电影感关键帧" },
    ],
  },
  {
    type: "tts_model",
    category: "模型",
    displayName: "角色配音",
    description: "根据对白生成 TTS 音频。",
    icon: Mic,
    iconName: "mic",
    color: "#8b5cf6",
    defaultWidth: 280,
    defaultHeight: 160,
    defaultData: { kind: "model", iconName: "mic" },
    inputSlots: [{ name: "script", type: "json", label: "剧本分镜" }],
    outputSlots: [{ name: "audio", type: "audio", label: "配音音频" }],
    parameters: [
      { name: "modelConfigId", label: "使用模型", type: "model_selector", default: null },
      { name: "progressStep", label: "进度文案", type: "string", default: "生成角色配音" },
    ],
  },
  {
    type: "video_model",
    category: "模型",
    displayName: "图生视频",
    description: "根据关键帧与参数生成视频片段。",
    icon: Video,
    iconName: "video",
    color: "#f97316",
    defaultWidth: 300,
    defaultHeight: 170,
    defaultData: { kind: "model", iconName: "video" },
    inputSlots: [
      { name: "keyframe", type: "image", label: "关键帧" },
      { name: "script", type: "json", label: "剧本分镜" },
    ],
    outputSlots: [{ name: "clip", type: "video", label: "视频片段" }],
    parameters: [
      { name: "modelConfigId", label: "使用模型", type: "model_selector", default: null },
      { name: "progressStep", label: "进度文案", type: "string", default: "图生视频" },
    ],
  },
  {
    type: "subtitle",
    category: "工具",
    displayName: "字幕合成",
    description: "合并配音、视频片段并烧录字幕。",
    icon: Wrench,
    iconName: "wrench",
    color: "#f97316",
    defaultWidth: 280,
    defaultHeight: 150,
    defaultData: { kind: "tool", iconName: "wrench" },
    inputSlots: [
      { name: "clip", type: "video", label: "视频片段" },
      { name: "audio", type: "audio", label: "配音音频" },
      { name: "script", type: "json", label: "剧本分镜" },
    ],
    outputSlots: [{ name: "finalVideo", type: "video", label: "成片" }],
    parameters: [{ name: "progressStep", label: "进度文案", type: "string", default: "字幕与音视频合成" }],
  },
  {
    type: "video_output",
    category: "输出",
    displayName: "成片输出",
    description: "用户侧播放最终漫剧视频。",
    icon: FileOutput,
    iconName: "file-output",
    color: "#ef4444",
    defaultWidth: 260,
    defaultHeight: 130,
    defaultData: { kind: "output", iconName: "file-output" },
    inputSlots: [{ name: "finalVideo", type: "video", label: "成片" }],
    outputSlots: [],
    parameters: [{ name: "displayMode", label: "展示方式", type: "select", default: "video" }],
  },
  {
    type: "user_confirm",
    category: "编排",
    displayName: "用户确认",
    description: "等待用户确认上一步产物后继续。",
    icon: CircleDot,
    iconName: "circle-dot",
    color: "#14b8a6",
    defaultWidth: 220,
    defaultHeight: 120,
    defaultData: { kind: "confirm", iconName: "circle-dot" },
    inputSlots: [{ name: "artifact", type: "any", label: "待确认结果" }],
    outputSlots: [{ name: "confirmed", type: "any", label: "确认结果" }],
    parameters: [{ name: "sourceNodeId", label: "来源节点", type: "string", default: "" }],
  },
  {
    type: "condition",
    category: "编排",
    displayName: "条件判断",
    description: "根据用户是否填写修订意见决定分支。",
    icon: GitBranch,
    iconName: "git-branch",
    color: "#a855f7",
    defaultWidth: 240,
    defaultHeight: 130,
    defaultData: { kind: "condition", iconName: "git-branch" },
    inputSlots: [
      { name: "upstream", type: "any", label: "上游结果" },
      { name: "revision", type: "text", label: "修订意见" },
    ],
    outputSlots: [{ name: "next", type: "any", label: "继续执行" }],
    parameters: [{ name: "revisionField", label: "修订字段", type: "string", default: "scriptFeedback" }],
  },
]

export const NODE_CATEGORIES = [...new Set(NODE_TYPES.map((node) => node.category))]
export const WORKFLOW_EXECUTION_NODE_TYPES = [
  "start",
  "field_input",
  "user_input",
  "llm_text",
  "image_model",
  "tts_model",
  "video_model",
  "subtitle",
  "video_output",
  "user_confirm",
  "condition",
]
export const NODE_TYPE_MAP = new Map(NODE_TYPES.map((node) => [node.type, node]))
