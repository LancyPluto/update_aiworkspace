"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
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
  Download,
  FileText,
  KeyRound,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Shield,
  ShoppingBag,
  Sparkles,
  Store,
  Trash2,
  Upload,
  UploadCloud,
  Video,
  Workflow,
  type LucideIcon,
} from "lucide-react"
import { ToolIntegrationApiSection } from "@/components/admin/tool-integration-api-section"
import { resolveIntegrationPluginId } from "@/lib/model-capabilities"
import {
  extractFrontendStyle,
  extractIntegrationMarkers,
  serializeConfigNote,
} from "@/lib/tool-config-note"
import { cn } from "@/lib/utils"
import {
  createTool,
  deleteTool,
  fetchAdminToolCategories,
  fetchAdminTools,
  fetchToolFields,
  offlineTool,
  publishTool,
  updateTool,
  updateToolFields,
  uploadToolCover,
} from "@/lib/api/tools"
import { applyToolTemplate, fetchToolTemplates, type ToolTemplateSummary } from "@/lib/api/tool-templates"
import { FieldSchemaEditor, FieldSchemaPreview } from "@/components/admin/field-schema-editor"
import {
  editableFromToolField,
  parseFieldsJson,
  serializeFields,
  toFieldPayload,
  type EditableField,
} from "@/lib/tool-fields"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { fetchModelProviders } from "@/lib/api/model-providers"
import { ApiError, getBaseUrl } from "@/lib/api/http"
import { downloadConfigBundle, exportConfigBundle, importConfigBundle, readConfigBundleFile } from "@/lib/api/config-bundles"
import type { AgentModelConfig, ModelProviderDescriptor, ToolCategory, ToolField, ToolFieldPayload, ToolSummary } from "@/lib/api/types"

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
  coverUrl: string | null
  primaryColor: string
  welcomeMessage: string
  mediaDisplayMode: "icon" | "effect"
  modelIconUrl: string
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
  coverUrl: string
  primaryColor: string
  welcomeMessage: string
  mediaDisplayMode: "icon" | "effect"
  modelIconUrl: string
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
  coverUrl: "",
  primaryColor: "#3b82f6",
  welcomeMessage: "",
  mediaDisplayMode: "icon",
  modelIconUrl: "",
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

function executionCapabilityForTool(
  toolType: string,
  toolCode: string,
  executionHandler?: string | null,
  inputModality?: string | null,
  outputModality?: string | null,
): string {
  const eh = executionHandler?.trim()
  if (eh) return eh.toUpperCase()
  const code = (toolCode || "").trim()
  if (code === "digital_human_agent") return "DIGITAL_HUMAN"
  const input = (inputModality || "").trim().toUpperCase()
  const output = (outputModality || "").trim().toUpperCase()
  if (input === "TEXT" && output === "AUDIO") return "TEXT_TO_SPEECH"
  if (input === "AUDIO" && output === "TEXT") return "SPEECH_TO_TEXT"
  if (input === "TEXT" && output === "IMAGE") return "IMAGE_GENERATION"
  if (input === "IMAGE" && output === "IMAGE") return "IMAGE_TO_IMAGE"
  if (input === "IMAGE" && output === "TEXT") return "IMAGE_UNDERSTANDING"
  if (output === "VIDEO") return "VIDEO_GENERATION"
  if (output === "JSON" && (toolType || "").trim().toUpperCase() === "EMBEDDING") return "EMBEDDING"
  if (output === "JSON" && (toolType || "").trim().toUpperCase() === "RERANK") return "RERANK"
  return (toolType || "TEXT_GENERATION").toUpperCase()
}

const fallbackProviderCapabilities: Record<string, string[]> = {
  mock: ["TEXT_GENERATION"],
  openai_compatible: ["TEXT_GENERATION"],
  anthropic_compatible: ["TEXT_GENERATION"],
  minimax: ["TEXT_GENERATION"],
  siliconflow: ["IMAGE_GENERATION", "DIGITAL_HUMAN"],
  siliconflow_images: ["IMAGE_GENERATION", "DIGITAL_HUMAN"],
  volcengine_images: ["IMAGE_GENERATION"],
  minimax_speech: ["TEXT_TO_SPEECH"],
  siliconflow_speech: ["TEXT_TO_SPEECH"],
  siliconflow_asr: ["SPEECH_TO_TEXT"],
  seedance: ["VIDEO_GENERATION", "DIGITAL_HUMAN"],
  worker_video: ["VIDEO_GENERATION"],
}

function modelConfigSupportsCapability(
  config: AgentModelConfig,
  capability: string,
  providerCapabilities: Record<string, string[]> = fallbackProviderCapabilities,
): boolean {
  const caps = resolvedModelCapabilities(config, providerCapabilities)
  if (caps.length === 0) return false
  const want = capability.toUpperCase()
  return caps.some((c) => (c || "").toUpperCase() === want)
}

