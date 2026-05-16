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
import { applyToolTemplate, fetchToolTemplates, type ToolTemplateSummary } from "@/lib/api/tool-templates"
import { FieldSchemaEditor } from "@/components/admin/field-schema-editor"
import {
  editableFromToolField,
  parseFieldsJson,
  serializeFields,
  toFieldPayload,
  type EditableField,
} from "@/lib/tool-fields"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
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
  executionHandler?: string | null
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
  templateCode: string
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
  templateCode: "text_generation_default",
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

function executionCapabilityForTool(toolType: string, toolCode: string, executionHandler?: string | null): string {
  const eh = executionHandler?.trim()
  if (eh) return eh.toUpperCase()
  const code = (toolCode || "").trim()
  if (code === "digital_human_agent") return "DIGITAL_HUMAN"
  return (toolType || "TEXT_GENERATION").toUpperCase()
}

function modelConfigSupportsCapability(config: AgentModelConfig, capability: string): boolean {
  const caps = config.capabilities
  if (!caps || caps.length === 0) return true
  const want = capability.toUpperCase()
  return caps.some((c) => (c || "").toUpperCase() === want)
}

function optionLabel(options: Array<{ value: string; label: string }>, value?: string | null) {
  return options.find((item) => item.value === value)?.label || value || "-"
}

