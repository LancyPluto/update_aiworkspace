"use client"

import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from "react"
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type Connection,
  MarkerType,
  BackgroundVariant,
  Handle,
  Position,
  type NodeProps,
} from "@xyflow/react"
import "@xyflow/react/dist/style.css"
import {
  Captions,
  FileInput,
  FileText,
  GripVertical,
  Image,
  Play,
  Plus,
  Settings2,
  Sparkles,
  Video,
  Volume2,
  Wrench,
  Maximize2,
  type LucideIcon,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"
import type { AgentModelConfig, ToolField } from "@/lib/api/types"

// ---- Types ----

export interface WorkflowTool {
  toolCode: string
  name: string
  description: string
  toolType: string
  inputModality: string
  outputModality: string
  executionHandler?: string | null
  modelConfigId: number | null
  modelConfigName: string | null
  modelName: string | null
  configNote: string | null
}

interface WorkflowNodeData extends Record<string, unknown> {
  title: string
  subtitle: string
  detail: string
  kind: "start" | "input" | "model" | "tool" | "output"
  iconName: string
  config?: AgentModelConfig | null
}

type WorkflowFlowNode = Node<WorkflowNodeData>
type WorkflowFlowEdge = Edge

// ---- Constants ----

const NODE_WIDTH = 240

const MODEL_SEED_CODES = {
  voice: "siliconflow_voice_tts",
  asr: "siliconflow_asr_teleai",
  image: "siliconflow_image_turbo",
  seedance: "volcengine-gateway-video",
}

const workflowPalette: Array<{
  name: string
  description: string
  icon: LucideIcon
  iconName: string
  kind: WorkflowNodeData["kind"]
  color: string
}> = [
  { name: "Start", description: "接收用户输入和上下文", icon: Play, iconName: "play", kind: "start", color: "#10b981" },
  { name: "Input", description: "字段校验、参数映射、补参", icon: FileInput, iconName: "file-input", kind: "input", color: "#64748b" },
  { name: "LLM / Model", description: "调用系统配置中的模型", icon: Settings2, iconName: "settings", kind: "model", color: "#3b82f6" },
  { name: "Tool", description: "Worker、搜索、文件处理能力", icon: Wrench, iconName: "wrench", kind: "tool", color: "#f59e0b" },
  { name: "End", description: "结果渲染、下载、状态回写", icon: Sparkles, iconName: "sparkles", kind: "output", color: "#ef4444" },
]

const kindColorMap: Record<WorkflowNodeData["kind"], { bg: string; text: string; border: string; handle: string }> = {
  start: { bg: "bg-emerald-50 dark:bg-emerald-950/40", text: "text-emerald-700 dark:text-emerald-300", border: "border-emerald-200 dark:border-emerald-800", handle: "bg-emerald-400" },
  input: { bg: "bg-slate-100 dark:bg-slate-800/40", text: "text-slate-600 dark:text-slate-300", border: "border-slate-200 dark:border-slate-700", handle: "bg-slate-400" },
  model: { bg: "bg-blue-50 dark:bg-blue-950/40", text: "text-blue-700 dark:text-blue-300", border: "border-blue-200 dark:border-blue-800", handle: "bg-blue-400" },
  tool: { bg: "bg-amber-50 dark:bg-amber-950/40", text: "text-amber-700 dark:text-amber-300", border: "border-amber-200 dark:border-amber-800", handle: "bg-amber-400" },
  output: { bg: "bg-rose-50 dark:bg-rose-950/40", text: "text-rose-700 dark:text-rose-300", border: "border-rose-200 dark:border-rose-800", handle: "bg-rose-400" },
}

// ---- Icon resolver (pass icon name through data) ----

const iconMap: Record<string, LucideIcon> = {
  play: Play,
  "file-input": FileInput,
  "file-text": FileText,
  settings: Settings2,
  sparkles: Sparkles,
  wrench: Wrench,
  image: Image,
  video: Video,
  "volume-2": Volume2,
  captions: Captions,
}

function resolveIcon(iconName: string): LucideIcon {
  return iconMap[iconName] || Settings2
}

// ---- Helpers ----

function findModelConfig(configs: AgentModelConfig[], ...codes: string[]) {
  return configs.find((c) => codes.includes(c.configCode || ""))
}

function modelLabel(config?: AgentModelConfig | null, fallback?: string) {
  return config?.displayName || config?.modelName || fallback || "未绑定模型"
}

function isDigitalHumanWorkflow(tool: WorkflowTool) {
  const handler = (tool.executionHandler || "").toUpperCase()
  return handler === "DIGITAL_HUMAN" || tool.toolCode === "digital_human_agent" || tool.toolCode === "ai_comic_drama_agent"
}

function modelConfigOptionLabel(config: AgentModelConfig) {
  return `${config.displayName || config.modelName} / ${config.provider}`
}

function buildWorkflowNodes(tool: WorkflowTool, configs: AgentModelConfig[]): WorkflowFlowNode[] {
  const primaryConfig = tool.modelConfigId
    ? configs.find((c) => c.id === tool.modelConfigId)
    : null
  const voiceConfig = findModelConfig(configs, MODEL_SEED_CODES.voice)
  const asrConfig = findModelConfig(configs, MODEL_SEED_CODES.asr)
  const imageConfig = findModelConfig(configs, MODEL_SEED_CODES.image, "siliconflow_digital_human")
  const videoConfig = findModelConfig(configs, MODEL_SEED_CODES.seedance)

  if (isDigitalHumanWorkflow(tool)) {
    return [
      { id: "start", position: { x: 48, y: 190 }, data: { title: "Start", subtitle: "用户提交参数", detail: `${tool.inputModality} -> ${tool.outputModality}`, kind: "start", iconName: "play" } },
      { id: "input", position: { x: 340, y: 190 }, data: { title: "参数整理", subtitle: "脚本、形象、场景、时长", detail: tool.configNote || tool.description || "从用户表单收集工作流输入", kind: "input", iconName: "file-input" } },
      { id: "voice", position: { x: 640, y: 42 }, data: { title: "TTS 语音", subtitle: modelLabel(voiceConfig, "FunAudioLLM/CosyVoice2-0.5B"), detail: "把口播脚本生成旁白音频", kind: "model", iconName: "volume-2", config: voiceConfig } },
      { id: "image", position: { x: 640, y: 340 }, data: { title: "形象生图", subtitle: modelLabel(imageConfig, "Tongyi-MAI/Z-Image-Turbo"), detail: "根据形象和场景生成参考图", kind: "model", iconName: "image", config: imageConfig } },
      { id: "video", position: { x: 960, y: 190 }, data: { title: "图生视频", subtitle: modelLabel(videoConfig, "doubao-seedance-1-5-pro-251215"), detail: "Seedance 根据参考图和提示词生成视频", kind: "model", iconName: "video", config: videoConfig } },
      { id: "subtitle", position: { x: 1260, y: 190 }, data: { title: "字幕与合成", subtitle: "FFmpeg 后处理", detail: `ASR 预留: ${modelLabel(asrConfig, "TeleAI/TeleSpeechASR")}`, kind: "tool", iconName: "captions", config: asrConfig } },
      { id: "output", position: { x: 1560, y: 190 }, data: { title: "End", subtitle: "成片渲染和下载", detail: "输出 final.mp4、字幕文件和任务结果", kind: "output", iconName: "sparkles" } },
    ]
  }

  return [
    { id: "start", position: { x: 64, y: 210 }, data: { title: "Start", subtitle: "用户提交表单", detail: `${tool.inputModality} -> ${tool.outputModality}`, kind: "start", iconName: "play" } },
    { id: "input", position: { x: 360, y: 210 }, data: { title: "字段 Schema", subtitle: "参数校验与补全", detail: tool.configNote || "读取工具字段配置并组装参数", kind: "input", iconName: "file-input" } },
    { id: "prompt", position: { x: 660, y: 210 }, data: { title: "隐藏 Prompt", subtitle: "Worker 构造任务提示词", detail: tool.description || "按工具配置生成模型输入", kind: "tool", iconName: "file-text" } },
    { id: "model", position: { x: 960, y: 210 }, data: { title: "模型调用", subtitle: modelLabel(primaryConfig, tool.modelConfigName || tool.modelName || "默认模型配置"), detail: primaryConfig?.provider || "使用当前绑定或默认模型", kind: "model", iconName: "settings", config: primaryConfig } },
    { id: "output", position: { x: 1260, y: 210 }, data: { title: "End", subtitle: "结果渲染", detail: "保存任务结果并展示给用户", kind: "output", iconName: "sparkles" } },
  ]
}

function buildWorkflowEdges(tool: WorkflowTool): WorkflowFlowEdge[] {
  if (isDigitalHumanWorkflow(tool)) {
    return [
      { id: "e-start-input", source: "start", target: "input", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-input-voice", source: "input", target: "voice", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-input-image", source: "input", target: "image", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-voice-video", source: "voice", target: "video", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-image-video", source: "image", target: "video", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-video-subtitle", source: "video", target: "subtitle", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
      { id: "e-subtitle-output", source: "subtitle", target: "output", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
    ]
  }
  return [
    { id: "e-start-input", source: "start", target: "input", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
    { id: "e-input-prompt", source: "input", target: "prompt", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
    { id: "e-prompt-model", source: "prompt", target: "model", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
    { id: "e-model-output", source: "model", target: "output", type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" }, style: { stroke: "#94a3b8", strokeWidth: 2 } },
  ]
}

// ---- Custom ReactFlow Node Component (Dify-style) ----

function WorkflowNodeComponent({ id, data, selected }: NodeProps<WorkflowFlowNode>) {
  const Icon = resolveIcon(data.iconName)
  const colors = kindColorMap[data.kind]

  return (
    <div
      className={cn(
        "group relative rounded-xl border-2 bg-card shadow-md transition-shadow min-w-[220px]",
        selected
          ? "border-primary shadow-lg ring-2 ring-primary/20"
          : "border-border hover:border-primary/30 hover:shadow-lg",
      )}
      style={{ width: NODE_WIDTH }}
    >
      {/* Header bar */}
      <div className={cn("flex items-center gap-2.5 rounded-t-[10px] border-b px-4 py-2.5", colors.bg, colors.border, colors.text)}>
        <div className={cn("flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border", colors.border, colors.bg)}>
          <Icon className="h-3.5 w-3.5" />
        </div>
        <span className="text-sm font-semibold truncate">{data.title}</span>
        <GripVertical className="ml-auto h-3.5 w-3.5 opacity-40 shrink-0" />
      </div>

      {/* Body */}
      <div className="px-4 py-3 space-y-1.5">
        <p className="text-xs font-medium text-card-foreground line-clamp-1">{data.subtitle}</p>
        <p className="text-[11px] leading-4 text-muted-foreground line-clamp-2">{data.detail}</p>
      </div>

      {/* Connection handles */}
      <Handle
        type="target"
        position={Position.Left}
        className={cn("!h-3 !w-3 !border-2 !border-card", colors.handle)}
      />
      <Handle
        type="source"
        position={Position.Right}
        className={cn("!h-3 !w-3 !border-2 !border-card", colors.handle)}
      />
    </div>
  )
}

const nodeTypes = {
  workflowNode: WorkflowNodeComponent,
}

// ---- Main Component ----

export function ToolWorkflowCanvas({
  tool,
  modelConfigs,
  fields = [],
  variant = "embedded",
}: {
  tool: WorkflowTool
  modelConfigs: AgentModelConfig[]
  fields?: ToolField[]
  variant?: "embedded" | "workspace"
}) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null)
  const [reactFlowInstance, setReactFlowInstance] = useState<any>(null)

  const initialNodes = useMemo(() => buildWorkflowNodes(tool, modelConfigs), [tool, modelConfigs])
  const initialEdges = useMemo(() => buildWorkflowEdges(tool), [tool])

  const [nodes, setNodes, onNodesChange] = useNodesState<WorkflowFlowNode>(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState<WorkflowFlowEdge>(initialEdges)
  const [selectedNodeId, setSelectedNodeId] = useState<string>(initialNodes[0]?.id || "")

  // Reset when tool/modelConfigs change
  useEffect(() => {
    setNodes(initialNodes)
    setEdges(initialEdges)
    setSelectedNodeId(initialNodes[0]?.id || "")
  }, [initialNodes, initialEdges, setNodes, setEdges])

  const selectedNode = nodes.find((n) => n.id === selectedNodeId)
  const selectedModels = nodes.filter((n) => n.data.config)

  // ---- Drag & Drop from palette ----

  const onDragOver = useCallback((event: DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = "move"
  }, [])

  const onDrop = useCallback(
    (event: DragEvent) => {
      event.preventDefault()
      const kind = event.dataTransfer.getData("application/reactflow-kind") as WorkflowNodeData["kind"]
      if (!kind || !reactFlowInstance) return

      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })

      const paletteItem = workflowPalette.find((p) => p.kind === kind)
      if (!paletteItem) return

      const nodeId = `${kind}-${Date.now()}`
      const newNode: WorkflowFlowNode = {
        id: nodeId,
        type: "workflowNode",
        position,
        data: {
          title: paletteItem.name,
          subtitle: kind === "model" ? "选择系统模型配置" : paletteItem.description,
          detail: paletteItem.description,
          kind: paletteItem.kind,
          iconName: paletteItem.iconName,
          config: kind === "model" ? null : undefined,
        },
      }

      setNodes((nds: WorkflowFlowNode[]) => {
        if (selectedNodeId && nds.some((n: WorkflowFlowNode) => n.id === selectedNodeId)) {
          const newEdge: WorkflowFlowEdge = {
            id: `e-${selectedNodeId}-${nodeId}`,
            source: selectedNodeId,
            target: nodeId,
            type: "smoothstep",
            markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" },
            style: { stroke: "#94a3b8", strokeWidth: 2 },
          }
          setEdges((eds: WorkflowFlowEdge[]) => [...eds, newEdge])
        }
        return [...nds, newNode]
      })

      setSelectedNodeId(nodeId)
    },
    [reactFlowInstance, selectedNodeId, setNodes, setEdges],
  )

  // ---- Edge connection ----

  const onConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds: WorkflowFlowEdge[]) => [
        ...eds,
        {
          ...connection,
          id: `e-${connection.source}-${connection.target}`,
          type: "smoothstep",
          markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" },
          style: { stroke: "#94a3b8", strokeWidth: 2 },
        } as WorkflowFlowEdge,
      ])
    },
    [setEdges],
  )

  // ---- Node click ----

  const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    setSelectedNodeId(node.id)
  }, [])

  // ---- Inspector handlers ----

  function updateSelectedNode<K extends "title" | "subtitle" | "detail">(key: K, value: string) {
    if (!selectedNode) return
    setNodes((nds: WorkflowFlowNode[]) =>
      nds.map((n: WorkflowFlowNode) =>
        n.id === selectedNode.id
          ? { ...n, data: { ...n.data, [key]: value } }
          : n,
      ),
    )
  }

  function updateSelectedNodeConfig(configId: string) {
    if (!selectedNode || selectedNode.data.kind !== "model") return
    const nextConfig =
      configId === "__unbound"
        ? null
        : modelConfigs.find((c) => String(c.id) === configId) || null
    setNodes((nds: WorkflowFlowNode[]) =>
      nds.map((n: WorkflowFlowNode) =>
        n.id === selectedNode.id
          ? {
              ...n,
              data: {
                ...n.data,
                config: nextConfig,
                subtitle: nextConfig ? modelLabel(nextConfig) : n.data.subtitle,
                detail: nextConfig
                  ? `${nextConfig.provider} · ${nextConfig.configCode || nextConfig.modelName}`
                  : n.data.detail,
              },
            }
          : n,
      ),
    )
  }

  function addWorkflowNode(paletteItem: (typeof workflowPalette)[number]) {
    const anchor = selectedNode
      ? { x: selectedNode.position.x + 310, y: selectedNode.position.y + 30 }
      : { x: 200, y: 210 }
    const nodeId = `${paletteItem.kind}-${Date.now()}`
    const newNode: WorkflowFlowNode = {
      id: nodeId,
      type: "workflowNode",
      position: { x: Math.max(40, anchor.x), y: Math.max(40, anchor.y) },
      data: {
        title: paletteItem.name,
        subtitle: paletteItem.kind === "model" ? "选择系统模型配置" : paletteItem.description,
        detail: paletteItem.description,
        kind: paletteItem.kind,
        iconName: paletteItem.iconName,
        config: paletteItem.kind === "model" ? null : undefined,
      },
    }
    setNodes((nds: WorkflowFlowNode[]) => [...nds, newNode])
    if (selectedNode) {
      const newEdge: WorkflowFlowEdge = {
        id: `e-${selectedNode.id}-${nodeId}`,
        source: selectedNode.id,
        target: nodeId,
        type: "smoothstep",
        markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" },
        style: { stroke: "#94a3b8", strokeWidth: 2 },
      }
      setEdges((eds: WorkflowFlowEdge[]) => [...eds, newEdge])
    }
    setSelectedNodeId(nodeId)
  }

  function resetLayout() {
    setNodes(initialNodes)
    setEdges(initialEdges)
    setSelectedNodeId(initialNodes[0]?.id || "")
  }

  function deleteSelectedNode() {
    if (!selectedNode || selectedNode.data.kind === "start" || selectedNode.data.kind === "output") return
    setNodes((nds: WorkflowFlowNode[]) => nds.filter((n: WorkflowFlowNode) => n.id !== selectedNode.id))
    setEdges((eds: WorkflowFlowEdge[]) =>
      eds.filter((e: WorkflowFlowEdge) => e.source !== selectedNode.id && e.target !== selectedNode.id),
    )
    setSelectedNodeId(nodes[0]?.id || "")
  }

  // ---- Canvas ----

  const canvas = (
    <div className="flex min-w-0 flex-col" ref={reactFlowWrapper}>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-card-foreground">工作流画布</h3>
          <p className="text-xs text-muted-foreground">
            拖拽节点移动 · 滚轮缩放 · 拖拽空白区域平移 · 从左侧面板拖入新节点
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button type="button" size="sm" variant="outline" className="gap-1.5" onClick={resetLayout}>
            <Maximize2 className="h-3.5 w-3.5" />
            重置视图
          </Button>
          {selectedNode && selectedNode.data.kind !== "start" && selectedNode.data.kind !== "output" ? (
            <Button type="button" size="sm" variant="outline" className="gap-1.5 text-destructive hover:text-destructive" onClick={deleteSelectedNode}>
              删除节点
            </Button>
          ) : null}
        </div>
      </div>
      <div
        className={cn(
          "w-full rounded-lg border border-border overflow-hidden",
          variant === "workspace" ? "h-[calc(100vh-200px)]" : "h-[560px]",
        )}
      >
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={onNodeClick}
          onInit={setReactFlowInstance}
          onDragOver={onDragOver}
          onDrop={onDrop}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={{ padding: 0.3 }}
          deleteKeyCode={["Backspace", "Delete"]}
          multiSelectionKeyCode="Shift"
          selectionOnDrag={false}
          panOnDrag={[1, 2]}
          panOnScroll={false}
          zoomOnScroll={true}
          minZoom={0.2}
          maxZoom={2}
          defaultEdgeOptions={{
            type: "smoothstep",
            markerEnd: { type: MarkerType.ArrowClosed, color: "#94a3b8" },
            style: { stroke: "#94a3b8", strokeWidth: 2 },
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
            nodeColor={(n) => {
              const kind = (n.data as WorkflowNodeData)?.kind
              if (!kind) return "hsl(var(--muted-foreground))"
              const colors = kindColorMap[kind]
              const map: Record<string, string> = { emerald: "#10b981", slate: "#64748b", blue: "#3b82f6", amber: "#f59e0b", rose: "#ef4444" }
              for (const [k, v] of Object.entries(map)) {
                if (colors.text.includes(k)) return v
              }
              return "#94a3b8"
            }}
            maskColor="hsl(var(--background) / 0.7)"
          />
        </ReactFlow>
      </div>
    </div>
  )

  // ---- Inspector panel ----

  const inspector = (
    <aside className="space-y-3 overflow-y-auto max-h-[calc(100vh-200px)]">
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">节点编辑</p>
        {selectedNode ? (
          <div className="mt-3 space-y-3">
            <div className="flex items-center gap-2">
              <Badge variant="secondary" className="capitalize">{selectedNode.data.kind}</Badge>
              <Badge variant="outline">{selectedNode.data.title}</Badge>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">标题</label>
              <Input
                value={selectedNode.data.title}
                onChange={(e) => updateSelectedNode("title", e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">副标题</label>
              <Input
                value={selectedNode.data.subtitle}
                onChange={(e) => updateSelectedNode("subtitle", e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">说明</label>
              <Textarea
                value={selectedNode.data.detail}
                onChange={(e) => updateSelectedNode("detail", e.target.value)}
                className="min-h-20 text-xs"
              />
            </div>
            {selectedNode.data.kind === "model" ? (
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-muted-foreground">系统模型配置</label>
                <Select
                  value={selectedNode.data.config?.id ? String(selectedNode.data.config.id) : "__unbound"}
                  onValueChange={updateSelectedNodeConfig}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="选择系统配置里的模型" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__unbound">不绑定模型</SelectItem>
                    {modelConfigs.map((c) => (
                      <SelectItem key={c.id} value={String(c.id)}>
                        {modelConfigOptionLabel(c)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-[11px] leading-4 text-muted-foreground">
                  读取管理端"系统配置"中的模型，选择后绑定到当前工作流节点。
                </p>
              </div>
            ) : null}
          </div>
        ) : (
          <p className="mt-3 text-xs text-muted-foreground">点击画布中的节点进行编辑。</p>
        )}
      </div>

      {/* Selected node model info */}
      {selectedNode?.data.config ? (
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-semibold text-card-foreground">节点模型</p>
          <div className="mt-3 space-y-2 text-xs">
            <div className="flex justify-between gap-3">
              <span className="text-muted-foreground">Provider</span>
              <span className="text-right font-medium">{selectedNode.data.config.provider}</span>
            </div>
            <div className="flex justify-between gap-3">
              <span className="text-muted-foreground">Model</span>
              <span className="text-right font-medium">{selectedNode.data.config.modelName}</span>
            </div>
            <div className="flex justify-between gap-3">
              <span className="text-muted-foreground">Config Code</span>
              <span className="text-right font-mono text-[11px]">{selectedNode.data.config.configCode || "-"}</span>
            </div>
          </div>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {(selectedNode.data.config.capabilities || []).filter(Boolean).map((cap) => (
              <Badge key={cap} variant="secondary" className="text-[10px]">{cap}</Badge>
            ))}
          </div>
        </div>
      ) : null}

      {/* Tool fields */}
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">工具字段</p>
        <div className="mt-3 max-h-52 space-y-2 overflow-y-auto pr-1">
          {fields.length > 0 ? (
            fields.map((field) => (
              <div key={field.fieldKey} className="rounded-md border border-border/70 bg-secondary/40 px-3 py-2">
                <div className="flex items-center justify-between gap-2">
                  <p className="truncate text-xs font-medium text-card-foreground">{field.fieldName}</p>
                  {field.required ? (
                    <Badge variant="outline" className="text-[10px]">必填</Badge>
                  ) : null}
                </div>
                <p className="mt-1 font-mono text-[11px] text-muted-foreground">
                  {field.fieldKey} · {field.fieldType}
                </p>
              </div>
            ))
          ) : (
            <p className="text-xs text-muted-foreground">当前没有读取到字段 Schema。</p>
          )}
        </div>
      </div>

      {/* Model node list */}
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">已识别模型节点</p>
        <div className="mt-3 space-y-2">
          {selectedModels.length > 0 ? (
            selectedModels.map((n) => (
              <button
                key={n.id}
                type="button"
                className={cn(
                  "w-full rounded-md border border-border/70 bg-secondary/40 p-3 text-left transition hover:border-primary/40",
                  selectedNodeId === n.id && "border-primary ring-1 ring-primary/20",
                )}
                onClick={() => setSelectedNodeId(n.id)}
              >
                <div className="flex items-center justify-between gap-3">
                  <p className="text-xs font-medium text-card-foreground">{n.data.title}</p>
                  <Badge variant={n.data.config ? "secondary" : "destructive"} className="text-[10px]">
                    {n.data.config ? "已配置" : "未配置"}
                  </Badge>
                </div>
                <p className="mt-1 break-all text-xs text-muted-foreground">{n.data.subtitle}</p>
              </button>
            ))
          ) : (
            <p className="text-xs text-muted-foreground">该工具当前只展示主模型节点。</p>
          )}
        </div>
      </div>
    </aside>
  )

  // ---- Node palette (Dify-style left sidebar) ----

  const palette = (
    <aside className="space-y-3">
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">节点库</p>
        <p className="mt-0.5 text-xs text-muted-foreground">拖拽或点击添加到画布</p>
        <div className="mt-3 space-y-1.5">
          {workflowPalette.map((item) => {
            const Icon = item.icon
            return (
              <div
                key={item.kind}
                draggable
                onDragStart={(event) => {
                  event.dataTransfer.setData("application/reactflow-kind", item.kind)
                  event.dataTransfer.effectAllowed = "move"
                }}
                onClick={() => addWorkflowNode(item)}
                className="group flex cursor-grab items-center gap-3 rounded-lg border border-border/70 bg-secondary/30 px-3 py-2.5 transition active:cursor-grabbing hover:border-primary/30 hover:bg-primary/5 active:scale-[0.98]"
              >
                <div
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg"
                  style={{ backgroundColor: `${item.color}18`, color: item.color }}
                >
                  <Icon className="h-4 w-4" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-medium text-card-foreground">{item.name}</p>
                  <p className="truncate text-[11px] leading-4 text-muted-foreground">{item.description}</p>
                </div>
                <Plus className="h-3.5 w-3.5 shrink-0 text-muted-foreground opacity-0 transition group-hover:opacity-100" />
              </div>
            )
          })}
        </div>
      </div>

      {/* Tool info card */}
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">{tool.name}</p>
        <p className="mt-1 text-xs leading-5 text-muted-foreground">{tool.description || "暂无描述"}</p>
        <div className="mt-3 flex flex-wrap gap-2">
          <Badge variant="secondary">{tool.toolCode}</Badge>
          <Badge variant="outline">{tool.executionHandler || tool.toolType}</Badge>
        </div>
      </div>

      {/* Quick tips */}
      <div className="rounded-lg border border-border bg-card p-4">
        <p className="text-sm font-semibold text-card-foreground">操作提示</p>
        <ul className="mt-2 space-y-1.5 text-xs text-muted-foreground">
          <li>· 拖拽节点库中的块到画布</li>
          <li>· 点击节点库中的块快速添加</li>
          <li>· 鼠标拖拽空白区域平移画布</li>
          <li>· 滚轮缩放画布</li>
          <li>· 拖拽节点调整位置</li>
          <li>· 选中节点后在右侧编辑</li>
        </ul>
      </div>
    </aside>
  )

  // ---- Layout ----

  if (variant === "workspace") {
    return (
      <div className="grid gap-4 xl:grid-cols-[260px_minmax(0,1fr)_320px]">
        {palette}
        {canvas}
        {inspector}
      </div>
    )
  }

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
      {canvas}
      {inspector}
    </div>
  )
}