function resolvedModelCapabilities(
  config: AgentModelConfig,
  providerCapabilities: Record<string, string[]> = fallbackProviderCapabilities,
): string[] {
  if (config.capabilities && config.capabilities.length > 0) {
    return config.capabilities
      .filter((capability) => capability && capability.trim())
      .map((capability) => capability.trim().toUpperCase())
  }
  const provider = (config.provider || "").trim().toLowerCase()
  return (providerCapabilities[provider] || fallbackProviderCapabilities[provider] || [])
    .filter((capability) => capability && capability.trim())
    .map((capability) => capability.trim().toUpperCase())
}

function capabilityLabel(capability: string): string {
  const value = capability.toUpperCase()
  return toolTypeOptions.find((item) => item.value === value)?.label || value
}

function optionLabel(options: Array<{ value: string; label: string }>, value?: string | null) {
  return options.find((item) => item.value === value)?.label || value || "-"
}

const templateCodeByToolType: Record<string, string> = {
  TEXT_GENERATION: "text_generation_default",
  IMAGE_GENERATION: "image_generation_default",
  TEXT_TO_SPEECH: "text_to_speech_default",
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

function isVideoPreviewUrl(url?: string | null): boolean {
  if (!url) return false
  const normalized = url.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => normalized.endsWith(ext))
}

function normalizeToolMediaUrl(url?: string | null): string {
  const raw = url?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const baseUrl = getBaseUrl().replace(/\/$/, "")
  return baseUrl ? `${baseUrl}${path}` : path
}

function safePreviewFields(json: string): EditableField[] {
  try {
    return parseFieldsJson(json)
  } catch {
    return []
  }
}

