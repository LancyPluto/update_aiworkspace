"use client"

import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from "react"
import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
  useEdgesState,
  useNodesState,
} from "@xyflow/react"
import "@xyflow/react/dist/style.css"
import { CheckCircle2, History, LayoutGrid, Redo2, Rocket, Save, Undo2 } from "lucide-react"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type {
  AgentModelConfig,
  ToolSummary,
  WorkflowGroup,
  WorkflowNode,
  WorkflowNodeData,
  WorkflowResponse,
  WorkflowVersionItem,
} from "@/lib/api/types"
import { fetchToolFields, updateToolFields } from "@/lib/api/tools"
import {
  fetchWorkflow,
  fetchWorkflowVersions,
  publishWorkflow,
  restoreWorkflowVersion,
  saveWorkflow,
  validateWorkflow,
} from "@/lib/api/workflows"
import {
  editableFromToolField,
  toFieldPayload,
  type EditableField,
} from "@/lib/tool-fields"
import { NodePalette } from "./node-palette"
import { NODE_TYPE_MAP, type NodeTypeDefinition } from "./node-registry"
import { WorkflowNodeComponent, type WorkflowSlot } from "./workflow-node"
import { GroupNodeComponent } from "./group-node"
import { InspectorPanel } from "./inspector-panel"
import { buildNodeActions, useContextMenu } from "./context-menu"
import { useUndoRedo } from "./undo-redo"

type NodeDataWithSlots = WorkflowNodeData & {
  nodeDefType?: string
  inputSlots?: WorkflowSlot[]
  outputSlots?: WorkflowSlot[]
  selectedModelLabel?: string
  selectedModelProvider?: string
  selectedModelName?: string
}
type WFNode = Node<NodeDataWithSlots>
type WFEdge = Edge<{
  sourceParam?: WorkflowSlot
  targetParam?: WorkflowSlot
  mapping?: { from: string; to: string; modality: string }
}>
type WFNodeChange = NodeChange<WFNode>

const reactFlowNodeTypes = {
  workflowNode: WorkflowNodeComponent,
  groupNode: GroupNodeComponent,
}

interface WorkflowCanvasProps {
  toolId: number
  toolName: string
  tool?: ToolSummary | null
  modelConfigs: AgentModelConfig[]
  variant?: "embedded" | "workspace"
}

function randomId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function getNodeDef(data: unknown): NodeTypeDefinition | undefined {
  const record = data as Record<string, unknown> | undefined
  if (!record) return undefined
  if (record.nodeDefType) return NODE_TYPE_MAP.get(record.nodeDefType as string)
  if (record.kind) return NODE_TYPE_MAP.get(record.kind as string)
  return undefined
}

function getNodeSlots(node: WFNode | undefined, side: "inputSlots" | "outputSlots"): WorkflowSlot[] {
  const def = getNodeDef(node?.data)
  const fallback = side === "inputSlots" ? def?.inputSlots : def?.outputSlots
  return (node?.data?.[side] || fallback || []) as WorkflowSlot[]
}

function cloneSlots(slots: Array<{ name: string; label: string; type: string }>): WorkflowSlot[] {
  return slots.map((slot) => ({ name: slot.name, label: slot.label, type: slot.type }))
}

function capabilityForApiRole(role: string): string | undefined {
  const map: Record<string, string> = {
    text_generation: "TEXT_GENERATION",
    text_to_speech: "TEXT_TO_SPEECH",
    image_generation: "IMAGE_GENERATION",
    video_generation: "VIDEO_GENERATION",
    search: "TEXT_GENERATION",
  }
  return map[role]
}

function findModelConfigId(modelConfigs: AgentModelConfig[], role: string): number | null {
  const capability = capabilityForApiRole(role)
  const enabled = modelConfigs.filter((config) => config.enabled !== false)
  if (capability) {
    const matched = enabled.find((config) =>
      (config.capabilities || []).some((cap) => cap.toUpperCase() === capability),
    )
    if (matched) return matched.id
  }
  return enabled.find((config) => config.isDefault)?.id || enabled[0]?.id || null
}

function findComicDramaModelConfigId(modelConfigs: AgentModelConfig[], role: string): number | null {
  const capability = capabilityForApiRole(role)
  const providerPriority: Record<string, string[]> = {
    text_generation: ["agnes_chat"],
    image_generation: ["agnes_images"],
    text_to_speech: ["minimax_speech", "siliconflow_speech"],
    video_generation: ["agnes_video", "seedance"],
  }
  const candidates = modelConfigs
    .filter((config) => config.enabled !== false)
    .filter((config) =>
      !capability || (config.capabilities || []).some((cap) => cap.toUpperCase() === capability),
    )
  for (const provider of providerPriority[role] || []) {
    const matched = candidates
      .filter((config) => config.provider.toLowerCase() === provider)
      .sort((left, right) => right.id - left.id)[0]
    if (matched) return matched.id
  }
  return findModelConfigId(modelConfigs, role)
}

function modelLabel(model: AgentModelConfig): string {
  return model.displayName || model.configCode || model.modelName
}

type EditableFieldDraft = Omit<
  EditableField,
  "executionRequired" | "userRequired" | "defaultValue" | "agentFillStrategy" | "riskLevel" | "isCore" | "uiMeta"
> &
  Partial<
    Pick<
      EditableField,
      "executionRequired" | "userRequired" | "defaultValue" | "agentFillStrategy" | "riskLevel" | "isCore" | "uiMeta"
    >
  >

function withFieldDefaults(field: EditableFieldDraft): EditableField {
  return {
    executionRequired: field.required,
    userRequired: field.required,
    defaultValue: "",
    agentFillStrategy: field.required ? "ask_user" : "default",
    riskLevel: "LOW",
    isCore: false,
    uiMeta: {},
    ...field,
  }
}

const DIGITAL_HUMAN_FIELD_DRAFT: EditableField[] = ([
  {
    fieldKey: "videoTopic",
    fieldName: "视频主题",
    fieldType: "text",
    placeholder: "例如：新品护肤套装 30 秒种草口播",
    required: true,
    sortOrder: 1,
    options: [],
  },
  {
    fieldKey: "script",
    fieldName: "口播脚本",
    fieldType: "textarea",
    placeholder: "写清楚数字人要说的内容，建议 60 到 180 字",
    required: true,
    sortOrder: 2,
    options: [],
  },
  {
    fieldKey: "avatarStyle",
    fieldName: "数字人形象",
    fieldType: "radio",
    placeholder: "选择数字人视觉风格",
    required: true,
    sortOrder: 3,
    options: [
      { label: "职业主播", value: "职业主播" },
      { label: "科技感主持人", value: "科技感主持人" },
      { label: "亲和力导购", value: "亲和力导购" },
      { label: "知识博主", value: "知识博主" },
    ],
  },
  {
    fieldKey: "presenterVoice",
    fieldName: "讲述人性别/音色",
    fieldType: "radio",
    placeholder: "选择与数字人形象一致的声音；不确定时选自动",
    required: false,
    sortOrder: 4,
    options: [
      { label: "自动匹配", value: "auto" },
      { label: "女声", value: "female" },
      { label: "男声", value: "male" },
    ],
  },
  {
    fieldKey: "scene",
    fieldName: "视频场景",
    fieldType: "radio",
    placeholder: "选择数字人所在场景",
    required: true,
    sortOrder: 5,
    options: [
      { label: "直播间", value: "直播间" },
      { label: "产品展示台", value: "产品展示台" },
      { label: "办公室", value: "办公室" },
      { label: "纯色演播室", value: "纯色演播室" },
    ],
  },
  {
    fieldKey: "aspectRatio",
    fieldName: "画面比例",
    fieldType: "radio",
    placeholder: "选择视频比例",
    required: true,
    sortOrder: 6,
    options: [
      { label: "16:9 横屏", value: "16:9" },
      { label: "9:16 竖屏", value: "9:16" },
      { label: "1:1 方形", value: "1:1" },
    ],
  },
  {
    fieldKey: "duration",
    fieldName: "视频时长要求",
    fieldType: "select",
    placeholder: "选择期望成片时长。当前模型会先生成短片段，较长时长需要后续多段生成与拼接。",
    required: true,
    sortOrder: 7,
    options: [
      { label: "5 秒", value: "5" },
      { label: "10 秒", value: "10" },
      { label: "15 秒", value: "15" },
      { label: "30 秒", value: "30" },
      { label: "60 秒", value: "60" },
    ],
  },
  {
    fieldKey: "resolution",
    fieldName: "视频清晰度",
    fieldType: "radio",
    placeholder: "选择生成视频清晰度；480p 成本更低、生成更快",
    required: true,
    sortOrder: 8,
    options: [
      { label: "480p", value: "480p" },
      { label: "720p 高清", value: "720p" },
    ],
  },
  {
    fieldKey: "brandName",
    fieldName: "品牌/产品",
    fieldType: "text",
    placeholder: "例如：澄光实验室补水精华",
    required: false,
    sortOrder: 9,
    options: [],
  },
  {
    fieldKey: "referenceImageUrl",
    fieldName: "参考形象图 URL",
    fieldType: "image",
    placeholder: "可选：填写数字人参考形象图 URL，填写后会走图生视频模型",
    required: false,
    sortOrder: 10,
    options: [],
  },
  {
    fieldKey: "visualRequirements",
    fieldName: "画面要求",
    fieldType: "textarea",
    placeholder: "例如：明亮干净、人物半身出镜、带产品特写，不要夸张特效",
    required: false,
    sortOrder: 11,
    options: [],
  },
  {
    fieldKey: "negativePrompt",
    fieldName: "负面提示词",
    fieldType: "textarea",
    placeholder: "例如：不要畸形手指、不要过度美颜、不要文字水印",
    required: false,
    sortOrder: 12,
    options: [],
  },
] satisfies EditableFieldDraft[]).map(withFieldDefaults)

