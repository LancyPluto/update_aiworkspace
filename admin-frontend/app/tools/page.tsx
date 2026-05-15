"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import {
  Copy,
  FileText,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  ShoppingBag,
  Sparkles,
  Store,
  Trash2,
  Video,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import {
  createTool,
  fetchAdminTools,
  fetchToolCategories,
  fetchToolFields,
  offlineTool,
  publishTool,
  updateTool,
  updateToolFields,
} from "@/lib/api/tools"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { ApiError } from "@/lib/api/http"
import type { AgentModelConfig, ToolCategory, ToolField, ToolFieldPayload, ToolSummary } from "@/lib/api/types"

interface ToolRow {
  id: string
  rawId: number
  toolCode: string
  name: string
  description: string
  category: string
  categoryId: number | null
  toolType: string
  inputModality: string
  outputModality: string
  configNote: string | null
  icon: LucideIcon
  credits: number
  status: boolean
  rawStatus: string
  modelConfigId: number | null
  modelConfigName: string | null
  modelName: string | null
}

interface ToolForm {
  toolCode: string
  toolName: string
  description: string
  categoryId: string
  toolType: string
  inputModality: string
  outputModality: string
  configNote: string
  estimatedCreditCost: string
  modelConfigId: string
}

const initialForm: ToolForm = {
  toolCode: "",
  toolName: "",
  description: "",
  categoryId: "",
  toolType: "TEXT_GENERATION",
  inputModality: "TEXT",
  outputModality: "TEXT",
  configNote: "",
  estimatedCreditCost: "5",
  modelConfigId: "",
}

const toolTypeOptions = [
  { value: "TEXT_GENERATION", label: "文本生成", hint: "文案、摘要、客服回复等文本类工具" },
  { value: "IMAGE_GENERATION", label: "文生图", hint: "输入提示词，输出图片" },
  { value: "IMAGE_TO_IMAGE", label: "图生图", hint: "输入参考图或原图，输出图片" },
  { value: "IMAGE_UNDERSTANDING", label: "图片理解", hint: "输入图片，输出识别/分析文本" },
  { value: "SPEECH_TO_TEXT", label: "语音转文字", hint: "输入音频，输出文本" },
  { value: "TEXT_TO_SPEECH", label: "文字转语音", hint: "输入文本，输出音频" },
  { value: "VIDEO_GENERATION", label: "视频生成", hint: "输入文本/素材，输出视频" },
  { value: "EMBEDDING", label: "Embedding", hint: "向量化，输出结构化 JSON" },
  { value: "RERANK", label: "Rerank", hint: "重排序，输出结构化 JSON" },
  { value: "AGENT", label: "Agent 编排", hint: "多步骤规划和工具调用" },
]

const modalityOptions = [
  { value: "TEXT", label: "文本" },
  { value: "IMAGE", label: "图片" },
  { value: "AUDIO", label: "音频" },
  { value: "VIDEO", label: "视频" },
  { value: "JSON", label: "JSON" },
  { value: "FILE", label: "文件" },
  { value: "MULTIMODAL", label: "多模态" },
]

const defaultModalitiesByType: Record<string, { input: string; output: string }> = {
  TEXT_GENERATION: { input: "TEXT", output: "TEXT" },
  IMAGE_GENERATION: { input: "TEXT", output: "IMAGE" },
  IMAGE_TO_IMAGE: { input: "IMAGE", output: "IMAGE" },
  IMAGE_UNDERSTANDING: { input: "IMAGE", output: "TEXT" },
  SPEECH_TO_TEXT: { input: "AUDIO", output: "TEXT" },
  TEXT_TO_SPEECH: { input: "TEXT", output: "AUDIO" },
  VIDEO_GENERATION: { input: "TEXT", output: "VIDEO" },
  EMBEDDING: { input: "TEXT", output: "JSON" },
  RERANK: { input: "TEXT", output: "JSON" },
  AGENT: { input: "MULTIMODAL", output: "TEXT" },
}

function optionLabel(options: Array<{ value: string; label: string }>, value?: string | null) {
  return options.find((item) => item.value === value)?.label || value || "-"
}

function optionsJson(options: Array<string | { label: string; value: string }>) {
  return JSON.stringify(options.map((option) => (typeof option === "string" ? { label: option, value: option } : option)))
}

