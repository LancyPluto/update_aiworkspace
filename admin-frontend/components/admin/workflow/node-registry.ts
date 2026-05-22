import {
  Bot,
  FileInput,
  FileOutput,
  FileText,
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
]

export const NODE_CATEGORIES = [...new Set(NODE_TYPES.map((node) => node.category))]
export const NODE_TYPE_MAP = new Map(NODE_TYPES.map((node) => [node.type, node]))