const COMIC_DRAMA_FIELD_DRAFT: EditableField[] = ([
  {
    fieldKey: "sourceMode",
    fieldName: "剧本来源",
    fieldType: "select",
    placeholder: "选择 AI 创作或导入完整剧本",
    required: true,
    defaultValue: "AI_CREATE",
    sortOrder: 1,
    options: [
      { label: "AI 创作", value: "AI_CREATE" },
      { label: "粘贴或导入剧本", value: "IMPORT" },
    ],
  },
  {
    fieldKey: "storyTheme",
    fieldName: "漫剧主题",
    fieldType: "text",
    placeholder: "例如：穿越后我靠 AI 开店逆袭",
    required: false,
    sortOrder: 2,
    options: [],
  },
  {
    fieldKey: "scriptText",
    fieldName: "完整剧本（可选）",
    fieldType: "textarea",
    placeholder: "可直接粘贴完整剧本；选择 AI 创作时可以留空",
    required: false,
    sortOrder: 3,
    options: [],
  },
  {
    fieldKey: "scriptFile",
    fieldName: "剧本文件（可选）",
    fieldType: "file",
    placeholder: "上传 TXT、Markdown 或 DOCX 文件",
    required: false,
    sortOrder: 4,
    options: [],
  },
  {
    fieldKey: "genre",
    fieldName: "题材类型",
    fieldType: "select",
    placeholder: "选择漫剧题材",
    required: false,
    sortOrder: 5,
    options: [
      { label: "都市逆袭", value: "都市逆袭" },
      { label: "甜宠恋爱", value: "甜宠恋爱" },
      { label: "悬疑反转", value: "悬疑反转" },
      { label: "科幻脑洞", value: "科幻脑洞" },
    ],
  },
  {
    fieldKey: "plotOutline",
    fieldName: "剧情梗概（可选）",
    fieldType: "textarea",
    placeholder: "留空则由大模型自动生成剧本与分镜",
    required: false,
    sortOrder: 6,
    options: [],
  },
  {
    fieldKey: "episodeDuration",
    fieldName: "本集目标时长",
    fieldType: "select",
    placeholder: "选择 30–90 秒",
    required: false,
    defaultValue: "60",
    sortOrder: 7,
    options: [
      { label: "30 秒", value: "30" },
      { label: "60 秒", value: "60" },
      { label: "90 秒", value: "90" },
    ],
  },
  {
    fieldKey: "visualStyle",
    fieldName: "画风风格",
    fieldType: "select",
    placeholder: "选择画面风格",
    required: false,
    sortOrder: 8,
    options: [
      { label: "电影感写实", value: "电影感写实" },
      { label: "国漫厚涂", value: "国漫厚涂" },
      { label: "日漫赛璐璐", value: "日漫赛璐璐" },
      { label: "Q 版轻喜剧", value: "Q 版轻喜剧" },
    ],
  },
  {
    fieldKey: "aspectRatio",
    fieldName: "画面比例",
    fieldType: "select",
    placeholder: "选择发布画幅",
    required: false,
    sortOrder: 9,
    options: [
      { label: "9:16 竖屏", value: "9:16 竖屏" },
      { label: "16:9 横屏", value: "16:9 横屏" },
      { label: "1:1 方形", value: "1:1 方形" },
    ],
  },
  {
    fieldKey: "resolution",
    fieldName: "视频清晰度",
    fieldType: "select",
    placeholder: "480p 更快更省算力",
    required: false,
    sortOrder: 10,
    options: [
      { label: "480p", value: "480p" },
      { label: "720p", value: "720p" },
    ],
  },
  {
    fieldKey: "referenceMaterial",
    fieldName: "参考素材（可选）",
    fieldType: "textarea",
    placeholder: "参考作品、角色设定、禁用元素等",
    required: false,
    sortOrder: 11,
    options: [],
  },
] satisfies EditableFieldDraft[]).map(withFieldDefaults)

function cloneFields(fields: EditableField[]): EditableField[] {
  return fields.map((field) => ({
    ...field,
    options: field.options.map((option) => ({ ...option })),
  }))
}

function modalityFromField(field: EditableField): string {
  if (field.fieldType === "image") return "image"
  if (field.fieldType === "file") return "file"
  if (field.fieldType === "checkbox" || field.fieldType === "slider") return "json"
  return "text"
}

function slotsFromFields(fields: EditableField[]): WorkflowSlot[] {
  return fields.map((field) => ({
    name: field.fieldKey,
    label: field.fieldName,
    type: modalityFromField(field),
  }))
}

function isDigitalHumanTool(tool?: ToolSummary | null): boolean {
  const marker = `${tool?.executionHandler || ""} ${tool?.toolType || ""} ${tool?.toolCode || ""}`.toUpperCase()
  return marker.includes("DIGITAL_HUMAN") || marker.includes("DIGITAL-HUMAN")
}

function isComicDramaTool(tool?: ToolSummary | null): boolean {
  const marker = `${tool?.executionHandler || ""} ${tool?.toolType || ""} ${tool?.toolCode || ""}`.toUpperCase()
  return marker.includes("COMIC") || marker.includes("DRAMA") || tool?.toolCode === "ai_comic_drama_agent"
}

function isWorkflowAgentTool(tool?: ToolSummary | null): boolean {
  if (isComicDramaTool(tool) || isDigitalHumanTool(tool)) return true
  const handler = String(tool?.executionHandler || "").toUpperCase()
  return handler.includes("WORKFLOW")
}

function hasDigitalHumanFields(fields: EditableField[]): boolean {
  const keys = new Set(fields.map((field) => field.fieldKey))
  return ["videoTopic", "script", "avatarStyle", "scene", "aspectRatio", "duration", "resolution"].every((key) =>
    keys.has(key),
  )
}

function createNodeFromDef(
  def: NodeTypeDefinition,
  position: { x: number; y: number },
  overrides?: Partial<NodeDataWithSlots>,
): WFNode {
  return {
    id: randomId(),
    type: "workflowNode",
    position,
    data: {
      title: def.displayName,
      subtitle: def.description,
      detail: def.description,
      kind: def.type,
      iconName: def.iconName,
      color: def.color,
      config: null,
      nodeDefType: def.type,
      parameters: Object.fromEntries((def.parameters || []).map((param) => [param.name, param.default])),
      inputSlots: cloneSlots(def.inputSlots),
      outputSlots: cloneSlots(def.outputSlots),
      ...overrides,
    },
    width: def.defaultWidth,
    height: def.defaultHeight,
  }
}

function firstSlotName(slots: WorkflowSlot[], fallback: string): string {
  return slots[0]?.name || fallback
}

function createWorkflowEdge(
  source: WFNode,
  target: WFNode,
  handles?: { source?: string; target?: string },
): WFEdge {
  const sourceSlots = getNodeSlots(source, "outputSlots")
  const targetSlots = getNodeSlots(target, "inputSlots")
  const sourceSlot = sourceSlots.find((slot) => slot.name === handles?.source) || sourceSlots[0]
  const targetSlot = targetSlots.find((slot) => slot.name === handles?.target) || targetSlots[0]
  const sourceName = sourceSlot?.name || firstSlotName(sourceSlots, "output")
  const targetName = targetSlot?.name || firstSlotName(targetSlots, "input")

  return {
    id: `e-${source.id}-out-${sourceName}-${target.id}-in-${targetName}-${randomId()}`,
    source: source.id,
    target: target.id,
    sourceHandle: `out-${sourceName}`,
    targetHandle: `in-${targetName}`,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, color: "#64748b" },
    style: { stroke: "#64748b", strokeWidth: 2.2 },
    data: sourceSlot
      ? {
          sourceParam: sourceSlot,
          targetParam: targetSlot || { ...sourceSlot },
          mapping: {
            from: sourceSlot.name,
            to: targetSlot?.name || targetName,
            modality: sourceSlot.type,
          },
        }
      : undefined,
  }
}

/** Repair UTF-8 mojibake when JDBC/API mis-decodes Chinese labels (e.g. ä¼šè¯ → 会话). */
function repairUtf8Mojibake(text: string): string {
  if (!text || !/[äåèæÃ]/.test(text)) return text
  try {
    const bytes = Uint8Array.from(text, (char) => char.charCodeAt(0) & 0xff)
    const repaired = new TextDecoder("utf-8").decode(bytes)
    if (/[\u4e00-\u9fff]/.test(repaired) && !repaired.includes("\uFFFD")) {
      return repaired
    }
  } catch {
    // ignore
  }
  return text
}

function repairWorkflowValue<T>(value: T): T {
  if (typeof value === "string") {
    return repairUtf8Mojibake(value) as T
  }
  if (Array.isArray(value)) {
    return value.map((item) => repairWorkflowValue(item)) as T
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, repairWorkflowValue(item)]),
    ) as T
  }
  return value
}

function ensureNodeSlots(node: WFNode): WFNode {
  const def = getNodeDef(node.data)
  return {
    ...node,
    data: {
      ...node.data,
      inputSlots: node.data.inputSlots || cloneSlots(def?.inputSlots || []),
      outputSlots: node.data.outputSlots || cloneSlots(def?.outputSlots || []),
    },
  }
}

function normalizeWorkflowEdges(nodes: WFNode[], edges: WFEdge[]): WFEdge[] {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]))

  return edges.map((edge) => {
    const sourceNode = nodeMap.get(edge.source)
    const targetNode = nodeMap.get(edge.target)
    const sourceSlots = getNodeSlots(sourceNode, "outputSlots")
    const targetSlots = getNodeSlots(targetNode, "inputSlots")
    const sourceHandles = new Set(sourceSlots.map((slot) => `out-${slot.name}`))
    const targetHandles = new Set(targetSlots.map((slot) => `in-${slot.name}`))
    const sourceName = edge.sourceHandle && sourceHandles.has(edge.sourceHandle)
      ? edge.sourceHandle.replace(/^out-/, "")
      : firstSlotName(sourceSlots, "output")
    const sourceSlot = sourceSlots.find((slot) => slot.name === sourceName)
    const targetName = edge.targetHandle && targetHandles.has(edge.targetHandle)
      ? edge.targetHandle.replace(/^in-/, "")
      : firstSlotName(targetSlots, "input")

    const resolvedTargetSlot = targetSlots.find((slot) => slot.name === targetName) || edge.data?.targetParam

    return {
      ...edge,
      id: edge.id || `e-${edge.source}-out-${sourceName}-${edge.target}-in-${targetName}-${randomId()}`,
      type: edge.type || "smoothstep",
      sourceHandle: `out-${sourceName}`,
      targetHandle: `in-${targetName}`,
      markerEnd: edge.markerEnd || { type: MarkerType.ArrowClosed, color: "#64748b" },
      style: edge.style || { stroke: "#64748b", strokeWidth: 2.2 },
      data: {
        ...(edge.data || {}),
        sourceParam: sourceSlot || edge.data?.sourceParam,
        targetParam: resolvedTargetSlot,
        mapping: sourceSlot
          ? { from: sourceSlot.name, to: targetName, modality: sourceSlot.type }
          : edge.data?.mapping,
      },
    }
  })
}

function dedupeEdgeIds(edges: WFEdge[]): WFEdge[] {
  const seen = new Set<string>()
  return edges.map((edge) => {
    if (!seen.has(edge.id)) {
      seen.add(edge.id)
      return edge
    }
    const nextId = `${edge.id}-${randomId()}`
    seen.add(nextId)
    return { ...edge, id: nextId }
  })
}

function apiToReactFlow(workflow: WorkflowResponse): {
  nodes: WFNode[]
  edges: WFEdge[]
  groups: WorkflowGroup[]
} {
  try {
    return {
      nodes: repairWorkflowValue(JSON.parse(workflow.nodesJson || "[]") as WFNode[]).map(ensureNodeSlots),
      edges: JSON.parse(workflow.edgesJson || "[]"),
      groups: workflow.groupsJson ? JSON.parse(workflow.groupsJson) : [],
    }
  } catch {
    return { nodes: [], edges: [], groups: [] }
  }
}

function createWorkflowNodes(types: string[], tool?: ToolSummary | null): WFNode[] {
  return types
    .map((type, index) => {
      const def = NODE_TYPE_MAP.get(type)
      if (!def) return null
      return createNodeFromDef(
        def,
        { x: 80 + index * 300, y: index % 2 === 0 ? 120 : 260 },
        { detail: index === 0 ? `当前工具：${tool?.toolName || "新工作流"}` : def.description },
      )
    })
    .filter(Boolean) as WFNode[]
}

function connectByType(
  nodes: WFNode[],
  sourceType: string,
  targetType: string,
  handles?: { source?: string; target?: string },
): WFEdge | null {
  const source = nodes.find((node) => getNodeDef(node.data)?.type === sourceType)
  const target = nodes.find((node) => getNodeDef(node.data)?.type === targetType)
  if (!source || !target) return null
  return createWorkflowEdge(source, target, handles)
}