const toolFieldTemplates: Record<string, ToolFieldPayload[]> = {
  TEXT_GENERATION: [
    { fieldKey: "topic", fieldName: "主题", fieldType: "textarea", placeholder: "说明要生成的内容主题、产品或场景", required: true, sortOrder: 1 },
    { fieldKey: "tone", fieldName: "语气风格", fieldType: "select", optionsJson: optionsJson(["专业", "亲切", "种草", "高级", "幽默"]), required: true, sortOrder: 2 },
    { fieldKey: "length", fieldName: "字数", fieldType: "number", placeholder: "例如 200", required: false, sortOrder: 3 },
    { fieldKey: "requirements", fieldName: "补充要求", fieldType: "textarea", placeholder: "禁用词、必须包含的信息、目标人群等", required: false, sortOrder: 4 },
  ],
  IMAGE_GENERATION: [
    { fieldKey: "prompt", fieldName: "画面描述", fieldType: "textarea", placeholder: "描述主体、场景、光线、构图和细节", required: true, sortOrder: 1 },
    { fieldKey: "aspectRatio", fieldName: "画面比例", fieldType: "radio", optionsJson: optionsJson(["1:1", "4:3", "3:4", "16:9", "9:16"]), required: true, sortOrder: 2 },
    { fieldKey: "style", fieldName: "风格", fieldType: "select", optionsJson: optionsJson(["写实", "电商", "插画", "动漫", "极简", "国潮"]), required: false, sortOrder: 3 },
    { fieldKey: "count", fieldName: "生成数量", fieldType: "number", placeholder: "例如 1", required: false, sortOrder: 4 },
    { fieldKey: "negativePrompt", fieldName: "反向提示词", fieldType: "textarea", placeholder: "不希望出现的元素", required: false, sortOrder: 5 },
  ],
  IMAGE_TO_IMAGE: [
    { fieldKey: "referenceImage", fieldName: "参考图 URL", fieldType: "image", placeholder: "粘贴图片 URL；文件上传将在后续接入", required: true, sortOrder: 1 },
    { fieldKey: "prompt", fieldName: "修改要求", fieldType: "textarea", placeholder: "说明要保留和修改的部分", required: true, sortOrder: 2 },
    { fieldKey: "strength", fieldName: "改动强度", fieldType: "slider", placeholder: "0-100", required: false, sortOrder: 3 },
    { fieldKey: "aspectRatio", fieldName: "画面比例", fieldType: "radio", optionsJson: optionsJson(["保持原图", "1:1", "4:3", "3:4", "16:9", "9:16"]), required: false, sortOrder: 4 },
  ],
  IMAGE_UNDERSTANDING: [
    { fieldKey: "imageUrl", fieldName: "图片 URL", fieldType: "image", placeholder: "粘贴需要分析的图片 URL；文件上传将在后续接入", required: true, sortOrder: 1 },
    { fieldKey: "question", fieldName: "分析问题", fieldType: "textarea", placeholder: "例如：识别商品卖点并生成标题", required: true, sortOrder: 2 },
  ],
  SPEECH_TO_TEXT: [
    { fieldKey: "audioUrl", fieldName: "音频 URL", fieldType: "file", placeholder: "粘贴音频文件 URL；文件上传将在后续接入", required: true, sortOrder: 1 },
    { fieldKey: "language", fieldName: "语言", fieldType: "select", optionsJson: optionsJson(["中文", "英文", "自动识别"]), required: false, sortOrder: 2 },
    { fieldKey: "punctuation", fieldName: "自动标点", fieldType: "checkbox", required: false, sortOrder: 3 },
  ],
  TEXT_TO_SPEECH: [
    { fieldKey: "text", fieldName: "朗读文本", fieldType: "textarea", placeholder: "输入需要转语音的内容", required: true, sortOrder: 1 },
    { fieldKey: "voice", fieldName: "音色", fieldType: "select", optionsJson: optionsJson(["女声", "男声", "童声", "沉稳", "活泼"]), required: false, sortOrder: 2 },
    { fieldKey: "speed", fieldName: "语速", fieldType: "slider", placeholder: "0-100", required: false, sortOrder: 3 },
  ],
  VIDEO_GENERATION: [
    { fieldKey: "prompt", fieldName: "视频描述", fieldType: "textarea", placeholder: "描述镜头、主体、动作、风格和时长", required: true, sortOrder: 1 },
    { fieldKey: "aspectRatio", fieldName: "视频比例", fieldType: "radio", optionsJson: optionsJson(["16:9", "9:16", "1:1"]), required: true, sortOrder: 2 },
    { fieldKey: "duration", fieldName: "时长秒数", fieldType: "number", placeholder: "例如 5", required: false, sortOrder: 3 },
  ],
  EMBEDDING: [
    { fieldKey: "text", fieldName: "向量化文本", fieldType: "textarea", placeholder: "输入需要转向量的文本", required: true, sortOrder: 1 },
  ],
  RERANK: [
    { fieldKey: "query", fieldName: "查询语句", fieldType: "textarea", required: true, sortOrder: 1 },
    { fieldKey: "documents", fieldName: "候选文本", fieldType: "textarea", placeholder: "每行一条候选内容", required: true, sortOrder: 2 },
  ],
  AGENT: [
    { fieldKey: "goal", fieldName: "任务目标", fieldType: "textarea", placeholder: "说明希望 Agent 完成什么", required: true, sortOrder: 1 },
    { fieldKey: "constraints", fieldName: "限制条件", fieldType: "textarea", placeholder: "预算、风格、不能做的事等", required: false, sortOrder: 2 },
  ],
}