function mapTool(tool: ToolSummary): ToolRow {
  const { note, style } = extractFrontendStyle(tool.configNote)
  return {
    id: String(tool.id),
    rawId: tool.id,
    toolCode: tool.toolCode,
    name: tool.toolName,
    description: tool.description || "暂无描述",
    category: tool.categoryName || "未分类",
    categoryId: tool.categoryId ?? null,
    toolType: tool.toolType || "TEXT_GENERATION",
    inputModality: tool.inputModality || "TEXT",
    outputModality: tool.outputModality || "TEXT",
    configNote: note || null,
    coverUrl: tool.coverUrl || null,
    primaryColor: style.primaryColor,
    welcomeMessage: style.welcomeMessage,
    mediaDisplayMode: style.mediaDisplayMode,
    modelIconUrl: style.modelIconUrl,
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
  const [selectedOutputModality, setSelectedOutputModality] = useState<string | null>(null)
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<ToolRow | null>(null)
  const [toolList, setToolList] = useState<ToolRow[]>([])
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [providerCapabilities, setProviderCapabilities] = useState<Record<string, string[]>>(fallbackProviderCapabilities)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [bundleBusy, setBundleBusy] = useState(false)
  const [exportDialogOpen, setExportDialogOpen] = useState(false)
  const [togglingId, setTogglingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [coverUploading, setCoverUploading] = useState(false)
  const [coverDragging, setCoverDragging] = useState(false)
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
      const [toolsResp, cats, providers] = await Promise.all([
        fetchAdminTools(),
        fetchAdminToolCategories().catch(() => [] as ToolCategory[]),
        fetchModelProviders().catch(() => [] as ModelProviderDescriptor[]),
      ])
      setToolList(toolsResp.list.map(mapTool))
      setCategories(cats)
      if (providers.length > 0) {
        setProviderCapabilities(
          providers.reduce<Record<string, string[]>>((acc, provider) => {
            acc[provider.code.trim().toLowerCase()] = provider.capabilities || []
            return acc
          }, { ...fallbackProviderCapabilities }),
        )
      }
      const configs = await fetchAgentModelConfigs().catch(() => [] as AgentModelConfig[])
      setModelConfigs(configs.filter((config) => config.enabled !== false))
      const templates = await fetchToolTemplates().catch(() => [] as ToolTemplateSummary[])
      setToolTemplates(templates)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载工具失败")
    } finally {
      setLoading(false)
    }
  }

  async function handleExportBundle(includeSecrets: boolean) {
    setBundleBusy(true)
    setError(null)
    setNotice(null)
    try {
      const bundle = await exportConfigBundle(includeSecrets)
      downloadConfigBundle(bundle)
      setExportDialogOpen(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "导出配置包失败")
    } finally {
      setBundleBusy(false)
    }
  }

  async function handleImportBundle(file: File | undefined) {
    if (!file) return
    setBundleBusy(true)
    setError(null)
    setNotice(null)
    try {
      const bundle = await readConfigBundleFile(file)
      const result = await importConfigBundle(bundle)
      await loadAll()
      const warningText = result.warnings?.length ? `；提示：${result.warnings.join("；")}` : ""
      setNotice(`导入完成：工具 ${result.tools}、字段 ${result.fields}、提示词版本 ${result.promptVersions}、工作流 ${result.workflows}${warningText}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "导入配置包失败，请确认 JSON 格式正确")
    } finally {
      setBundleBusy(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    return toolList.filter((tool) => {
      const matchesKeyword =
        !keyword ||
        [tool.name, tool.description, tool.category, tool.toolCode].some((value) =>
          value.toLowerCase().includes(keyword),
        )
      const matchesOutput =
        !selectedOutputModality ||
        (tool.outputModality || "").trim().toUpperCase() === selectedOutputModality
      return matchesKeyword && matchesOutput
    })
  }, [toolList, searchQuery, selectedOutputModality])

  const outputModalityFilters = useMemo(() => {
    const counts = new Map<string, number>()
    for (const tool of toolList) {
      const key = (tool.outputModality || "TEXT").trim().toUpperCase()
      counts.set(key, (counts.get(key) || 0) + 1)
    }
    const order = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "JSON", "FILE", "MULTIMODAL"]
    return [...counts.entries()]
      .sort(([a], [b]) => {
        const ia = order.indexOf(a)
        const ib = order.indexOf(b)
        return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
      })
      .map(([key, count]) => ({ key, label: optionLabel(modalityOptions, key), count }))
  }, [toolList])

  const requiredModelCapability = useMemo(() => {
    const code = editingTool?.toolCode ?? form.toolCode ?? ""
    const type = editingTool?.toolType ?? form.toolType
    const eh = editingTool?.executionHandler ?? null
    const input = form.inputModality || editingTool?.inputModality
    const output = form.outputModality || editingTool?.outputModality
    return executionCapabilityForTool(type, code, eh, input, output)
  }, [editingTool, form.toolType, form.toolCode, form.inputModality, form.outputModality])

  const filteredModelConfigs = useMemo(
    () => modelConfigs.filter((c) => modelConfigSupportsCapability(c, requiredModelCapability, providerCapabilities)),
    [modelConfigs, providerCapabilities, requiredModelCapability],
  )

  const defaultModelConfig = useMemo(
    () => modelConfigs.find((config) => config.isDefault) || modelConfigs[0] || null,
    [modelConfigs],
  )

  const defaultModelSupportsRequiredCapability = useMemo(
    () => !defaultModelConfig || modelConfigSupportsCapability(defaultModelConfig, requiredModelCapability, providerCapabilities),
    [defaultModelConfig, providerCapabilities, requiredModelCapability],
  )

  const modelSelectValue = form.modelConfigId
    ? form.modelConfigId
    : defaultModelSupportsRequiredCapability
      ? "default"
      : "__select_matching_model"

  const configuredModelRows = useMemo(
    () => modelConfigs.map((config) => ({
      config,
      capabilities: resolvedModelCapabilities(config, providerCapabilities),
    })),
    [modelConfigs, providerCapabilities],
  )

  const integrationPluginId = useMemo(
    () => (editingTool ? resolveIntegrationPluginId(editingTool) : null),
    [editingTool],
  )

  useEffect(() => {
    if (!form.modelConfigId) return
    const selected = modelConfigs.find((config) => String(config.id) === form.modelConfigId)
    if (selected && !modelConfigSupportsCapability(selected, requiredModelCapability, providerCapabilities)) {
      updateForm("modelConfigId", "")
    }
  }, [form.modelConfigId, modelConfigs, providerCapabilities, requiredModelCapability])

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
      const message = err instanceof ApiError ? err.message : "更新工具状态失败"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setTogglingId(null)
    }
  }

  function updateForm<K extends keyof ToolForm>(key: K, value: ToolForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function selectedModelName(): string {
    const selected = form.modelConfigId
      ? modelConfigs.find((config) => String(config.id) === form.modelConfigId)
      : defaultModelConfig
    return selected?.displayName || selected?.modelName || ""
  }

  async function handleCoverUpload(file?: File | null) {
    if (!file) return
    setFormError(null)
    setCoverUploading(true)
    try {
      const uploaded = await uploadToolCover({
        file,
        toolName: form.toolName.trim(),
        toolCode: form.toolCode.trim(),
        modelName: selectedModelName(),
      })
      updateForm("coverUrl", uploaded.url)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "工具展示素材上传失败，请查看后端日志。"
      console.error("[AI Tool Management] 工具展示素材上传失败", err)
      setFormError(message)
    } finally {
      setCoverUploading(false)
      setCoverDragging(false)
    }
  }

  function openCreateDialog() {
    setEditingTool(null)
    setForm({
      ...initialForm,
      categoryId: categories[0] ? String(categories[0].id) : "",
      templateCode: templateCodeByToolType[initialForm.toolType] || "",
    })
    setFormError(null)
    setCoverUploading(false)
    setCoverDragging(false)
    setIsAddDialogOpen(true)
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
      description: tool.description === "暂无描述" ? "" : tool.description,
      categoryId: tool.categoryId ? String(tool.categoryId) : "",
      toolType: tool.toolType,
      inputModality: tool.inputModality,
      outputModality: tool.outputModality,
      configNote: tool.configNote || "",
      coverUrl: tool.coverUrl || "",
      primaryColor: tool.primaryColor || "#3b82f6",
      welcomeMessage: tool.welcomeMessage || "",
      mediaDisplayMode: tool.mediaDisplayMode || "icon",
      modelIconUrl: tool.modelIconUrl || "",
      estimatedCreditCost: String(tool.credits),
      modelConfigId: tool.modelConfigId ? String(tool.modelConfigId) : "",
      templateCode: "",
    })
    setFormError(null)
    setCoverUploading(false)
    setCoverDragging(false)
    setIsAddDialogOpen(true)
  }

  async function handleSaveTool() {
    setFormError(null)
    if (!form.toolName.trim()) {
      setFormError("请填写工具名称。")
      return
    }
    if (!form.categoryId) {
      setFormError(categories.length === 0 ? "请先在「分类管理」中创建一个工具分类。" : "请选择工具分类。")
      return
    }
    const credits = Number(form.estimatedCreditCost)
    if (!Number.isFinite(credits) || credits < 0) {
      setFormError("消耗算力必须是大于等于 0 的数字。")
      return
    }
    if (!form.modelConfigId && !defaultModelSupportsRequiredCapability) {
      setFormError(`默认模型不支持「${capabilityLabel(requiredModelCapability)}」，请选择一个匹配的模型配置。`)
      return
    }
    setSubmitting(true)
    try {
      const style = {
        primaryColor: form.primaryColor,
        welcomeMessage: form.welcomeMessage,
        mediaDisplayMode: form.mediaDisplayMode,
        modelIconUrl: form.modelIconUrl,
      }
      let preservedMarkers: string[] | undefined
      if (integrationPluginId && editingTool) {
        const toolsResp = await fetchAdminTools()
        const fresh = toolsResp.list.find((tool) => tool.id === editingTool.rawId)
        preservedMarkers = extractIntegrationMarkers(fresh?.configNote)
      }
      const payload = {
        toolCode: form.toolCode.trim() || undefined,
        toolName: form.toolName.trim(),
        categoryId: Number(form.categoryId),
        description: form.description.trim() || undefined,
        toolType: form.toolType,
        inputModality: form.inputModality,
        outputModality: form.outputModality,
        configNote: serializeConfigNote(form.configNote, style, preservedMarkers),
        coverUrl: form.coverUrl.trim() || undefined,
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
      const message = err instanceof ApiError ? err.message : "保存工具失败，请稍后重试。"
      console.error("[AI Tool Management] 保存工具失败", {
        error: err,
        api: err instanceof ApiError
          ? { code: err.code, status: err.status, traceId: err.traceId, responseBody: err.responseBody }
          : null,
      })
      setFormError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDeleteTool(tool: ToolRow) {
    if (typeof window !== "undefined") {
      const confirmed = window.confirm(`确定删除「${tool.name}」吗？删除后该工具会从管理列表和用户端下线。`)
      if (!confirmed) return
    }
    setDeletingId(tool.rawId)
    setError(null)
    try {
      await deleteTool(tool.rawId)
      setToolList((prev) => prev.filter((item) => item.rawId !== tool.rawId))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "删除工具失败，请查看后端日志。"
      console.error("[AI Tool Management] 删除工具失败", { toolId: tool.rawId, toolCode: tool.toolCode, error: err })
      setError(message)
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setDeletingId(null)
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
      setFieldError(err instanceof ApiError ? err.message : "加载字段配置失败")
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
      const coreFields = editable.filter((field) => field.isCore)
      if (coreFields.length > 1) {
        throw new Error(`核心字段只能选择一个：${coreFields.map((field) => field.fieldName || field.fieldKey).join("、")}`)
      }
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
      setFieldError(err instanceof ApiError ? err.message : "保存字段配置失败")
    } finally {
      setFieldSaving(false)
    }
  }

  const headerDescription = error
    ? `操作失败：${error}`
    : notice
      ? notice
    : loading
      ? "正在加载工具列表..."
      : "管理 AI 工具工作流入口和画布配置。"

  return (
    <AdminLayout>
      <AdminHeader title="大模型管理" description={headerDescription} />

      <div className="space-y-6 p-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索工具名称、分类、描述..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" className="gap-2" onClick={() => setExportDialogOpen(true)} disabled={bundleBusy || loading}>
              <Download className="h-4 w-4" />
              导出
            </Button>
            <Button variant="outline" className="relative gap-2" disabled={bundleBusy || loading}>
              <Upload className={bundleBusy ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
              导入
              <input
                type="file"
                accept="application/json,.json"
                className="absolute inset-0 cursor-pointer opacity-0"
                disabled={bundleBusy || loading}
                onChange={(event) => {
                  handleImportBundle(event.target.files?.[0])
                  event.currentTarget.value = ""
                }}
              />
            </Button>
          <Button className="gap-2" onClick={openCreateDialog}>
            <Plus className="h-4 w-4" />
            新建工具
          </Button>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant={selectedOutputModality === null ? "default" : "outline"}
            onClick={() => setSelectedOutputModality(null)}
          >
            全部
            <span className="ml-1 opacity-70">{toolList.length}</span>
          </Button>
          {outputModalityFilters.map((item) => (
            <Button
              key={item.key}
              type="button"
              size="sm"
              variant={selectedOutputModality === item.key ? "default" : "outline"}
              onClick={() => setSelectedOutputModality(item.key)}
            >
              {item.label}
              <span className="ml-1 opacity-70">{item.count}</span>
            </Button>
          ))}
        </div>

          <Dialog
            open={isAddDialogOpen}
            onOpenChange={(open) => {
              setIsAddDialogOpen(open)
              if (!open) {
                setForm(initialForm)
                setEditingTool(null)
                setFormError(null)
                setCoverUploading(false)
                setCoverDragging(false)
              }
            }}
          >
            <DialogContent
              className={cn(
                "max-h-[92vh] overflow-y-auto border-border bg-card",
                integrationPluginId ? "max-w-2xl" : "max-w-lg",
              )}
            >
              <DialogHeader>
                <DialogTitle>{editingTool ? "编辑 AI 工具" : "新建 AI 工具"}</DialogTitle>
                <DialogDescription className={formError ? "text-destructive" : undefined}>
                  {formError || "配置工具信息、能力类型和模型绑定。"}
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>工具名称</Label>
                  <Input value={form.toolName} onChange={(event) => updateForm("toolName", event.target.value)} placeholder="例如：文字转语音" />
                </div>
                <div className="space-y-2">
                  <Label>工具描述</Label>
                  <Textarea value={form.description} onChange={(event) => updateForm("description", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>工具展示素材 URL</Label>
                  <Input
                    value={form.coverUrl}
                    onChange={(event) => updateForm("coverUrl", event.target.value)}
                    placeholder="可填图片、GIF、MP4/WebM/MOV 地址；不填则使用默认图标"
                  />
                  <label
                    className={cn(
                      "flex cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-border bg-secondary/30 px-4 py-5 text-center transition",
                      coverDragging && "border-primary bg-primary/5",
                      coverUploading && "pointer-events-none opacity-70",
                    )}
                    onDragEnter={(event) => {
                      event.preventDefault()
                      setCoverDragging(true)
                    }}
                    onDragOver={(event) => {
                      event.preventDefault()
                      setCoverDragging(true)
                    }}
                    onDragLeave={(event) => {
                      event.preventDefault()
                      setCoverDragging(false)
                    }}
                    onDrop={(event) => {
                      event.preventDefault()
                      const file = event.dataTransfer.files?.[0]
                      void handleCoverUpload(file)
                    }}
                  >
                    <UploadCloud className="mb-2 h-5 w-5 text-primary" />
                    <span className="text-sm font-medium">
                      {coverUploading ? "上传中..." : "拖拽图片、GIF 或视频到这里"}
                    </span>
                    <span className="mt-1 text-xs text-muted-foreground">
                      也可以点击选择文件；系统会按工具名和模型自动命名。
                    </span>
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,video/quicktime,video/x-m4v"
                      className="hidden"
                      onChange={(event) => {
                        const file = event.target.files?.[0]
                        void handleCoverUpload(file)
                        event.currentTarget.value = ""
                      }}
                    />
                  </label>
                  {form.coverUrl.trim() ? (
                    <div className="overflow-hidden rounded-lg border border-border bg-muted/40">
                      {isVideoPreviewUrl(form.coverUrl) ? (
                        <video
                          src={normalizeToolMediaUrl(form.coverUrl)}
                          className="aspect-video w-full object-cover"
                          muted
                          loop
                          playsInline
                          controls
                          preload="metadata"
                        />
                      ) : (
                        <img
                          src={normalizeToolMediaUrl(form.coverUrl)}
                          alt="工具展示素材预览"
                          className="aspect-video w-full object-cover"
                        />
                      )}
                    </div>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      建议使用 16:9 横图；GIF 可直接作为图片使用，视频建议 MP4/WebM。
                    </p>
                  )}
                </div>
                <div className="space-y-3 rounded-lg border border-border bg-secondary/20 p-3">
                  <div>
                    <Label>前端展示样式</Label>
                    <p className="mt-1 text-xs text-muted-foreground">
                      C 端工具卡片和聊天欢迎页会复用这些视觉配置。
                    </p>
                  </div>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-2 sm:col-span-2">
                      <Label>模型卡片展示</Label>
                      <Select value={form.mediaDisplayMode} onValueChange={(value) => updateForm("mediaDisplayMode", value as "icon" | "effect")}>
                        <SelectTrigger>
                          <SelectValue placeholder="选择模型卡片展示方式" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="icon">模型图标</SelectItem>
                          <SelectItem value="effect">模型效果</SelectItem>
                        </SelectContent>
                      </Select>
                      <p className="text-xs text-muted-foreground">
                        模型图标会在 C 端展示圆形图标；模型效果会使用展示素材作为宽幅图片或视频预览。
                      </p>
                    </div>
                    <div className="space-y-2">
                      <Label>主题色</Label>
                      <div className="flex items-center gap-2">
                        <Input
                          type="color"
                          className="h-10 w-14 cursor-pointer p-1"
                          value={form.primaryColor || "#3b82f6"}
                          onChange={(event) => updateForm("primaryColor", event.target.value)}
                        />
                        <Input
                          value={form.primaryColor || "#3b82f6"}
                          onChange={(event) => updateForm("primaryColor", event.target.value)}
                          placeholder="#3b82f6"
                        />
                      </div>
                    </div>
                    <div className="space-y-2">
                      <Label>欢迎语</Label>
                      <Textarea
                        rows={2}
                        value={form.welcomeMessage}
                        onChange={(event) => updateForm("welcomeMessage", event.target.value)}
                        placeholder="首次进入聊天页时展示"
                      />
                    </div>
                    <div className="space-y-2 sm:col-span-2">
                      <Label>模型图标 URL</Label>
                      <Input
                        value={form.modelIconUrl}
                        onChange={(event) => updateForm("modelIconUrl", event.target.value)}
                        placeholder="用于聊天页左上角和欢迎态；不填则按绑定模型自动生成默认图标"
                      />
                    </div>
                  </div>
                  <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-3">
                    {form.modelIconUrl.trim() ? (
                      <div
                        className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full ring-1 ring-border"
                        style={{ backgroundColor: `${form.primaryColor || "#3b82f6"}18` }}
                      >
                        <img src={normalizeToolMediaUrl(form.modelIconUrl)} alt="model icon preview" className="h-full w-full object-cover" />
                      </div>
                    ) : form.mediaDisplayMode === "effect" && form.coverUrl.trim() ? (
                      <div className="h-14 w-24 shrink-0 overflow-hidden rounded-lg bg-muted ring-1 ring-border">
                        {isVideoPreviewUrl(form.coverUrl) ? (
                          <video src={normalizeToolMediaUrl(form.coverUrl)} className="h-full w-full object-cover" muted loop playsInline preload="metadata" />
                        ) : (
                          <img src={normalizeToolMediaUrl(form.coverUrl)} alt="effect preview" className="h-full w-full object-cover" />
                        )}
                      </div>
                    ) : (
                    <div
                      className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full ring-1 ring-border"
                      style={{ backgroundColor: `${form.primaryColor || "#3b82f6"}18` }}
                    >
                      {form.coverUrl.trim() && !isVideoPreviewUrl(form.coverUrl) ? (
                        <img src={normalizeToolMediaUrl(form.coverUrl)} alt="前端图标预览" className="h-full w-full object-cover" />
                      ) : (
                        <Sparkles className="h-5 w-5 text-primary" />
                      )}
                    </div>
                    )}
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">{form.toolName || "工具名称"}</p>
                      <p className="line-clamp-1 text-xs text-muted-foreground">
                        {form.welcomeMessage || "欢迎语会展示在聊天欢迎页"}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>分类</Label>
                    <Select value={form.categoryId} onValueChange={(value) => updateForm("categoryId", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder={categories.length === 0 ? "暂无分类，请先创建分类" : "选择分类"} />
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
                    <Label>消耗算力</Label>
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
                  <Label>工具编码</Label>
                  <Input
                    value={form.toolCode}
                    onChange={(event) => updateForm("toolCode", event.target.value)}
                    placeholder="不填则自动生成"
                    disabled={Boolean(editingTool)}
                  />
                </div>
                <div className="space-y-2">
                  <Label>模型配置</Label>
                  {integrationPluginId ? (
                    <div className="space-y-3 rounded-lg border border-border p-3">
                      <p className="text-xs text-amber-700">
                        工作台类工具的大模型绑定保存在下方「保存配置」中；仅点对话框底部「保存工具」不会写入文本/文生图模型。
                      </p>
                      <ToolIntegrationApiSection
                        pluginId={integrationPluginId}
                        toolId={editingTool.rawId}
                        onSaved={async () => {
                          const toolsResp = await fetchAdminTools()
                          const fresh = toolsResp.list.find((tool) => tool.id === editingTool.rawId)
                          if (fresh) {
                            const mapped = mapTool(fresh)
                            setEditingTool(mapped)
                            setForm((prev) => ({ ...prev, configNote: mapped.configNote || "" }))
                          }
                          await loadAll()
                        }}
                      />
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <Select
                        value={modelSelectValue}
                        onValueChange={(value) => updateForm("modelConfigId", value === "default" || value === "__select_matching_model" ? "" : value)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="选择匹配的模型配置" />
                        </SelectTrigger>
                        <SelectContent>
                          {!defaultModelSupportsRequiredCapability ? (
                            <SelectItem value="__select_matching_model" disabled>
                              请选择支持「{capabilityLabel(requiredModelCapability)}」的模型
                            </SelectItem>
                          ) : null}
                          <SelectItem value="default" disabled={!defaultModelSupportsRequiredCapability}>
                            使用默认模型配置
                          </SelectItem>
                          {filteredModelConfigs.map((config) => (
                            <SelectItem key={config.id} value={String(config.id)}>
                              {config.displayName || config.modelName} · {config.provider}
                            </SelectItem>
                          ))}
                          {filteredModelConfigs.length === 0 ? (
                            <SelectItem value="__no_matching_models" disabled>
                              暂无匹配模型配置
                            </SelectItem>
                          ) : null}
                        </SelectContent>
                      </Select>
                      <p className="text-xs text-muted-foreground">需要模型能力：{capabilityLabel(requiredModelCapability)}</p>
                    </div>
                  )}
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setIsAddDialogOpen(false)} disabled={submitting}>
                  取消
                </Button>
                <Button onClick={handleSaveTool} disabled={submitting}>
                  {submitting ? "保存中..." : editingTool ? "保存工具" : "创建工具"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>

        {false ? (
        <div className="rounded-2xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center justify-between gap-4">
            <div>
              <h2 className="text-base font-semibold text-card-foreground">已配置模型能力</h2>
              <p className="text-sm text-muted-foreground">确认当前后台可绑定到不同模态工具的模型配置。</p>
            </div>
            <Badge variant="secondary">{configuredModelRows.length} 个模型</Badge>
          </div>
          {configuredModelRows.length === 0 ? (
            <p className="text-sm text-muted-foreground">暂无可用模型配置。</p>
          ) : (
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              {configuredModelRows.map(({ config, capabilities }) => (
                <div key={config.id} className="rounded-xl border border-border/70 bg-secondary/40 p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-medium text-card-foreground">{config.displayName || config.modelName}</p>
                      <p className="truncate text-xs text-muted-foreground">{config.provider} · {config.modelName}</p>
                    </div>
                    {config.isDefault ? <Badge variant="outline">默认</Badge> : null}
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {capabilities.length > 0 ? capabilities.map((capability) => (
                      <Badge key={capability} variant="secondary" className="text-xs">
                        {capabilityLabel(capability)}
                      </Badge>
                    )) : (
                      <Badge variant="destructive" className="text-xs">未识别能力</Badge>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
        ) : null}

        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {filteredTools.map((tool) => (
            <div
              key={tool.id}
              className={cn(
                "group relative overflow-hidden rounded-2xl border border-border bg-card p-6 transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5",
                !tool.status && "opacity-60",
              )}
            >
              {tool.coverUrl ? (
                <div className="-mx-6 -mt-6 mb-5 overflow-hidden border-b border-border bg-muted">
                  {isVideoPreviewUrl(tool.coverUrl) ? (
                    <video
                      src={normalizeToolMediaUrl(tool.coverUrl)}
                      className="aspect-video w-full object-cover"
                      muted
                      loop
                      playsInline
                      preload="metadata"
                    />
                  ) : (
                    <img src={normalizeToolMediaUrl(tool.coverUrl)} alt={tool.name} className="aspect-video w-full object-cover" />
                  )}
                </div>
              ) : null}
              <div className="flex items-start justify-between gap-4">
                <div className="flex min-w-0 items-center gap-3">
                  <div
                    className="flex h-10 w-10 items-center justify-center rounded-xl"
                    style={{ backgroundColor: `${tool.primaryColor || "#3b82f6"}18` }}
                  >
                    <tool.icon className="h-5 w-5" style={{ color: tool.primaryColor || "hsl(var(--primary))" }} />
                  </div>
                  <div className="min-w-0">
                    <h3 className="truncate font-semibold text-card-foreground">{tool.name}</h3>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      <Badge variant="secondary" className="text-xs font-normal">{tool.category}</Badge>
                      <Badge variant={tool.status ? "outline" : "destructive"} className="text-xs font-normal">
                        {tool.status ? "已上线" : "已下线"}
                      </Badge>
                    </div>
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
                      <Pencil className="h-4 w-4" /> 编辑
                    </DropdownMenuItem>
                    <DropdownMenuItem asChild className="gap-2">
                      <Link href={`/tools/${tool.rawId}/workflow`}>
                        <Workflow className="h-4 w-4" /> 工作流画布
                      </Link>
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" onClick={() => openFieldDialog(tool)}>
                      <FileText className="h-4 w-4" /> 字段配置
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" disabled>
                      <Copy className="h-4 w-4" /> 复制
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      className="gap-2 text-destructive"
                      disabled={deletingId === tool.rawId}
                      onClick={() => handleDeleteTool(tool)}
                    >
                      <Trash2 className="h-4 w-4" /> {deletingId === tool.rawId ? "删除中..." : "删除"}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>

              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{tool.description}</p>

              <div className="mt-4 grid gap-2 border-t border-border pt-4 text-xs">
                <div className="flex items-center justify-between gap-3 rounded-lg bg-muted/40 px-3 py-2">
                  <span className="text-muted-foreground">工作流类型</span>
                  <span className="truncate font-medium text-card-foreground">{optionLabel(toolTypeOptions, tool.toolType)}</span>
                </div>
                <div className="flex items-center justify-between gap-3 rounded-lg bg-muted/40 px-3 py-2">
                  <span className="text-muted-foreground">输入 / 输出</span>
                  <span className="truncate font-medium text-card-foreground">
                    {optionLabel(modalityOptions, tool.inputModality)} {"->"} {optionLabel(modalityOptions, tool.outputModality)}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-3 rounded-lg bg-muted/40 px-3 py-2">
                  <span className="text-muted-foreground">执行器</span>
                  <span className="truncate font-mono text-card-foreground">{tool.executionHandler || "MODEL"}</span>
                </div>
                <div className="flex items-center justify-between gap-3 rounded-lg bg-muted/40 px-3 py-2">
                  <span className="text-muted-foreground">绑定模型</span>
                  <span className="max-w-[180px] truncate text-right font-medium text-card-foreground">
                    {tool.modelConfigName || tool.modelName || "默认模型配置"}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-3 rounded-lg bg-muted/40 px-3 py-2">
                  <span className="text-muted-foreground">消耗算力</span>
                  <span className="flex items-center gap-1 font-medium text-card-foreground">
                    <Sparkles className="h-3.5 w-3.5 text-primary" />
                    {tool.credits}
                  </span>
                </div>
              </div>
              <Button asChild variant="outline" size="sm" className="mt-4 w-full gap-2">
                <Link href={`/tools/${tool.rawId}/workflow`}>
                  <Workflow className="h-4 w-4" />
                  编辑工作流
                </Link>
              </Button>

              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="text-sm">
                  <p className="font-medium text-card-foreground">
                    {tool.status ? "已上线" : "已下线"}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {tool.status ? "用户端可见并可使用" : "用户端暂不可见"}
                  </p>
                </div>
                <Switch
                  checked={tool.status}
                  disabled={togglingId === tool.rawId}
                  onCheckedChange={() => toggleToolStatus(tool.id)}
                />
              </div>

              {false ? (<>
              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-1 text-muted-foreground">
                    <Sparkles className="h-4 w-4" />
                    <span>{tool.credits} 算力</span>
                  </div>
                  <div className="text-muted-foreground">{tool.rawStatus}</div>
                </div>
                {false ? <Switch checked={tool.status} disabled={togglingId === tool.rawId} onCheckedChange={() => toggleToolStatus(tool.id)} /> : null}
              </div>
              <div className="mt-3 flex flex-wrap gap-2 text-xs">
                <Badge variant="outline">{optionLabel(toolTypeOptions, tool.toolType)}</Badge>
                <Badge variant="secondary">
                  {optionLabel(modalityOptions, tool.inputModality)} → {optionLabel(modalityOptions, tool.outputModality)}
                </Badge>
              </div>
              <div className="mt-3 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
                模型：{tool.modelConfigName || tool.modelName || "默认模型配置"}
              </div>
              <Button asChild variant="outline" size="sm" className="mt-4 w-full gap-2">
                <Link href={`/tools/${tool.rawId}/workflow`}>
                  <Workflow className="h-4 w-4" />
                  打开工作流画布
                </Link>
              </Button>
              </>) : null}
              {tool.welcomeMessage ? (
                <div className="mt-2 rounded-lg bg-secondary/40 px-3 py-2 text-xs text-muted-foreground">
                  欢迎语：{tool.welcomeMessage}
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </div>

      <Dialog open={exportDialogOpen} onOpenChange={setExportDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>导出配置包</DialogTitle>
            <DialogDescription>
              请选择是否把模型 API Key 和额外鉴权信息一起写入 JSON。含密钥文件只适合可信成员之间临时流转。
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-3 sm:grid-cols-2">
            <button
              type="button"
              className="rounded-lg border border-border bg-card p-4 text-left transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
              disabled={bundleBusy}
              onClick={() => handleExportBundle(false)}
            >
              <div className="flex items-center gap-2 font-medium">
                <Shield className="h-4 w-4 text-primary" />
                不含密钥
              </div>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">适合提交到分支、分享给成员或作为默认配置模板。</p>
            </button>

            <button
              type="button"
              className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-4 text-left transition-colors hover:bg-amber-500/15 disabled:cursor-not-allowed disabled:opacity-60"
              disabled={bundleBusy}
              onClick={() => handleExportBundle(true)}
            >
              <div className="flex items-center gap-2 font-medium">
                <KeyRound className="h-4 w-4 text-amber-500" />
                包含密钥
              </div>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">仅用于可信开发环境快速同步，导出后请不要提交仓库。</p>
            </button>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setExportDialogOpen(false)} disabled={bundleBusy}>
              取消
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={fieldDialogOpen} onOpenChange={setFieldDialogOpen}>
        <DialogContent className="!w-[1180px] !max-w-[calc(100vw-2rem)] max-h-[90vh] overflow-y-auto bg-card border-border">
          <DialogHeader>
            <DialogTitle>聊天输入控件</DialogTitle>
            <DialogDescription>
              {fieldTool
                ? `配置「${fieldTool.name}」在聊天输入区展示的参数控件，例如数量、比例、参考图。`
                : "配置聊天输入区参数控件。"}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2 lg:grid-cols-[minmax(0,1fr)_380px]">
            <div className="min-w-0 space-y-3">
              {fieldTool ? (
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-secondary/30 p-3">
                  <div>
                    <p className="text-sm font-medium">从工具模板填充控件</p>
                    <p className="text-xs text-muted-foreground">
                      当前类型：{optionLabel(toolTypeOptions, fieldTool.toolType)}。应用后可再微调数量、比例、参考图等控件。
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
                    可视化控件配置
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
            <div className="lg:sticky lg:top-0 lg:self-start">
              <FieldSchemaPreview
                fields={fieldEditorMode === "visual" ? editableFields : safePreviewFields(fieldJson)}
              />
            </div>
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