function connectNodes(
  source: WFNode,
  target: WFNode,
  handles?: { source?: string; target?: string },
): WFEdge {
  return createWorkflowEdge(source, target, handles)
}

function modelNode(
  id: string,
  position: { x: number; y: number },
  options: {
    title: string
    detail: string
    progressStep: string
    inputSlots: WorkflowSlot[]
    outputSlots: WorkflowSlot[]
    modelConfigId?: number | null
  },
): WFNode {
  const def = NODE_TYPE_MAP.get("llm_model")
  if (!def) throw new Error("llm_model node type is missing")
  return {
    ...createNodeFromDef(def, position, {
      title: options.title,
      detail: options.detail,
      kind: "model",
      nodeDefType: "llm_model",
      inputSlots: options.inputSlots,
      outputSlots: options.outputSlots,
      parameters: {
        modelConfigId: options.modelConfigId ?? null,
        progressStep: options.progressStep,
      },
    }),
    id,
  }
}

function backendToolNode(
  id: string,
  position: { x: number; y: number },
  options: {
    title: string
    detail: string
    toolName: string
    progressStep: string
    inputSlots: WorkflowSlot[]
    outputSlots: WorkflowSlot[]
  },
): WFNode {
  const def = NODE_TYPE_MAP.get("backend_tool")
  if (!def) throw new Error("backend_tool node type is missing")
  return {
    ...createNodeFromDef(def, position, {
      title: options.title,
      detail: options.detail,
      kind: "tool",
      nodeDefType: "backend_tool",
      inputSlots: options.inputSlots,
      outputSlots: options.outputSlots,
      parameters: {
        toolName: options.toolName,
        progressStep: options.progressStep,
      },
    }),
    id,
  }
}

const apiNode = modelNode as unknown as (
  id: string,
  position: { x: number; y: number },
  options: {
    title: string
    subtitle?: string
    detail: string
    apiRole?: string
    progressStep: string
    inputSlots: WorkflowSlot[]
    outputSlots: WorkflowSlot[]
    modelConfigId?: number | null
    requestTemplate?: string
    responsePath?: string
  },
) => WFNode

function fixedNode(
  id: string,
  type: string,
  position: { x: number; y: number },
  overrides?: Partial<NodeDataWithSlots>,
): WFNode {
  const def = NODE_TYPE_MAP.get(type)
  if (!def) throw new Error(`${type} node type is missing`)
  return { ...createNodeFromDef(def, position, overrides), id }
}

function compactEdges(edges: Array<WFEdge | null>): WFEdge[] {
  return edges.filter(Boolean) as WFEdge[]
}

function buildSimplifiedDefaultWorkflow(
  tool?: ToolSummary | null,
  modelConfigs: AgentModelConfig[] = [],
): { nodes: WFNode[]; edges: WFEdge[] } {
  if (isDigitalHumanTool(tool)) {
    const fieldSlots = slotsFromFields(DIGITAL_HUMAN_FIELD_DRAFT)
    const input = fixedNode("input", "field_input", { x: 80, y: 230 }, {
      title: "用户输入参数",
      detail: "定义用户侧数字人视频表单字段、填写方式、选项和必填规则。",
      outputSlots: fieldSlots,
    })
    const prompt = fixedNode("prompt", "prompt_template", { x: 460, y: 230 }, {
      title: "提示词组装",
      detail: "把主题、脚本、形象、场景、画面要求整理成模型可用的生成提示词。",
      inputSlots: [{ name: "params", type: "json", label: "用户填写参数" }],
      outputSlots: [{ name: "prompt", type: "text", label: "数字人生成提示词" }],
    })
    const speech = modelNode("model-voice", { x: 850, y: 40 }, {
      title: "语音生成模型",
      detail: "根据口播脚本和讲述人音色生成口播音频。",
      progressStep: "生成口播语音",
      modelConfigId: findModelConfigId(modelConfigs, "text_to_speech"),
      inputSlots: [
        { name: "script", type: "text", label: "口播脚本" },
        { name: "presenterVoice", type: "text", label: "讲述人音色" },
        { name: "avatarStyle", type: "text", label: "数字人形象" },
      ],
      outputSlots: [{ name: "audio", type: "audio", label: "口播音频" }],
    })
    const image = modelNode("model-image", { x: 850, y: 280 }, {
      title: "形象与背景生成模型",
      detail: "根据提示词和参考图生成数字人形象图与场景背景。",
      progressStep: "生成数字人形象和背景",
      modelConfigId: findModelConfigId(modelConfigs, "image_generation"),
      inputSlots: [
        { name: "prompt", type: "text", label: "数字人生成提示词" },
        { name: "referenceImageUrl", type: "image", label: "参考形象图" },
      ],
      outputSlots: [
        { name: "avatarImage", type: "image", label: "数字人形象图" },
        { name: "backgroundImage", type: "image", label: "场景背景图" },
      ],
    })
    const video = modelNode("model-video", { x: 1260, y: 170 }, {
      title: "图生视频模型",
      detail: "根据形象图、背景图、音频、比例、时长和清晰度生成视频片段。",
      progressStep: "生成数字人视频片段",
      modelConfigId: findModelConfigId(modelConfigs, "video_generation"),
      inputSlots: [
        { name: "avatarImage", type: "image", label: "数字人形象图" },
        { name: "backgroundImage", type: "image", label: "场景背景图" },
        { name: "audio", type: "audio", label: "口播音频" },
        { name: "prompt", type: "text", label: "数字人生成提示词" },
        { name: "duration", type: "text", label: "视频时长" },
        { name: "resolution", type: "text", label: "视频清晰度" },
        { name: "aspectRatio", type: "text", label: "画面比例" },
      ],
      outputSlots: [{ name: "rawVideo", type: "video", label: "视频片段" }],
    })
    const postprocess = backendToolNode("tool-postprocess", { x: 1680, y: 190 }, {
      title: "字幕与音视频合成工具",
      detail: "用后端工具合并音频、校准字幕并输出用户可播放的视频。",
      toolName: "FFmpeg 字幕与音视频合成",
      progressStep: "合成最终视频",
      inputSlots: [
        { name: "rawVideo", type: "video", label: "视频片段" },
        { name: "audio", type: "audio", label: "口播音频" },
        { name: "script", type: "text", label: "口播脚本" },
      ],
      outputSlots: [
        { name: "finalVideo", type: "video", label: "最终视频" },
        { name: "subtitle", type: "file", label: "字幕文件" },
      ],
    })
    const outputNode = fixedNode("output", "final_output", { x: 2080, y: 220 }, {
      title: "输出节点",
      detail: "用户侧渲染视频播放器、字幕文件和下载按钮。",
      inputSlots: [
        { name: "finalVideo", type: "video", label: "最终视频" },
        { name: "subtitle", type: "file", label: "字幕文件" },
      ],
      parameters: { displayMode: "video" },
    })
    const nodes = [input, prompt, speech, image, video, postprocess, outputNode]
    return {
      nodes,
      edges: compactEdges([
        connectNodes(input, prompt, { source: "params", target: "params" }),
        connectNodes(input, speech, { source: "script", target: "script" }),
        connectNodes(input, speech, { source: "presenterVoice", target: "presenterVoice" }),
        connectNodes(input, speech, { source: "avatarStyle", target: "avatarStyle" }),
        connectNodes(prompt, image, { source: "prompt", target: "prompt" }),
        connectNodes(input, image, { source: "referenceImageUrl", target: "referenceImageUrl" }),
        connectNodes(image, video, { source: "avatarImage", target: "avatarImage" }),
        connectNodes(image, video, { source: "backgroundImage", target: "backgroundImage" }),
        connectNodes(speech, video, { source: "audio", target: "audio" }),
        connectNodes(prompt, video, { source: "prompt", target: "prompt" }),
        connectNodes(input, video, { source: "duration", target: "duration" }),
        connectNodes(input, video, { source: "resolution", target: "resolution" }),
        connectNodes(input, video, { source: "aspectRatio", target: "aspectRatio" }),
        connectNodes(video, postprocess, { source: "rawVideo", target: "rawVideo" }),
        connectNodes(speech, postprocess, { source: "audio", target: "audio" }),
        connectNodes(input, postprocess, { source: "script", target: "script" }),
        connectNodes(postprocess, outputNode, { source: "finalVideo", target: "finalVideo" }),
        connectNodes(postprocess, outputNode, { source: "subtitle", target: "subtitle" }),
      ]),
    }
  }

  const input = fixedNode("input", "field_input", { x: 80, y: 220 }, {
    title: "用户输入参数",
    detail: `当前工具：${tool?.toolName || "新工作流"}`,
  })
  const prompt = fixedNode("prompt", "prompt_template", { x: 450, y: 220 })
  const model = modelNode("model-main", { x: 820, y: 220 }, {
    title: "大模型统一节点",
    detail: "调用系统配置中的模型完成主要生成或分析任务。",
    progressStep: "调用大模型",
    modelConfigId: findModelConfigId(modelConfigs, "text_generation"),
    inputSlots: [{ name: "prompt", type: "text", label: "组装后提示词" }],
    outputSlots: [{ name: "result", type: "any", label: "模型输出" }],
  })
  const toolNode = backendToolNode("tool-main", { x: 1190, y: 220 }, {
    title: "后端工具统一节点",
    detail: "执行格式整理、文件生成、数据处理等非模型能力。",
    toolName: "结果整理工具",
    progressStep: "整理输出结果",
    inputSlots: [{ name: "result", type: "any", label: "模型输出" }],
    outputSlots: [{ name: "result", type: "any", label: "整理后结果" }],
  })
  const outputNode = fixedNode("output", "final_output", { x: 1560, y: 220 }, {
    title: "输出节点",
    detail: "定义用户侧最终看到的结果。",
  })
  const nodes = [input, prompt, model, toolNode, outputNode]
  return {
    nodes,
    edges: compactEdges([
      connectNodes(input, prompt, { source: "params", target: "params" }),
      connectNodes(prompt, model, { source: "prompt", target: "prompt" }),
      connectNodes(model, toolNode, { source: "result", target: "result" }),
      connectNodes(toolNode, outputNode, { source: "result", target: "result" }),
    ]),
  }
}

const COMIC_WORKFLOW_TEMPLATE_VERSION = "comic-project-v2"

function userConfirmNode(
  id: string,
  position: { x: number; y: number },
  options: { title: string; detail: string; sourceNodeId: string; stageKey: string },
): WFNode {
  return fixedNode(id, "user_confirm", position, {
    title: options.title,
    detail: options.detail,
    inputSlots: [{ name: "artifact", type: "any", label: "待确认产物" }],
    outputSlots: [{ name: "confirmed", type: "json", label: "确认结果" }],
    parameters: {
      sourceNodeId: options.sourceNodeId,
      stageKey: options.stageKey,
    },
  })
}