const templateCodeByToolType: Record<string, string> = {
  TEXT_GENERATION: "text_generation_default",
  IMAGE_GENERATION: "image_generation_default",
  VIDEO_GENERATION: "video_generation_default",
  AGENT: "digital_human_default",
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
    executionHandler: tool.executionHandler ?? null,
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
  const [fieldEditorMode, setFieldEditorMode] = useState<"visual" | "json">("visual")
  const [editableFields, setEditableFields] = useState<EditableField[]>([])
  const [toolTemplates, setToolTemplates] = useState<ToolTemplateSummary[]>([])

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
      const templates = await fetchToolTemplates().catch(() => [] as ToolTemplateSummary[])
      setToolTemplates(templates)
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

  const requiredModelCapability = useMemo(() => {
    const code = editingTool?.toolCode ?? form.toolCode ?? ""
    const type = editingTool?.toolType ?? form.toolType
    const eh = editingTool?.executionHandler ?? null
    return executionCapabilityForTool(type, code, eh)
  }, [editingTool, form.toolType, form.toolCode])

  const filteredModelConfigs = useMemo(
    () => modelConfigs.filter((c) => modelConfigSupportsCapability(c, requiredModelCapability)),
    [modelConfigs, requiredModelCapability],
  )

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
      templateCode: templateCodeByToolType[value] || prev.templateCode,
    }))
  }

  function applyTemplateToForm(templateCode: string) {
    const template = toolTemplates.find((item) => item.templateCode === templateCode)
    if (!template) return
    setForm((prev) => ({
      ...prev,
      templateCode,
      toolType: template.toolType,
      inputModality: template.inputModality,
      outputModality: template.outputModality,
      configNote: template.configNote || "",
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
      templateCode: "",
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
        templateCode: !editingTool && form.templateCode ? form.templateCode : undefined,
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

  function applyLoadedFields(fields: ToolField[]) {
    const editable = fields.map((field, index) => editableFromToolField(field, index))
    setEditableFields(editable)
    setFieldJson(serializeFields(editable))
  }

  async function openFieldDialog(tool: ToolRow) {
    setFieldTool(tool)
    setFieldDialogOpen(true)
    setFieldEditorMode("visual")
    setFieldError(null)
    setFieldLoading(true)
    try {
      const fields = await fetchToolFields(tool.rawId)
      applyLoadedFields(fields)
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "Failed to load fields")
      setEditableFields([])
      setFieldJson("[]")
    } finally {
      setFieldLoading(false)
    }
  }

  function switchFieldEditorMode(mode: "visual" | "json") {
    if (mode === fieldEditorMode) return
    try {
      if (mode === "json") {
        setFieldJson(serializeFields(editableFields))
      } else {
        setEditableFields(parseFieldsJson(fieldJson))
      }
      setFieldEditorMode(mode)
      setFieldError(null)
    } catch (err) {
      setFieldError(err instanceof Error ? err.message : "字段配置格式错误")
    }
  }

  async function applyFieldTemplate() {
    if (!fieldTool) return
    const templateCode =
      toolTemplates.find((item) => item.toolType === fieldTool.toolType)?.templateCode
      || templateCodeByToolType[fieldTool.toolType]
      || "text_generation_default"
    setFieldSaving(true)
    setFieldError(null)
    try {
      await applyToolTemplate(fieldTool.rawId, {
        templateCode,
        applyMetadata: false,
        overwritePrompt: true,
      })
      const fields = await fetchToolFields(fieldTool.rawId)
      applyLoadedFields(fields)
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "应用模板失败")
    } finally {
      setFieldSaving(false)
    }
  }

  async function saveFields() {
    if (!fieldTool) return
    setFieldError(null)
    let fields: ToolFieldPayload[]
    try {
      const editable = fieldEditorMode === "visual" ? editableFields : parseFieldsJson(fieldJson)
      fields = editable.map((field, index) => toFieldPayload(field, index))
    } catch (err) {
      setFieldError(err instanceof Error ? err.message : "字段配置无效")
      return
    }
    setFieldSaving(true)
    try {
      const saved = await updateToolFields(fieldTool.rawId, fields)
      applyLoadedFields(saved)
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
                {!editingTool ? (
                  <div className="space-y-2">
                    <Label>工具模板</Label>
                    <Select
                      value={form.templateCode}
                      onValueChange={(value) => {
                        updateForm("templateCode", value)
                        applyTemplateToForm(value)
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="选择蓝图模板" />
                      </SelectTrigger>
                      <SelectContent>
                        {toolTemplates.map((item) => (
                          <SelectItem key={item.templateCode} value={item.templateCode}>
                            {item.templateName} ({item.executionHandler})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                ) : null}
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
                      {filteredModelConfigs.map((config) => (
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
            <DialogTitle>用户端表单字段</DialogTitle>
            <DialogDescription>
              {fieldTool
                ? `配置「${fieldTool.name}」参数。画面比例等请用「单选/下拉」并配置选项，用户只能点选。`
                : "配置工具字段。"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            {fieldTool ? (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-secondary/30 p-3">
                <div>
                  <p className="text-sm font-medium">从工具模板填充</p>
                  <p className="text-xs text-muted-foreground">
                    当前类型：{optionLabel(toolTypeOptions, fieldTool.toolType)}。应用后可再在下方微调各字段选项。
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
            <Tabs value={fieldEditorMode} onValueChange={(value) => switchFieldEditorMode(value as "visual" | "json")}>
              <TabsList className="grid w-full grid-cols-2">
                <TabsTrigger value="visual" disabled={fieldLoading || fieldSaving}>
                  可视化配置
                </TabsTrigger>
                <TabsTrigger value="json" disabled={fieldLoading || fieldSaving}>
                  高级 JSON
                </TabsTrigger>
              </TabsList>
              <TabsContent value="visual" className="mt-3 max-h-[55vh] overflow-y-auto pr-1">
                <FieldSchemaEditor
                  fields={editableFields}
                  disabled={fieldLoading || fieldSaving}
                  onChange={(next) => {
                    setEditableFields(next)
                    setFieldJson(serializeFields(next))
                  }}
                />
              </TabsContent>
              <TabsContent value="json" className="mt-3">
                <Textarea
                  value={fieldJson}
                  onChange={(event) => setFieldJson(event.target.value)}
                  className="min-h-[420px] font-mono text-xs"
                  disabled={fieldLoading || fieldSaving}
                  placeholder='[{"fieldKey":"aspectRatio","fieldName":"画面比例","fieldType":"radio"}]'
                />
                <p className="mt-2 text-xs text-muted-foreground">
                  radio / select 需带 optionsJson。日常请优先使用「可视化配置」。
                </p>
              </TabsContent>
            </Tabs>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFieldDialogOpen(false)} disabled={fieldSaving}>
              取消
            </Button>
            <Button onClick={saveFields} disabled={fieldLoading || fieldSaving}>
              {fieldSaving ? "保存中..." : "保存字段"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}