const CATEGORY_ICON_MAP: Record<string, LucideIcon> = {
  Copywriting: FileText,
  Content: FileText,
  Video,
  Ecommerce: ShoppingBag,
  Sales: MessageSquare,
  Store,
}

function pickIcon(categoryName?: string | null): LucideIcon {
  if (!categoryName) return Sparkles
  return CATEGORY_ICON_MAP[categoryName] || Sparkles
}

function mapTool(tool: ToolSummary): ToolRow {
  return {
    id: String(tool.id),
    rawId: tool.id,
    toolCode: tool.toolCode,
    name: tool.toolName,
    description: tool.description || "No description",
    category: tool.categoryName || "Uncategorized",
    categoryId: tool.categoryId ?? null,
    toolType: tool.toolType || "TEXT_GENERATION",
    inputModality: tool.inputModality || "TEXT",
    outputModality: tool.outputModality || "TEXT",
    configNote: tool.configNote || null,
    icon: pickIcon(tool.categoryName),
    credits: tool.estimatedCreditCost ?? 0,
    status: (tool.status || "").toUpperCase() === "ONLINE",
    rawStatus: tool.status,
    modelConfigId: tool.modelConfigId ?? null,
    modelConfigName: tool.modelConfigName || null,
    modelName: tool.modelName || null,
  }
}