function buildComicDramaDefaultWorkflow(
  tool?: ToolSummary | null,
  modelConfigs: AgentModelConfig[] = [],
): { nodes: WFNode[]; edges: WFEdge[] } {
  const textModelId = findComicDramaModelConfigId(modelConfigs, "text_generation")
  const imageModelId = findComicDramaModelConfigId(modelConfigs, "image_generation")
  const ttsModelId = findComicDramaModelConfigId(modelConfigs, "text_to_speech")
  const videoModelId = findComicDramaModelConfigId(modelConfigs, "video_generation")

  const start = fixedNode("start", "start", { x: 40, y: 300 }, {
    title: "开始项目",
    detail: `当前工具：${tool?.toolName || "AI 漫剧项目"}`,
    outputSlots: [{ name: "context", type: "json", label: "会话上下文" }],
    parameters: {
      templateVersion: COMIC_WORKFLOW_TEMPLATE_VERSION,
      handlerKey: "comic.project",
    },
  })
  const input = fixedNode("project-input", "field_input", { x: 340, y: 300 }, {
    title: "创建或导入剧本",
    detail: "支持 AI 创作、直接粘贴、TXT、Markdown 和 DOCX 导入。",
    inputSlots: [{ name: "context", type: "json", label: "会话上下文" }],
    outputSlots: [{ name: "params", type: "json", label: "用户填写参数" }],
  })
  const scriptPlanner = fixedNode("script-normalize", "script_planner", { x: 680, y: 300 }, {
    title: "整理完整剧本",
    detail: "保留导入原文，将人物、场景、对白和段落整理成统一剧本版本。",
    inputSlots: [{ name: "form", type: "json", label: "项目与剧本输入" }],
    outputSlots: [{ name: "script", type: "json", label: "结构化剧本" }],
    parameters: {
      modelConfigId: textModelId,
      handlerKey: "comic.script",
      role: "comic_script",
      progressStep: "整理完整剧本",
    },
  })
  const storyboard = fixedNode("storyboard", "storyboard_generator", { x: 1020, y: 300 }, {
    title: "AI 分镜拆解",
    detail: "生成镜号、时码、景别、机位、运镜、情绪、画面、台词、声音和生成提示词。",
    inputSlots: [{ name: "script", type: "json", label: "结构化剧本" }],
    outputSlots: [{ name: "storyboard", type: "json", label: "可编辑分镜表" }],
    parameters: {
      modelConfigId: textModelId,
      handlerKey: "comic.storyboard",
      role: "comic_storyboard",
      progressStep: "拆分结构化分镜",
    },
  })
  const storyboardConfirm = userConfirmNode("confirm-storyboard", { x: 1360, y: 300 }, {
    title: "锁定分镜",
    detail: "用户完成增删、重排和编辑后锁定当前分镜版本。",
    sourceNodeId: "storyboard",
    stageKey: "STORYBOARD_LOCK",
  })
  const characters = fixedNode("character-assets", "character_design", { x: 1700, y: 80 }, {
    title: "角色三视图",
    detail: "生成并版本化角色正面、侧面、背面参考图。",
    inputSlots: [
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "approval", type: "json", label: "分镜锁定结果" },
    ],
    outputSlots: [{ name: "characters", type: "json", label: "角色参考资产" }],
    parameters: {
      modelConfigId: imageModelId,
      handlerKey: "comic.character_reference",
      progressStep: "生成角色三视图",
    },
  })
  const scenes = fixedNode("scene-assets", "scene_design", { x: 1700, y: 300 }, {
    title: "场景锚点图",
    detail: "生成并版本化主要场景的空间、光线和风格参考图。",
    inputSlots: [
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "approval", type: "json", label: "分镜锁定结果" },
    ],
    outputSlots: [{ name: "scenes", type: "json", label: "场景参考资产" }],
    parameters: {
      modelConfigId: imageModelId,
      handlerKey: "comic.scene_reference",
      progressStep: "生成场景锚点图",
    },
  })
  const audio = fixedNode("shot-audio", "tts_model", { x: 1700, y: 520 }, {
    title: "台词与旁白",
    detail: "按角色和台词行生成独立配音，保留字幕时间信息。",
    inputSlots: [
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "approval", type: "json", label: "分镜锁定结果" },
    ],
    outputSlots: [{ name: "audio", type: "json", label: "配音与字幕素材" }],
    parameters: {
      modelConfigId: ttsModelId,
      handlerKey: "comic.shot_tts",
      progressStep: "生成台词与旁白",
    },
  })
  const assetConfirm = userConfirmNode("confirm-assets", { x: 2040, y: 300 }, {
    title: "确认参考素材与费用",
    detail: "选定角色和场景版本，展示本批镜头的预计费用后再继续。",
    sourceNodeId: "scene-assets",
    stageKey: "ASSET_AND_COST_APPROVAL",
  })
  const shotBatch = fixedNode("shot-batch", "scene_loop", { x: 2380, y: 300 }, {
    title: "镜头批次计划",
    detail: "将已锁定分镜展开为独立任务，按系统和供应商上限受控并行。",
    inputSlots: [
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "assets", type: "json", label: "素材与费用确认" },
      { name: "form", type: "json", label: "项目参数" },
    ],
    outputSlots: [{ name: "batch", type: "json", label: "镜头任务批次" }],
    parameters: {
      handlerKey: "comic.shot_batch",
      durationField: "episodeDuration",
      secondsPerScene: 5,
      maxScenes: 18,
      imageConcurrency: 4,
      videoConcurrency: 2,
    },
  })
  const keyframe = fixedNode("shot-keyframes", "keyframe_generator", { x: 2720, y: 180 }, {
    title: "并行生成关键帧",
    detail: "每个镜头独立生成，实际携带已选角色和场景参考图。",
    inputSlots: [
      { name: "batch", type: "json", label: "镜头任务批次" },
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "characters", type: "json", label: "角色参考资产" },
      { name: "scenes", type: "json", label: "场景参考资产" },
    ],
    outputSlots: [{ name: "keyframes", type: "json", label: "镜头关键帧版本" }],
    parameters: {
      modelConfigId: imageModelId,
      handlerKey: "comic.shot_keyframe",
      progressStep: "并行生成镜头关键帧",
    },
  })
  const clipVideo = fixedNode("shot-videos", "image_to_video", { x: 3060, y: 180 }, {
    title: "批量生成镜头视频",
    detail: "按镜头独立调用视频模型，失败只重试对应镜头。",
    inputSlots: [
      { name: "keyframes", type: "json", label: "镜头关键帧版本" },
      { name: "storyboard", type: "json", label: "已锁定分镜" },
      { name: "characters", type: "json", label: "角色参考资产" },
      { name: "scenes", type: "json", label: "场景参考资产" },
    ],
    outputSlots: [{ name: "clips", type: "json", label: "镜头视频版本" }],
    parameters: {
      modelConfigId: videoModelId,
      handlerKey: "comic.shot_video",
      progressStep: "批量生成镜头视频",
    },
  })
  const compose = fixedNode("compose", "subtitle", { x: 3400, y: 300 }, {
    title: "组装成片",
    detail: "按锁定顺序拼接选定片段，混合配音、旁白、BGM、音效并生成字幕。",
    inputSlots: [
      { name: "clips", type: "json", label: "已选镜头视频" },
      { name: "audio", type: "json", label: "配音与声音素材" },
      { name: "storyboard", type: "json", label: "已锁定分镜" },
    ],
    outputSlots: [
      { name: "finalVideo", type: "video", label: "最终成片" },
      { name: "subtitle", type: "file", label: "SRT 字幕" },
    ],
    parameters: {
      handlerKey: "comic.compose",
      progressStep: "组装音视频与字幕",
    },
  })
  const outputNode = fixedNode("output", "video_output", { x: 3740, y: 300 }, {
    title: "交付成片",
    detail: "输出可播放 MP4、SRT 字幕和项目素材版本。",
    inputSlots: [
      { name: "finalVideo", type: "video", label: "最终成片" },
      { name: "subtitle", type: "file", label: "SRT 字幕" },
    ],
    parameters: {
      displayMode: "video",
      templateVersion: COMIC_WORKFLOW_TEMPLATE_VERSION,
    },
  })

  const nodes = [
    start,
    input,
    scriptPlanner,
    storyboard,
    storyboardConfirm,
    characters,
    scenes,
    audio,
    assetConfirm,
    shotBatch,
    keyframe,
    clipVideo,
    compose,
    outputNode,
  ]

  return {
    nodes,
    edges: compactEdges([
      connectNodes(start, input, { source: "context", target: "context" }),
      connectNodes(input, scriptPlanner, { source: "params", target: "form" }),
      connectNodes(scriptPlanner, storyboard, { source: "script", target: "script" }),
      connectNodes(storyboard, storyboardConfirm, { source: "storyboard", target: "artifact" }),
      connectNodes(storyboard, characters, { source: "storyboard", target: "storyboard" }),
      connectNodes(storyboardConfirm, characters, { source: "confirmed", target: "approval" }),
      connectNodes(storyboard, scenes, { source: "storyboard", target: "storyboard" }),
      connectNodes(storyboardConfirm, scenes, { source: "confirmed", target: "approval" }),
      connectNodes(storyboard, audio, { source: "storyboard", target: "storyboard" }),
      connectNodes(storyboardConfirm, audio, { source: "confirmed", target: "approval" }),
      connectNodes(characters, assetConfirm, { source: "characters", target: "artifact" }),
      connectNodes(scenes, assetConfirm, { source: "scenes", target: "artifact" }),
      connectNodes(storyboard, shotBatch, { source: "storyboard", target: "storyboard" }),
      connectNodes(assetConfirm, shotBatch, { source: "confirmed", target: "assets" }),
      connectNodes(input, shotBatch, { source: "params", target: "form" }),
      connectNodes(shotBatch, keyframe, { source: "batch", target: "batch" }),
      connectNodes(storyboard, keyframe, { source: "storyboard", target: "storyboard" }),
      connectNodes(characters, keyframe, { source: "characters", target: "characters" }),
      connectNodes(scenes, keyframe, { source: "scenes", target: "scenes" }),
      connectNodes(keyframe, clipVideo, { source: "keyframes", target: "keyframes" }),
      connectNodes(storyboard, clipVideo, { source: "storyboard", target: "storyboard" }),
      connectNodes(characters, clipVideo, { source: "characters", target: "characters" }),
      connectNodes(scenes, clipVideo, { source: "scenes", target: "scenes" }),
      connectNodes(clipVideo, compose, { source: "clips", target: "clips" }),
      connectNodes(audio, compose, { source: "audio", target: "audio" }),
      connectNodes(storyboard, compose, { source: "storyboard", target: "storyboard" }),
      connectNodes(compose, outputNode, { source: "finalVideo", target: "finalVideo" }),
      connectNodes(compose, outputNode, { source: "subtitle", target: "subtitle" }),
    ]),
  }
}

function isLegacySimplifiedComicWorkflow(nodes: WFNode[]): boolean {
  const types = new Set(nodes.map((node) => String(getNodeDef(node.data)?.type || node.data.nodeDefType || "")))
  return types.has("prompt_template") && types.has("llm_model") && nodes.length <= 6
}

function shouldReloadComicWorkflow(tool: ToolSummary | null | undefined, nodes: WFNode[]): boolean {
  if (!isComicDramaTool(tool)) return false
  if (nodes.length === 0) return true
  if (isLegacySimplifiedComicWorkflow(nodes)) return true
  const requiredNodeIds = [
    "project-input",
    "script-normalize",
    "storyboard",
    "confirm-storyboard",
    "character-assets",
    "scene-assets",
    "confirm-assets",
    "shot-batch",
    "shot-keyframes",
    "shot-videos",
    "compose",
    "output",
  ]
  const hasVersionMarker = nodes.some(
    (node) => node.data.parameters?.templateVersion === COMIC_WORKFLOW_TEMPLATE_VERSION,
  )
  return !hasVersionMarker || !requiredNodeIds.every((id) => nodes.some((node) => node.id === id))
}

function normalizeComicFieldInputSlots(node: WFNode): WFNode {
  if (getNodeDef(node.data)?.type !== "field_input") return node
  const inputSlots = node.data.inputSlots?.length
    ? node.data.inputSlots
    : [{ name: "context", type: "json", label: "会话上下文" }]
  return {
    ...node,
    data: {
      ...node.data,
      inputSlots,
      outputSlots: [{ name: "params", type: "json", label: "用户填写参数" }],
    },
  }
}

function hasGarbledWorkflowText(nodes: WFNode[]): boolean {
  const text = nodes
    .map((node) => {
      const data = node.data || {}
      const slotText = [...(data.inputSlots || []), ...(data.outputSlots || [])]
        .map((slot) => slot.label)
        .join(" ")
      return `${data.title || ""} ${slotText}`
    })
    .join(" ")
  if (/[äåèæÃ]|u610f/i.test(text)) return true
  return nodes.some((node) => {
    const title = String(node.data?.title || "")
    return title && title !== "Start" && !/[\u4e00-\u9fff]/.test(title)
  })
}

/** Keep saved positions/model ids, restore Chinese labels from in-repo template. */
function mergeComicWorkflowWithTemplate(
  savedNodes: WFNode[],
  savedEdges: WFEdge[],
  tool?: ToolSummary | null,
  modelConfigs: AgentModelConfig[] = [],
): { nodes: WFNode[]; edges: WFEdge[] } {
  const template = buildComicDramaDefaultWorkflow(tool, modelConfigs)
  const templateById = new Map(template.nodes.map((node) => [node.id, node]))
  // 以管理员保存的节点为准（标题/槽位/新增节点都保留），模板只用于补齐缺失的元数据，避免每次加载覆盖管理员编辑
  const nodes = savedNodes.map((saved) => {
    const templateNode = templateById.get(saved.id)
    if (!templateNode) return normalizeComicFieldInputSlots(saved)
    return normalizeComicFieldInputSlots({
      ...saved,
      data: {
        ...templateNode.data,
        ...saved.data,
        inputSlots: saved.data.inputSlots?.length ? saved.data.inputSlots : templateNode.data.inputSlots,
        outputSlots: saved.data.outputSlots?.length ? saved.data.outputSlots : templateNode.data.outputSlots,
        parameters: {
          ...templateNode.data.parameters,
          ...saved.data.parameters,
        },
      },
    })
  })
  const edges =
    savedEdges.length > 0
      ? dedupeEdgeIds(normalizeWorkflowEdges(nodes, savedEdges))
      : template.edges
  return { nodes, edges }
}

function buildDefaultWorkflow(tool?: ToolSummary | null, modelConfigs: AgentModelConfig[] = []): { nodes: WFNode[]; edges: WFEdge[] } {
  if (isComicDramaTool(tool)) {
    return buildComicDramaDefaultWorkflow(tool, modelConfigs)
  }
  return buildSimplifiedDefaultWorkflow(tool, modelConfigs)

  const marker = `${tool?.executionHandler || tool?.toolType || tool?.toolCode || ""}`.toUpperCase()
  const output = `${tool?.outputModality || ""}`.toUpperCase()
  const isDigitalHuman = isDigitalHumanTool(tool)
  const isComic = marker.includes("COMIC") || marker.includes("DRAMA")
  const isEnterprise = marker.includes("ENTERPRISE") || marker.includes("DIAGNOS")
  const isSocial = marker.includes("SOCIAL") || marker.includes("CRAWLER") || marker.includes("XHS")

  if (isDigitalHuman) {
    const fieldSlots = slotsFromFields(DIGITAL_HUMAN_FIELD_DRAFT)
    const start = fixedNode("start", "start", { x: 60, y: 260 }, {
      subtitle: `当前工具：${tool?.toolName || "数字人视频生成"}`,
      outputSlots: [{ name: "context", type: "json", label: "会话上下文" }],
    })
    const input = fixedNode("input", "field_input", { x: 350, y: 210 }, {
      title: "用户输入参数",
      subtitle: "用户侧数字人视频表单",
      detail: "这里定义用户侧要填写的表单字段、控件类型、选项和必填规则。",
      inputSlots: [{ name: "context", type: "json", label: "会话上下文" }],
      outputSlots: fieldSlots,
    })
    const speech = apiNode("api-tts", { x: 760, y: 60 }, {
      title: "语音合成 API",
      subtitle: "硅基流动 TTS，按讲述人性别/音色生成口播音频",
      detail: "输入口播脚本和讲述人音色，输出可用于视频驱动和最终合成的音频文件。",
      apiRole: "text_to_speech",
      progressStep: "生成口播语音",
      modelConfigId: findModelConfigId(modelConfigs, "text_to_speech"),
      inputSlots: [
        { name: "script", type: "text", label: "口播脚本" },
        { name: "presenterVoice", type: "text", label: "讲述人音色" },
        { name: "avatarStyle", type: "text", label: "数字人形象" },
      ],
      outputSlots: [{ name: "audio", type: "audio", label: "口播音频" }],
    })
    const image = apiNode("api-image", { x: 760, y: 310 }, {
      title: "形象与背景生图 API",
      subtitle: "硅基流动生图，生成数字人形象和场景参考图",
      detail: "输入数字人形象、视频场景、比例、品牌和画面要求；如有参考图 URL，则优先作为图生视频参考。",
      apiRole: "image_generation",
      progressStep: "生成数字人形象和场景图",
      modelConfigId: findModelConfigId(modelConfigs, "image_generation"),
      inputSlots: [
        { name: "avatarStyle", type: "text", label: "数字人形象" },
        { name: "scene", type: "text", label: "视频场景" },
        { name: "aspectRatio", type: "text", label: "画面比例" },
        { name: "brandName", type: "text", label: "品牌/产品" },
        { name: "referenceImageUrl", type: "image", label: "参考形象图" },
        { name: "visualRequirements", type: "text", label: "画面要求" },
        { name: "negativePrompt", type: "text", label: "负面提示词" },
      ],
      outputSlots: [
        { name: "avatarImage", type: "image", label: "数字人形象图" },
        { name: "backgroundImage", type: "image", label: "场景背景图" },
      ],
    })
    const video = apiNode("api-video", { x: 1190, y: 190 }, {
      title: "图生视频 API",
      subtitle: "Seedance 图生视频，按音频、时长、比例和清晰度生成片段",
      detail: "输入形象图、音频、主题、比例、时长和清晰度，输出未后处理的视频片段。",
      apiRole: "video_generation",
      progressStep: "生成数字人视频片段",
      modelConfigId: findModelConfigId(modelConfigs, "video_generation"),
      inputSlots: [
        { name: "avatarImage", type: "image", label: "数字人形象图" },
        { name: "backgroundImage", type: "image", label: "场景背景图" },
        { name: "audio", type: "audio", label: "口播音频" },
        { name: "videoTopic", type: "text", label: "视频主题" },
        { name: "script", type: "text", label: "口播脚本" },
        { name: "duration", type: "text", label: "视频时长" },
        { name: "resolution", type: "text", label: "视频清晰度" },
        { name: "aspectRatio", type: "text", label: "画面比例" },
        { name: "negativePrompt", type: "text", label: "负面提示词" },
      ],
      outputSlots: [{ name: "rawVideo", type: "video", label: "视频片段" }],
    })
    const postprocess = apiNode("api-postprocess", { x: 1600, y: 210 }, {
      title: "字幕与音视频合成 API",
      subtitle: "FFmpeg 后处理，合并音频并烧录字幕",
      detail: "输入视频片段、音频和口播脚本，生成字幕文件并输出最终可播放视频。",
      apiRole: "postprocess",
      progressStep: "合并音频并烧录字幕",
      modelConfigId: findModelConfigId(modelConfigs, "video_generation"),
      inputSlots: [
        { name: "rawVideo", type: "video", label: "视频片段" },
        { name: "audio", type: "audio", label: "口播音频" },
        { name: "script", type: "text", label: "口播脚本" },
      ],
      outputSlots: [
        { name: "finalVideo", type: "video", label: "最终视频" },
        { name: "subtitle", type: "file", label: "字幕文件" },
      ],
    })
    const outputNode = fixedNode("output", "video_output", { x: 2000, y: 245 }, {
      title: "用户侧输出",
      subtitle: "渲染视频播放器、字幕文件和下载按钮",
      inputSlots: [
        { name: "finalVideo", type: "video", label: "最终视频" },
        { name: "subtitle", type: "file", label: "字幕文件" },
      ],
    })
    const nodes = [start, input, speech, image, video, postprocess, outputNode]
    return {
      nodes,
      edges: compactEdges([
        connectNodes(start, input, { source: "context", target: "context" }),
        connectNodes(input, speech, { source: "script", target: "script" }),
        connectNodes(input, speech, { source: "presenterVoice", target: "presenterVoice" }),
        connectNodes(input, speech, { source: "avatarStyle", target: "avatarStyle" }),
        connectNodes(input, image, { source: "avatarStyle", target: "avatarStyle" }),
        connectNodes(input, image, { source: "scene", target: "scene" }),
        connectNodes(input, image, { source: "aspectRatio", target: "aspectRatio" }),
        connectNodes(input, image, { source: "brandName", target: "brandName" }),
        connectNodes(input, image, { source: "referenceImageUrl", target: "referenceImageUrl" }),
        connectNodes(input, image, { source: "visualRequirements", target: "visualRequirements" }),
        connectNodes(input, image, { source: "negativePrompt", target: "negativePrompt" }),
        connectNodes(image, video, { source: "avatarImage", target: "avatarImage" }),
        connectNodes(image, video, { source: "backgroundImage", target: "backgroundImage" }),
        connectNodes(speech, video, { source: "audio", target: "audio" }),
        connectNodes(input, video, { source: "videoTopic", target: "videoTopic" }),
        connectNodes(input, video, { source: "script", target: "script" }),
        connectNodes(input, video, { source: "duration", target: "duration" }),
        connectNodes(input, video, { source: "resolution", target: "resolution" }),
        connectNodes(input, video, { source: "aspectRatio", target: "aspectRatio" }),
        connectNodes(input, video, { source: "negativePrompt", target: "negativePrompt" }),
        connectNodes(video, postprocess, { source: "rawVideo", target: "rawVideo" }),
        connectNodes(speech, postprocess, { source: "audio", target: "audio" }),
        connectNodes(input, postprocess, { source: "script", target: "script" }),
        connectNodes(postprocess, outputNode, { source: "finalVideo", target: "finalVideo" }),
        connectNodes(postprocess, outputNode, { source: "subtitle", target: "subtitle" }),
      ]),
    }
  }

  if (isComic) {
    const nodes = createWorkflowNodes(
      ["start", "field_input", "prompt_template", "image_model", "video_model", "subtitle", "ffmpeg", "video_output"],
      tool,
    )
    return {
      nodes,
      edges: compactEdges([
        connectByType(nodes, "start", "field_input", { source: "context", target: "context" }),
        connectByType(nodes, "field_input", "prompt_template", { source: "params", target: "params" }),
        connectByType(nodes, "prompt_template", "image_model", { source: "prompt", target: "prompt" }),
        connectByType(nodes, "prompt_template", "subtitle", { source: "prompt", target: "text" }),
        connectByType(nodes, "image_model", "video_model", { source: "image", target: "image" }),
        connectByType(nodes, "video_model", "ffmpeg", { source: "video", target: "video" }),
        connectByType(nodes, "subtitle", "ffmpeg", { source: "subtitle", target: "subtitle" }),
        connectByType(nodes, "ffmpeg", "video_output", { source: "video", target: "video" }),
      ]),
    }
  }

  const sequence = isEnterprise
    ? ["start", "field_input", "search", "llm_model", "document_export", "text_output"]
    : isSocial
      ? ["start", "field_input", "search", "llm_model", "text_output"]
      : output.includes("VIDEO")
        ? ["start", "field_input", "prompt_template", "video_model", "video_output"]
        : ["start", "field_input", "prompt_template", "llm_model", "text_output"]
  const nodes = createWorkflowNodes(sequence, tool)
  return {
    nodes,
    edges: nodes.slice(0, -1).map((node, index) => createWorkflowEdge(node, nodes[index + 1])),
  }
}