export default function ToolsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<ToolRow | null>(null)
  const [toolList, setToolList] = useState<ToolRow[]>([])
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [togglingId, setTogglingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState<ToolForm>(initialForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldDialogOpen, setFieldDialogOpen] = useState(false)
  const [fieldTool, setFieldTool] = useState<ToolRow | null>(null)
  const [fieldJson, setFieldJson] = useState("[]")
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [fieldLoading, setFieldLoading] = useState(false)
  const [fieldSaving, setFieldSaving] = useState(false)

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [toolsResp, cats] = await Promise.all([
        fetchAdminTools(),
        fetchToolCategories().catch(() => [] as ToolCategory[]),
      ])
      setToolList(toolsResp.list.map(mapTool))
      setCategories(cats)
      const configs = await fetchAgentModelConfigs().catch(() => [] as AgentModelConfig[])
      setModelConfigs(configs.filter((config) => config.enabled !== false))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load tools")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return toolList
    return toolList.filter((tool) =>
      [tool.name, tool.description, tool.category].some((value) =>
        value.toLowerCase().includes(keyword),
      ),
    )
  }, [toolList, searchQuery])

  async function toggleToolStatus(id: string) {
    const target = toolList.find((tool) => tool.id === id)
    if (!target) return
    setTogglingId(target.rawId)
    try {
      const updated = target.status
        ? await offlineTool(target.rawId)
        : await publishTool(target.rawId)
      setToolList((prev) => prev.map((tool) => (tool.id === id ? mapTool(updated) : tool)))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "Failed to update tool status"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setTogglingId(null)
    }
  }

  function updateForm<K extends keyof ToolForm>(key: K, value: ToolForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function updateToolType(value: string) {
    const defaults = defaultModalitiesByType[value] || defaultModalitiesByType.TEXT_GENERATION
    setForm((prev) => ({
      ...prev,
      toolType: value,
      inputModality: defaults.input,
      outputModality: defaults.output,
    }))
  }

  function openEditDialog(tool: ToolRow) {
    setEditingTool(tool)
    setForm({
      toolCode: tool.toolCode,
      toolName: tool.name,
      description: tool.description === "No description" ? "" : tool.description,
      categoryId: tool.categoryId ? String(tool.categoryId) : "",
      toolType: tool.toolType,
      inputModality: tool.inputModality,
      outputModality: tool.outputModality,
      configNote: tool.configNote || "",
      estimatedCreditCost: String(tool.credits),
      modelConfigId: tool.modelConfigId ? String(tool.modelConfigId) : "",
    })
    setFormError(null)
    setIsAddDialogOpen(true)
  }

  async function handleSaveTool() {
    setFormError(null)
    if (!form.toolName.trim()) {
      setFormError("Tool name is required")
      return
    }
    if (!form.categoryId) {
      setFormError("Category is required")
      return
    }
    const credits = Number(form.estimatedCreditCost)
    if (!Number.isFinite(credits) || credits < 0) {
      setFormError("Credit cost must be a non-negative number")
      return
    }
    setSubmitting(true)
    try {
      const payload = {
        toolCode: form.toolCode.trim() || undefined,
        toolName: form.toolName.trim(),
        categoryId: Number(form.categoryId),
        description: form.description.trim() || undefined,
        toolType: form.toolType,
        inputModality: form.inputModality,
        outputModality: form.outputModality,
        configNote: form.configNote.trim() || undefined,
        estimatedCreditCost: Math.floor(credits),
        modelConfigId: form.modelConfigId ? Number(form.modelConfigId) : null,
      }
      if (editingTool) {
        const updated = await updateTool(editingTool.rawId, payload)
        setToolList((prev) => prev.map((tool) => (tool.rawId === editingTool.rawId ? mapTool(updated) : tool)))
      } else {
        const created = await createTool(payload)
        const published = await publishTool(created.id)
        setToolList((prev) => [mapTool(published), ...prev])
      }
      setForm(initialForm)
      setEditingTool(null)
      setIsAddDialogOpen(false)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "Failed to save tool")
    } finally {
      setSubmitting(false)
    }
  }

  async function openFieldDialog(tool: ToolRow) {
    setFieldTool(tool)
    setFieldDialogOpen(true)
    setFieldError(null)
    setFieldLoading(true)
    try {
      const fields = await fetchToolFields(tool.rawId)
      setFieldJson(JSON.stringify(fields.map(fieldToPayload), null, 2))
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "Failed to load fields")
      setFieldJson("[]")
    } finally {
      setFieldLoading(false)
    }
  }

  function applyFieldTemplate() {
    if (!fieldTool) return
    const template = toolFieldTemplates[fieldTool.toolType] || toolFieldTemplates.TEXT_GENERATION
    setFieldJson(JSON.stringify(template, null, 2))
    setFieldError(null)
  }

  async function saveFields() {
    if (!fieldTool) return
    setFieldError(null)
    let fields: ToolFieldPayload[]
    try {
      const parsed = JSON.parse(fieldJson)
      if (!Array.isArray(parsed)) throw new Error("Field schema must be a JSON array")
      fields = parsed.map(normalizeFieldPayload)
    } catch (err) {
      setFieldError(err instanceof Error ? err.message : "Invalid field JSON")
      return
    }
    setFieldSaving(true)
    try {
      const saved = await updateToolFields(fieldTool.rawId, fields)
      setFieldJson(JSON.stringify(saved.map(fieldToPayload), null, 2))
      setFieldDialogOpen(false)
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "Failed to save fields")
    } finally {
      setFieldSaving(false)
    }
  }

  const headerDescription = error
    ? `Load failed: ${error}`
    : loading
      ? "Loading tool list..."
      : "Manage AI tools, tool fields, and publishing status."

  return (
    <AdminLayout>
      <AdminHeader title="AI Tool Management" description={headerDescription} />

      <div className="space-y-6 p-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search tools..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <Dialog
            open={isAddDialogOpen}
            onOpenChange={(open) => {
              setIsAddDialogOpen(open)
              if (!open) {
                setForm(initialForm)
                setEditingTool(null)
                setFormError(null)
              }
            }}
          >
            <DialogTrigger asChild>
              <Button className="gap-2">
                <Plus className="h-4 w-4" />
                Add Tool
              </Button>
            </DialogTrigger>
            <DialogContent className="bg-card border-border max-w-lg">
              <DialogHeader>
                <DialogTitle>{editingTool ? "Edit AI Tool" : "Add AI Tool"}</DialogTitle>
                <DialogDescription>{formError || "Configure the tool and its model binding."}</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>Tool name</Label>
                  <Input value={form.toolName} onChange={(event) => updateForm("toolName", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>Description</Label>
                  <Textarea value={form.description} onChange={(event) => updateForm("description", event.target.value)} />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>Category</Label>
                    <Select value={form.categoryId} onValueChange={(value) => updateForm("categoryId", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="Select category" />
                      </SelectTrigger>
                      <SelectContent>
                        {categories.map((cat) => (
                          <SelectItem key={cat.id} value={String(cat.id)}>
                            {cat.categoryName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>Credits</Label>
                    <Input
                      type="number"
                      value={form.estimatedCreditCost}
                      onChange={(event) => updateForm("estimatedCreditCost", event.target.value)}
                    />
                  </div>
                </div>
                <div className="space-y-2 rounded-lg border border-border bg-secondary/30 p-3">
                  <Label>模型能力类型</Label>
                  <Select value={form.toolType} onValueChange={updateToolType}>
                    <SelectTrigger>
                      <SelectValue placeholder="选择工具能力" />
                    </SelectTrigger>
                    <SelectContent>
                      {toolTypeOptions.map((item) => (
                        <SelectItem key={item.value} value={item.value}>
                          {item.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <p className="text-xs text-muted-foreground">
                    {toolTypeOptions.find((item) => item.value === form.toolType)?.hint}
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>输入模态</Label>
                    <Select value={form.inputModality} onValueChange={(value) => updateForm("inputModality", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="选择输入类型" />
                      </SelectTrigger>
                      <SelectContent>
                        {modalityOptions.map((item) => (
                          <SelectItem key={item.value} value={item.value}>
                            {item.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>输出模态</Label>
                    <Select value={form.outputModality} onValueChange={(value) => updateForm("outputModality", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="选择输出类型" />
                      </SelectTrigger>
                      <SelectContent>
                        {modalityOptions.map((item) => (
                          <SelectItem key={item.value} value={item.value}>
                            {item.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </div>
                <div className="space-y-2">
                  <Label>配置说明</Label>
                  <Textarea
                    value={form.configNote}
                    onChange={(event) => updateForm("configNote", event.target.value)}
                    placeholder="给管理员看的补充说明，例如文生图需要尺寸、数量、风格、反向提示词等字段。"
                  />
                </div>
                <div className="space-y-2">
                  <Label>Tool code</Label>
                  <Input
                    value={form.toolCode}
                    onChange={(event) => updateForm("toolCode", event.target.value)}
                    placeholder="Optional"
                    disabled={Boolean(editingTool)}
                  />
                </div>
                <div className="space-y-2">
                  <Label>Model config</Label>
                  <Select value={form.modelConfigId || "default"} onValueChange={(value) => updateForm("modelConfigId", value === "default" ? "" : value)}>
                    <SelectTrigger>
                      <SelectValue placeholder="Use default model config" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="default">Use default model config</SelectItem>
                      {modelConfigs.map((config) => (
                        <SelectItem key={config.id} value={String(config.id)}>
                          {config.displayName || config.modelName} · {config.provider}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setIsAddDialogOpen(false)} disabled={submitting}>
                  Cancel
                </Button>
                <Button onClick={handleSaveTool} disabled={submitting}>
                  {submitting ? "Saving..." : editingTool ? "Save Tool" : "Create Tool"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {filteredTools.map((tool) => (
            <div
              key={tool.id}
              className={cn(
                "group relative overflow-hidden rounded-2xl border border-border bg-card p-6 transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5",
                !tool.status && "opacity-60",
              )}
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
                    <tool.icon className="h-5 w-5 text-primary" />
                  </div>
                  <div>
                    <h3 className="font-semibold text-card-foreground">{tool.name}</h3>
                    <Badge variant="secondary" className="mt-1 text-xs font-normal">{tool.category}</Badge>
                  </div>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-8 w-8 opacity-0 transition-opacity group-hover:opacity-100">
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="bg-card border-border">
                    <DropdownMenuItem className="gap-2" onClick={() => openEditDialog(tool)}>
                      <Pencil className="h-4 w-4" /> Edit
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" onClick={() => openFieldDialog(tool)}>
                      <FileText className="h-4 w-4" /> Fields
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" disabled>
                      <Copy className="h-4 w-4" /> Duplicate
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2 text-destructive" disabled>
                      <Trash2 className="h-4 w-4" /> Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>

              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{tool.description}</p>

              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-1 text-muted-foreground">
                    <Sparkles className="h-4 w-4" />
                    <span>{tool.credits} credits</span>
                  </div>
                  <div className="text-muted-foreground">{tool.rawStatus}</div>
                </div>
                <Switch checked={tool.status} disabled={togglingId === tool.rawId} onCheckedChange={() => toggleToolStatus(tool.id)} />
              </div>
              <div className="mt-3 flex flex-wrap gap-2 text-xs">
                <Badge variant="outline">{optionLabel(toolTypeOptions, tool.toolType)}</Badge>
                <Badge variant="secondary">
                  {optionLabel(modalityOptions, tool.inputModality)} → {optionLabel(modalityOptions, tool.outputModality)}
                </Badge>
              </div>
              <div className="mt-3 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
                Model: {tool.modelConfigName || tool.modelName || "Default model config"}
              </div>
            </div>
          ))}
        </div>
      </div>

      <Dialog open={fieldDialogOpen} onOpenChange={setFieldDialogOpen}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto bg-card border-border">
          <DialogHeader>
            <DialogTitle>Field Schema</DialogTitle>
            <DialogDescription>
              {fieldTool ? `${fieldTool.name} user-facing form fields.` : "Configure tool fields."}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            {fieldTool ? (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-secondary/30 p-3">
                <div>
                  <p className="text-sm font-medium">通用字段模板</p>
                  <p className="text-xs text-muted-foreground">
                    当前类型：{optionLabel(toolTypeOptions, fieldTool.toolType)}。模板使用平台通用字段，不绑定具体供应商 API。
                  </p>
                </div>
                <Button type="button" variant="outline" size="sm" onClick={applyFieldTemplate} disabled={fieldLoading || fieldSaving}>
                  应用模板
                </Button>
              </div>
            ) : null}
            {fieldError ? (
              <div className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                {fieldError}
              </div>
            ) : null}
            <Textarea
              value={fieldJson}
              onChange={(event) => setFieldJson(event.target.value)}
              className="min-h-[420px] font-mono text-xs"
              disabled={fieldLoading || fieldSaving}
              placeholder='[{"fieldKey":"productName","fieldName":"Product name","fieldType":"TEXT","required":true,"sortOrder":1}]'
            />
            <p className="text-xs text-muted-foreground">
              Supported fieldType: text, textarea, select, number, radio, checkbox, slider, image, file.
            </p>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFieldDialogOpen(false)} disabled={fieldSaving}>
              Cancel
            </Button>
            <Button onClick={saveFields} disabled={fieldLoading || fieldSaving}>
              {fieldSaving ? "Saving..." : "Save fields"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}

function fieldToPayload(field: ToolField): ToolFieldPayload {
  return {
    fieldKey: field.fieldKey,
    fieldName: field.fieldName,
    fieldType: field.fieldType,
    placeholder: field.placeholder || "",
    optionsJson: field.optionsJson || "",
    required: field.required !== false,
    sortOrder: field.sortOrder ?? 1,
  }
}

function normalizeFieldPayload(field: Partial<ToolFieldPayload>, index: number): ToolFieldPayload {
  if (!field.fieldKey || !field.fieldName || !field.fieldType) {
    throw new Error(`Field ${index + 1} must include fieldKey, fieldName, and fieldType`)
  }
  return {
    fieldKey: String(field.fieldKey).trim(),
    fieldName: String(field.fieldName).trim(),
    fieldType: String(field.fieldType).trim(),
    placeholder: field.placeholder ? String(field.placeholder) : "",
    optionsJson: field.optionsJson ? String(field.optionsJson) : undefined,
    required: field.required !== false,
    sortOrder: Number(field.sortOrder ?? index + 1),
  }
}