function shouldUseDigitalHumanDefault(tool: ToolSummary | null | undefined, nodes: WFNode[]): boolean {
  if (isComicDramaTool(tool)) return shouldReloadComicWorkflow(tool, nodes)
  if (nodes.length === 0) return true
  if (!isDigitalHumanTool(tool)) return false
  const nodeTypes = nodes.map((node) => getNodeDef(node.data)?.type || node.data.nodeDefType || node.data.kind)
  const allowed = new Set(["field_input", "prompt_template", "llm_model", "backend_tool", "final_output"])
  return nodeTypes.some((type) => !allowed.has(String(type)))
}

function findSlotByHandle(node: WFNode | undefined, side: "inputSlots" | "outputSlots", handle?: string | null) {
  const prefix = side === "inputSlots" ? /^in-/ : /^out-/
  const name = handle?.replace(prefix, "")
  return getNodeSlots(node, side).find((slot) => slot.name === name)
}

function slotsCompatible(sourceType: string, targetType: string): boolean {
  const source = sourceType.trim().toLowerCase()
  const target = targetType.trim().toLowerCase()
  if (!source || !target) return true
  if (source === target) return true
  if (source === "any" || target === "any") return true
  if (source === "json" && (target === "text" || target === "json")) return true
  if (source === "text" && target === "json") return true
  return false
}

function canConnectBySlot(nodes: WFNode[], connection: Connection): boolean {
  if (!connection.source || !connection.target) return false
  const sourceNode = nodes.find((node) => node.id === connection.source)
  const targetNode = nodes.find((node) => node.id === connection.target)
  const sourceSlot = findSlotByHandle(sourceNode, "outputSlots", connection.sourceHandle)
  const targetSlot = findSlotByHandle(targetNode, "inputSlots", connection.targetHandle)
  if (!sourceSlot || !targetSlot) return true

  return slotsCompatible(sourceSlot.type, targetSlot.type)
}

function applyConnectionMapping(nodes: WFNode[], connection: Connection) {
  const sourceNode = nodes.find((node) => node.id === connection.source)
  const targetNode = nodes.find((node) => node.id === connection.target)
  const sourceSlot = findSlotByHandle(sourceNode, "outputSlots", connection.sourceHandle)
  const targetSlot = findSlotByHandle(targetNode, "inputSlots", connection.targetHandle)
  return { nodes, sourceSlot, targetSlot }
}

function isDuplicateConnection(edges: WFEdge[], connection: Connection): boolean {
  return edges.some(
    (edge) =>
      edge.source === connection.source &&
      edge.target === connection.target &&
      edge.sourceHandle === connection.sourceHandle &&
      edge.targetHandle === connection.targetHandle,
  )
}

function isPositionChange(change: WFNodeChange): boolean {
  return change.type === "position" && Boolean(change.dragging)
}

export function WorkflowCanvas({
  toolId,
  toolName,
  tool,
  modelConfigs,
  variant = "workspace",
}: WorkflowCanvasProps) {
  const [reactFlowInstance, setReactFlowInstance] = useState<any>(null)
  const [nodes, setNodes, baseOnNodesChange] = useNodesState<WFNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<WFEdge>([])
  const [groups, setGroups] = useState<WorkflowGroup[]>([])
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveStatus, setSaveStatus] = useState<"idle" | "saved" | "error">("idle")
  const [loading, setLoading] = useState(true)
  const [workflowVersion, setWorkflowVersion] = useState(0)
  const [workflowStatus, setWorkflowStatus] = useState<string>("DRAFT")
  const [hasUnpublishedChanges, setHasUnpublishedChanges] = useState(false)
  const [draftRevision, setDraftRevision] = useState(0)
  const [publishing, setPublishing] = useState(false)
  const [versionsOpen, setVersionsOpen] = useState(false)
  const [versions, setVersions] = useState<WorkflowVersionItem[]>([])
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [restoringVersion, setRestoringVersion] = useState<number | null>(null)
  const [fieldDraft, setFieldDraft] = useState<EditableField[]>([])
  const [fieldSaving, setFieldSaving] = useState(false)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [inspectorWidth, setInspectorWidth] = useState(360)
  const inspectorResizeRef = useRef<{ startX: number; startWidth: number } | null>(null)
  const dragSnapshotTakenRef = useRef(false)

  const { pushSnapshot, undo, redo, canUndo, canRedo, clear } = useUndoRedo()
  const { showMenu, contextMenu } = useContextMenu()

  const pushChange = useCallback(() => {
    pushSnapshot(
      nodes as unknown as import("@/lib/api/types").WorkflowNode[],
      edges as unknown as import("@/lib/api/types").WorkflowEdge[],
      groups,
    )
  }, [nodes, edges, groups, pushSnapshot])

  const onNodesChange = useCallback(
    (changes: WFNodeChange[]) => {
      if (!dragSnapshotTakenRef.current && changes.some(isPositionChange)) {
        dragSnapshotTakenRef.current = true
        pushChange()
      }
      if (changes.some((change) => change.type === "position" && change.dragging === false)) {
        dragSnapshotTakenRef.current = false
      }
      baseOnNodesChange(changes)
    },
    [baseOnNodesChange, pushChange],
  )

  useEffect(() => {
    if (!toolId || !Number.isFinite(toolId)) {
      setLoading(false)
      return
    }

    setLoading(true)
    fetchWorkflow(toolId)
      .then((workflow) => {
        let { nodes: loadedNodes, edges: loadedEdges, groups: loadedGroups } = apiToReactFlow(workflow)
        if (loadedNodes.length === 0 || shouldUseDigitalHumanDefault(tool, loadedNodes)) {
          const defaults = buildDefaultWorkflow(tool, modelConfigs)
          loadedNodes = defaults.nodes
          loadedEdges = defaults.edges
          loadedGroups = []
        } else if (isComicDramaTool(tool)) {
          const merged = mergeComicWorkflowWithTemplate(loadedNodes, loadedEdges, tool, modelConfigs)
          loadedNodes = merged.nodes
          loadedEdges = merged.edges
        }
        loadedEdges = dedupeEdgeIds(normalizeWorkflowEdges(loadedNodes, loadedEdges))
        setNodes(loadedNodes)
        setEdges(loadedEdges)
        setGroups(loadedGroups)
        setWorkflowVersion(workflow.version)
        setWorkflowStatus(workflow.status || "DRAFT")
        setHasUnpublishedChanges(workflow.hasUnpublishedChanges)
        setDraftRevision(workflow.draftRevision)
        clear()
      })
      .catch(() => {
        const defaults = buildDefaultWorkflow(tool, modelConfigs)
        setNodes(defaults.nodes)
        setEdges(defaults.edges)
        setGroups([])
        clear()
      })
      .finally(() => setLoading(false))
  }, [toolId, tool, modelConfigs, setNodes, setEdges, clear])

  useEffect(() => {
    if (!toolId || !Number.isFinite(toolId)) return
    setFieldError(null)
    fetchToolFields(toolId)
      .then((fields) => {
        const draft = fields.map((field, index) => editableFromToolField(field, index))
        setFieldDraft(
          isDigitalHumanTool(tool) && !hasDigitalHumanFields(draft)
            ? cloneFields(DIGITAL_HUMAN_FIELD_DRAFT)
            : isComicDramaTool(tool) && draft.length === 0
              ? cloneFields(COMIC_DRAMA_FIELD_DRAFT)
              : draft,
        )
      })
      .catch(() => {
        setFieldDraft(
          isDigitalHumanTool(tool)
            ? cloneFields(DIGITAL_HUMAN_FIELD_DRAFT)
            : isComicDramaTool(tool)
              ? cloneFields(COMIC_DRAMA_FIELD_DRAFT)
              : [],
        )
        setFieldError("字段配置加载失败")
      })
  }, [toolId, tool])

  const selectedNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) || null,
    [nodes, selectedNodeId],
  )

  useEffect(() => {
    if (fieldDraft.length === 0) return
    if (isComicDramaTool(tool)) {
      setNodes((current: WFNode[]) => current.map(normalizeComicFieldInputSlots))
      return
    }
    const fieldSlots = slotsFromFields(fieldDraft)
    setNodes((current: WFNode[]) =>
      current.map((node) => {
        if (getNodeDef(node.data)?.type !== "field_input") return node
        const existing = node.data.outputSlots || []
        const paramsSlot = existing.find((slot) => slot.name === "params") || {
          name: "params",
          type: "json",
          label: "用户填写参数",
        }
        const merged = [
          paramsSlot,
          ...fieldSlots.filter((slot) => slot.name !== "params"),
        ]
        return { ...node, data: { ...node.data, outputSlots: merged } }
      }),
    )
  }, [fieldDraft, setNodes])

  const selectedNodeDef = useMemo(() => {
    if (!selectedNode) return undefined
    return getNodeDef(selectedNode.data)
  }, [selectedNode])

  const selectedWorkflowNode = useMemo(() => {
    if (!selectedNode) return null
    return {
      id: selectedNode.id,
      type: selectedNode.type || "workflowNode",
      position: selectedNode.position,
      data: (selectedNode.data || {}) as import("@/lib/api/types").WorkflowNodeData,
      width: selectedNode.width as number | undefined,
      height: selectedNode.height as number | undefined,
      selected: selectedNode.selected,
    } as WorkflowNode
  }, [selectedNode])

  const visibleNodes = useMemo(() => {
    const modelMap = new Map(modelConfigs.map((config) => [config.id, config]))
    return nodes.map((node) => {
      const modelConfigId = Number(node.data.parameters?.modelConfigId)
      const model = Number.isFinite(modelConfigId) ? modelMap.get(modelConfigId) : undefined
      if (!model) {
        const { selectedModelLabel, selectedModelProvider, selectedModelName, ...data } = node.data
        return { ...node, data }
      }
      return {
        ...node,
        data: {
          ...node.data,
          selectedModelLabel: modelLabel(model),
          selectedModelProvider: model.provider,
          selectedModelName: model.modelName,
        },
      }
    })
  }, [nodes, modelConfigs])

  const doSave = useCallback(async (): Promise<boolean> => {
    setSaving(true)
    setSaveStatus("idle")
    try {
      // 保存只更新草稿；已有正式版本继续服务，直到管理员显式发布更新。
      const saved = await saveWorkflow(toolId, {
        workflowName: toolName || "default",
        nodesJson: JSON.stringify(nodes),
        edgesJson: JSON.stringify(edges),
        groupsJson: groups.length > 0 ? JSON.stringify(groups) : undefined,
        expectedDraftRevision: draftRevision,
      })
      setWorkflowVersion(saved.version)
      setWorkflowStatus(saved.status || "DRAFT")
      setHasUnpublishedChanges(saved.hasUnpublishedChanges)
      setDraftRevision(saved.draftRevision)
      setSaveStatus("saved")
      setTimeout(() => setSaveStatus("idle"), 2000)
      return true
    } catch {
      setSaveStatus("error")
      return false
    } finally {
      setSaving(false)
    }
  }, [toolId, toolName, nodes, edges, groups, draftRevision])

  const doPublish = useCallback(async () => {
    setPublishing(true)
    try {
      const saved = await doSave()
      if (!saved) {
        toast.error("保存失败，已取消发布")
        return
      }
      const validation = await validateWorkflow(toolId)
      if (!validation.valid) {
        toast.error(`工作流校验未通过：${validation.errors.join("；")}`)
        return
      }
      const published = await publishWorkflow(toolId)
      setWorkflowStatus(published.status || "PUBLISHED")
      setWorkflowVersion(published.version)
      setHasUnpublishedChanges(published.hasUnpublishedChanges)
      setDraftRevision(published.draftRevision)
      toast.success("工作流版本已发布", {
        description: "后续新任务将使用此版本；工具是否开放仍由工具列表中的上线开关控制。",
      })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "发布失败")
    } finally {
      setPublishing(false)
    }
  }, [doSave, toolId])

  const loadVersions = useCallback(async () => {
    setVersionsLoading(true)
    try {
      setVersions(await fetchWorkflowVersions(toolId, 1, 20))
    } catch {
      setVersions([])
    } finally {
      setVersionsLoading(false)
    }
  }, [toolId])

  const toggleVersions = useCallback(() => {
    setVersionsOpen((open) => {
      const next = !open
      if (next) void loadVersions()
      return next
    })
  }, [loadVersions])

  const doRestoreVersion = useCallback(
    async (version: number) => {
      setRestoringVersion(version)
      try {
        const restored = await restoreWorkflowVersion(toolId, version)
        const { nodes: restoredNodes, edges: restoredEdges, groups: restoredGroups } = apiToReactFlow(restored)
        setNodes(restoredNodes)
        setEdges(dedupeEdgeIds(normalizeWorkflowEdges(restoredNodes, restoredEdges)))
        setGroups(restoredGroups)
        setWorkflowVersion(restored.version)
        setWorkflowStatus(restored.status || "DRAFT")
        setHasUnpublishedChanges(restored.hasUnpublishedChanges)
        setDraftRevision(restored.draftRevision)
        clear()
        toast.success(`已恢复到 v${version}（生成新版本 v${restored.version}）`)
        void loadVersions()
      } catch (err) {
        toast.error(err instanceof Error ? err.message : "恢复版本失败")
      } finally {
        setRestoringVersion(null)
      }
    },
    [toolId, setNodes, setEdges, clear, loadVersions],
  )

  const handleUndo = useCallback(() => {
    const snapshot = undo()
    if (!snapshot) return
    setNodes(snapshot.nodes as WFNode[])
    setEdges(snapshot.edges as WFEdge[])
    setGroups(snapshot.groups)
  }, [undo, setNodes, setEdges])

  const handleRedo = useCallback(() => {
    const snapshot = redo()
    if (!snapshot) return
    setNodes(snapshot.nodes as WFNode[])
    setEdges(snapshot.edges as WFEdge[])
    setGroups(snapshot.groups)
  }, [redo, setNodes, setEdges])

  const onDragOver = useCallback((event: DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = "move"
  }, [])

  const handlePaletteDragStart = useCallback((event: React.DragEvent, def: NodeTypeDefinition) => {
    event.dataTransfer.setData("application/reactflow-type", JSON.stringify({ type: def.type }))
    event.dataTransfer.effectAllowed = "move"
  }, [])

  const addNode = useCallback(
    (def: NodeTypeDefinition, position?: { x: number; y: number }) => {
      pushChange()
      const fallback = reactFlowInstance
        ? reactFlowInstance.screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 })
        : { x: 420, y: 240 }
      const newNode = createNodeFromDef(def, position || fallback)
      setNodes((current: WFNode[]) => [...current, newNode])
      setSelectedNodeId(newNode.id)
    },
    [reactFlowInstance, pushChange, setNodes],
  )

  const onDrop = useCallback(
    (event: DragEvent) => {
      event.preventDefault()
      if (!reactFlowInstance) return
      const typeJson = event.dataTransfer.getData("application/reactflow-type")
      if (!typeJson) return

      const parsed = JSON.parse(typeJson) as { type: string }
      const def = NODE_TYPE_MAP.get(parsed.type)
      if (!def) return

      addNode(def, reactFlowInstance.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
    },
    [reactFlowInstance, addNode],
  )

  const isValidConnection = useCallback(
    (connection: Connection | WFEdge) =>
      canConnectBySlot(nodes, {
        source: connection.source ?? null,
        target: connection.target ?? null,
        sourceHandle: connection.sourceHandle ?? null,
        targetHandle: connection.targetHandle ?? null,
      } as Connection),
    [nodes],
  )

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target) return
      if (!canConnectBySlot(nodes, connection)) {
        toast.error("无法建立连线", {
          description: "输出/输入参数类型不匹配，请换一个空闲入口/出口。",
        })
        return
      }
      if (isDuplicateConnection(edges, connection)) {
        toast.error("连线已存在", { description: "同一对入口/出口只能连一条线。" })
        return
      }
      pushChange()

      const { sourceSlot, targetSlot } = applyConnectionMapping(nodes, connection)
      const targetHandle =
        connection.targetHandle || (targetSlot ? `in-${targetSlot.name}` : undefined) || undefined
      const edgeId = `e-${connection.source}-${connection.sourceHandle}-${connection.target}-${targetHandle}-${randomId()}`
      setEdges((current: WFEdge[]) => [
        ...current,
        {
          ...connection,
          targetHandle,
          id: edgeId,
          type: "smoothstep",
          markerEnd: { type: MarkerType.ArrowClosed, color: "#64748b" },
          style: { stroke: "#64748b", strokeWidth: 2.2 },
          data: sourceSlot
            ? {
                sourceParam: sourceSlot,
                targetParam: targetSlot,
                mapping: {
                  from: sourceSlot.name,
                  to: targetSlot?.name || sourceSlot.name,
                  modality: sourceSlot.type,
                },
              }
            : undefined,
        } as WFEdge,
      ])
    },
    [nodes, edges, pushChange, setEdges],
  )

  const deleteEdge = useCallback(
    (edgeId: string) => {
      if (!edgeId) return
      pushChange()
      setEdges((current: WFEdge[]) => current.filter((edge) => edge.id !== edgeId))
      if (selectedEdgeId === edgeId) setSelectedEdgeId(null)
    },
    [pushChange, setEdges, selectedEdgeId],
  )

  const deleteNode = useCallback(
    (nodeId: string) => {
      const node = nodes.find((item) => item.id === nodeId)
      if (!node || getNodeDef(node.data)?.type === "start") return
      pushChange()
      setNodes((current: WFNode[]) => current.filter((item) => item.id !== nodeId))
      setEdges((current: WFEdge[]) =>
        current.filter((edge) => edge.source !== nodeId && edge.target !== nodeId),
      )
      if (selectedNodeId === nodeId) setSelectedNodeId(null)
    },
    [nodes, pushChange, setNodes, setEdges, selectedNodeId],
  )

  const duplicateNode = useCallback(
    (nodeId: string) => {
      const node = nodes.find((item) => item.id === nodeId)
      if (!node) return
      pushChange()
      const newNode: WFNode = {
        ...node,
        id: randomId(),
        position: { x: node.position.x + 60, y: node.position.y + 60 },
        selected: false,
        data: { ...(node.data as NodeDataWithSlots) },
      }
      setNodes((current: WFNode[]) => [...current, newNode])
      setSelectedNodeId(newNode.id)
    },
    [nodes, pushChange, setNodes],
  )

  /**
   * 一键整理：按连线拓扑分层（从无入边节点开始 BFS），
   * 同层节点纵向均匀排布。仅整理普通节点，分组框保持原位。
   */
  const autoLayout = useCallback(() => {
    const layoutNodes = nodes.filter((node) => node.type !== "groupNode")
    if (layoutNodes.length === 0) return
    pushChange()

    const ids = new Set(layoutNodes.map((node) => node.id))
    const indegree = new Map<string, number>()
    const adjacency = new Map<string, string[]>()
    for (const id of ids) indegree.set(id, 0)
    for (const edge of edges) {
      if (!edge.source || !edge.target || !ids.has(edge.source) || !ids.has(edge.target)) continue
      indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1)
      adjacency.set(edge.source, [...(adjacency.get(edge.source) || []), edge.target])
    }

    const layerOf = new Map<string, number>()
    let frontier = layoutNodes.filter((node) => (indegree.get(node.id) || 0) === 0).map((node) => node.id)
    if (frontier.length === 0) frontier = [layoutNodes[0]!.id]
    let layer = 0
    const remainingIndegree = new Map(indegree)
    const visited = new Set<string>()
    while (frontier.length > 0) {
      const next: string[] = []
      for (const id of frontier) {
        if (visited.has(id)) continue
        visited.add(id)
        layerOf.set(id, layer)
        for (const child of adjacency.get(id) || []) {
          const remaining = (remainingIndegree.get(child) || 0) - 1
          remainingIndegree.set(child, remaining)
          if (remaining <= 0 && !visited.has(child)) next.push(child)
        }
      }
      frontier = next
      layer += 1
    }
    // 环或孤立节点放到最后一层
    for (const node of layoutNodes) {
      if (!layerOf.has(node.id)) layerOf.set(node.id, layer)
    }

    const X_GAP = 380
    const Y_PAD = 56
    // 节点高度因类型而异（如用户输入参数节点高 220），固定行距会导致同层节点重叠。
    // 按每个节点的真实高度堆叠，保证「散得开」、彼此不压盖。
    const nodeHeight = (id: string) => {
      const node = layoutNodes.find((item) => item.id === id)
      if (!node) return 160
      const measured = node.measured?.height ?? (typeof node.height === "number" ? node.height : undefined)
      const def = getNodeDef(node.data)
      return measured || def?.defaultHeight || 160
    }
    const layerBuckets = new Map<number, string[]>()
    for (const node of layoutNodes) {
      const l = layerOf.get(node.id) || 0
      layerBuckets.set(l, [...(layerBuckets.get(l) || []), node.id])
    }
    const positions = new Map<string, { x: number; y: number }>()
    for (const [l, bucket] of layerBuckets) {
      const heights = bucket.map((id) => nodeHeight(id))
      const totalHeight = heights.reduce((sum, h) => sum + h, 0) + Y_PAD * Math.max(bucket.length - 1, 0)
      let cursor = 280 - totalHeight / 2
      bucket.forEach((id, index) => {
        positions.set(id, { x: 60 + l * X_GAP, y: cursor })
        cursor += heights[index]! + Y_PAD
      })
    }
    setNodes((current: WFNode[]) =>
      current.map((node) => {
        const position = positions.get(node.id)
        return position ? { ...node, position } : node
      }),
    )
    window.requestAnimationFrame(() => reactFlowInstance?.fitView({ padding: 0.28 }))
  }, [nodes, edges, pushChange, setNodes, reactFlowInstance])

  const handleInspectorUpdate = useCallback(
    (nodeId: string, updates: Record<string, unknown>) => {
      pushChange()
      setNodes((current: WFNode[]) =>
        current.map((node) =>
          node.id === nodeId
            ? { ...node, data: { ...(node.data as NodeDataWithSlots), ...updates } }
            : node,
        ),
      )
    },
    [pushChange, setNodes],
  )

  const handleSaveFields = useCallback(async () => {
    setFieldError(null)
    setFieldSaving(true)
    try {
      const saved = await updateToolFields(
        toolId,
        fieldDraft.map((field, index) => toFieldPayload(field, index)),
      )
      setFieldDraft(saved.map((field, index) => editableFromToolField(field, index)))
    } catch (err) {
      setFieldError(err instanceof Error ? err.message : "字段配置保存失败")
    } finally {
      setFieldSaving(false)
    }
  }, [toolId, fieldDraft])

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable) return
      const mod = event.metaKey || event.ctrlKey

      if (mod && event.key === "s") {
        event.preventDefault()
        doSave()
      } else if (mod && event.shiftKey && event.key === "Z") {
        event.preventDefault()
        handleRedo()
      } else if (mod && event.key === "z") {
        event.preventDefault()
        handleUndo()
      } else if (mod && event.key === "a") {
        event.preventDefault()
        setNodes((current: WFNode[]) => current.map((node) => ({ ...node, selected: true })))
      } else if (event.key === "Delete" || event.key === "Backspace") {
        if (selectedNodeId) {
          event.preventDefault()
          deleteNode(selectedNodeId)
        } else if (selectedEdgeId) {
          event.preventDefault()
          deleteEdge(selectedEdgeId)
        }
      } else if (mod && event.key === "d") {
        event.preventDefault()
        if (selectedNodeId) duplicateNode(selectedNodeId)
      }
    }

    window.addEventListener("keydown", handler)
    return () => window.removeEventListener("keydown", handler)
  }, [doSave, handleRedo, handleUndo, selectedNodeId, selectedEdgeId, deleteNode, deleteEdge, duplicateNode, setNodes])

  if (loading) {
    return (
      <div className="flex min-h-[420px] items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
        正在加载工作流画布...
      </div>
    )
  }

  const isPublished = workflowStatus === "PUBLISHED"

  const toolbar = (
    <div className="flex flex-wrap items-center gap-2">
      <Badge variant={isPublished ? "default" : "secondary"} className="gap-1 text-xs">
        {isPublished ? <CheckCircle2 className="h-3 w-3" /> : null}
        {isPublished ? (hasUnpublishedChanges ? "有未发布更改" : "正式版本已发布") : "仅草稿"}
      </Badge>
      <Badge
        variant={saveStatus === "saved" ? "default" : saveStatus === "error" ? "destructive" : "outline"}
        className="text-xs"
      >
        {saveStatus === "saved" ? "已保存" : saveStatus === "error" ? "保存失败" : `v${workflowVersion || 1}`}
      </Badge>
      <Button type="button" size="sm" variant="outline" className="gap-1.5 text-xs" onClick={doSave} disabled={saving}>
        <Save className="h-3.5 w-3.5" />
        {saving ? "保存中..." : "保存"}
      </Button>
      <Button
        type="button"
        size="sm"
        className="gap-1.5 text-xs"
        onClick={doPublish}
        disabled={publishing || saving}
        variant="default"
      >
        <Rocket className="h-3.5 w-3.5" />
        {publishing ? "发布中..." : isPublished ? "发布更新" : "发布首版"}
      </Button>
      <Button type="button" size="sm" variant="outline" className="gap-1.5 text-xs" onClick={toggleVersions}>
        <History className="h-3.5 w-3.5" />
        版本
      </Button>
      <Button type="button" size="sm" variant="outline" className="gap-1.5 text-xs" onClick={autoLayout} title="按数据流拓扑自动排列节点">
        <LayoutGrid className="h-3.5 w-3.5" />
        整理布局
      </Button>
      <Button type="button" size="sm" variant="outline" className="gap-1.5 text-xs" onClick={handleUndo} disabled={!canUndo}>
        <Undo2 className="h-3.5 w-3.5" />
        撤销
      </Button>
      <Button type="button" size="sm" variant="outline" className="gap-1.5 text-xs" onClick={handleRedo} disabled={!canRedo}>
        <Redo2 className="h-3.5 w-3.5" />
        重做
      </Button>
    </div>
  )

  const versionsPanel = versionsOpen ? (
    <div className="mb-2 rounded-lg border border-border bg-card p-3">
      <div className="mb-2 flex items-center justify-between">
        <p className="text-xs font-medium text-foreground">版本历史（恢复会生成新版本，不会丢失当前内容）</p>
        <Button type="button" size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={() => setVersionsOpen(false)}>
          收起
        </Button>
      </div>
      {versionsLoading ? (
        <p className="text-xs text-muted-foreground">正在加载版本列表...</p>
      ) : versions.length === 0 ? (
        <p className="text-xs text-muted-foreground">暂无历史版本（首次保存后产生）</p>
      ) : (
        <ul className="max-h-44 space-y-1 overflow-y-auto">
          {versions.map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-2 rounded-md border border-border/60 px-2 py-1.5">
              <div className="min-w-0 text-xs">
                <span className="font-medium">v{item.version}</span>
                <span className="ml-2 text-muted-foreground">
                  {item.snapshotLabel || "正式版本"} · {item.publishedAt?.replace("T", " ").slice(0, 19) || "--"}
                </span>
              </div>
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="h-6 px-2 text-xs"
                disabled={restoringVersion !== null}
                onClick={() => void doRestoreVersion(item.version)}
              >
                {restoringVersion === item.version ? "恢复中..." : "恢复"}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  ) : null

  const canvas = (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-3xl text-xs leading-5 text-muted-foreground">
          当前画布用于描述工具执行流程。保存只更新草稿，不影响正在使用的正式版本；发布更新后，新任务使用新版本，运行中的任务继续使用原版本。工具是否对用户开放由工具列表中的上线开关控制。
        </p>
        {toolbar}
      </div>
      {versionsPanel}

      <div
        className="flex-1 overflow-hidden rounded-lg border border-border bg-card"
        style={{ minHeight: variant === "workspace" ? "calc(100vh - 220px)" : 560 }}
      >
        <ReactFlow
          nodes={visibleNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          isValidConnection={isValidConnection}
          connectionMode={ConnectionMode.Loose}
          onNodeClick={(_event, node) => setSelectedNodeId(node.id)}
          onEdgeClick={(_event, edge) => {
            setSelectedEdgeId(edge.id)
            setSelectedNodeId(null)
          }}
          onPaneClick={() => {
            setSelectedNodeId(null)
            setSelectedEdgeId(null)
          }}
          onNodeContextMenu={(event, node) => {
            event.preventDefault()
            setSelectedNodeId(node.id)
            setSelectedEdgeId(null)
            const canDelete = getNodeDef(node.data)?.type !== "start"
            showMenu(event.clientX, event.clientY, [
              ...buildNodeActions(
                () => duplicateNode(node.id),
                () => deleteNode(node.id),
                canDelete,
              ),
            ])
          }}
          onEdgeContextMenu={(event, edge) => {
            event.preventDefault()
            setSelectedEdgeId(edge.id)
            setSelectedNodeId(null)
            showMenu(event.clientX, event.clientY, [
              {
                label: "删除连线",
                onClick: () => deleteEdge(edge.id),
                destructive: true,
              },
            ] as any)
          }}
          onInit={setReactFlowInstance}
          onDragOver={onDragOver}
          onDrop={onDrop}
          nodeTypes={reactFlowNodeTypes}
          fitView
          fitViewOptions={{ padding: 0.28 }}
          deleteKeyCode={null}
          multiSelectionKeyCode="Shift"
          selectionOnDrag={false}
          panOnDrag={[0, 1, 2]}
          panOnScroll={false}
          zoomOnScroll
          nodesDraggable
          nodesConnectable
          elementsSelectable
          snapToGrid
          snapGrid={[20, 20]}
          minZoom={0.1}
          maxZoom={3}
          defaultEdgeOptions={{
            type: "smoothstep",
            markerEnd: { type: MarkerType.ArrowClosed, color: "#64748b" },
            style: { stroke: "#64748b", strokeWidth: 2.2 },
          }}
          proOptions={{ hideAttribution: true }}
        >
          <Background
            variant={BackgroundVariant.Dots}
            gap={20}
            size={1.5}
            color="hsl(var(--muted-foreground) / 0.18)"
          />
          <Controls
            showZoom
            showFitView
            showInteractive={false}
            position="bottom-left"
            className="!rounded-lg !border !border-border !bg-card !shadow-sm [&>button]:!border-border [&>button]:!bg-card [&>button]:!text-foreground [&>button:hover]:!bg-accent"
          />
          <MiniMap
            position="bottom-right"
            className="!rounded-lg !border !border-border !bg-card !shadow-sm"
            nodeStrokeColor="hsl(var(--border))"
            nodeColor={(node) => {
              const data = node.data as Record<string, unknown> | undefined
              return (data?.color as string | undefined) || "#94a3b8"
            }}
            maskColor="hsl(var(--background) / 0.7)"
          />
        </ReactFlow>
      </div>
    </div>
  )

  const palette = (
    <div className="w-[250px] shrink-0 overflow-hidden rounded-lg border border-border bg-card">
      <NodePalette tool={tool} onDragStart={handlePaletteDragStart} onAddNode={addNode} />
    </div>
  )

  const inspector = (
    <div className="relative flex shrink-0" style={{ width: inspectorWidth }}>
      <button
        type="button"
        aria-label="拖拽调整节点属性面板宽度"
        className="absolute -left-1.5 top-0 z-10 h-full w-3 cursor-col-resize border-0 bg-transparent p-0"
        onMouseDown={(event) => {
          inspectorResizeRef.current = { startX: event.clientX, startWidth: inspectorWidth }
          const onMove = (moveEvent: MouseEvent) => {
            const snapshot = inspectorResizeRef.current
            if (!snapshot) return
            const next = snapshot.startWidth - (moveEvent.clientX - snapshot.startX)
            setInspectorWidth(Math.min(720, Math.max(280, next)))
          }
          const onUp = () => {
            inspectorResizeRef.current = null
            window.removeEventListener("mousemove", onMove)
            window.removeEventListener("mouseup", onUp)
          }
          window.addEventListener("mousemove", onMove)
          window.addEventListener("mouseup", onUp)
        }}
      >
        <span className="mx-auto block h-10 w-1 rounded-full bg-border" />
      </button>
      <div className="h-full flex-1 overflow-hidden rounded-lg border border-border bg-card">
        <InspectorPanel
          node={selectedWorkflowNode}
          modelConfigs={modelConfigs}
          onUpdate={handleInspectorUpdate}
          onDelete={deleteNode}
          onDuplicate={duplicateNode}
          fieldDraft={fieldDraft}
          fieldSaving={fieldSaving}
          fieldError={fieldError}
          onFieldDraftChange={setFieldDraft}
          onSaveFields={handleSaveFields}
          enableFieldEditor={!isWorkflowAgentTool(tool)}
          canDelete={selectedNodeDef ? selectedNodeDef.type !== "start" : false}
        />
      </div>
    </div>
  )

  return (
    <>
      {contextMenu}
      {variant === "workspace" ? (
        <div className="flex h-full gap-3">
          {palette}
          {canvas}
          {inspector}
        </div>
      ) : (
        <div className="flex gap-3">
          {canvas}
          {inspector}
        </div>
      )}
    </>
  )
}
