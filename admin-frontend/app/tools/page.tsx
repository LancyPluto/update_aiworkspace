"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
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
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { ScrollArea } from "@/components/ui/scroll-area"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  Copy,
  Download,
  FileText,
  KeyRound,
  Loader2,
  MessageSquare,
  MoreHorizontal,
  Music,
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
  X,
  type LucideIcon,
} from "lucide-react"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { ComparisonSweepPreview, ToolUserPreviewCard } from "@/components/admin/tool-user-preview-card"
import { ToolIntegrationApiSection } from "@/components/admin/tool-integration-api-section"
import {
  capabilityLabel,
  defaultExecutionHandlerForToolType,
  defaultRequiredModelCapabilitiesForTool,
  modelConfigSupportsToolRequirements,
  normalizeLegacyRequiredModelCapabilities,
  normalizeModelCapabilities,
  normalizeRequiredModelCapabilities,
  resolveIntegrationPluginId,
  selectableModelCapabilities,
} from "@/lib/model-capabilities"
import {
  extractFrontendStyle,
  extractIntegrationMarkers,
  serializeConfigNote,
} from "@/lib/tool-config-note"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import {
  createTool,
  deleteTool,
  fetchAdminToolCategories,
  fetchAdminToolDetail,
  fetchAllAdminTools,
  fetchAgentSkill,
  fetchToolFields,
  offlineTool,
  publishAgentSkill,
  publishTool,
  saveAgentSkillDraft,
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
import { deletePricingRule, fetchPricingRules, savePricingRule } from "@/lib/api/pricing"
import {
  GPT_IMAGE2_PRICING_RULES_EXAMPLE,
  HAPPYHORSE_PRICING_RULES_EXAMPLE,
  KLING_VIDEO_PRICING_RULES_EXAMPLE,
  parsePricingRulesJson,
  pricingRuleJsonToPayload,
  pricingRulesForModelExport,
} from "@/lib/pricing-rules-json"
import { isToolAvailableToUsers, isWorkflowTool } from "@/lib/workflow-tools"
import type { AgentModelConfig, AgentSkillBundle, ConfigBundleImportResult, ModelProviderDescriptor, ToolCategory, ToolField, ToolFieldPayload, ToolSummary } from "@/lib/api/types"

const adminBasePath = (process.env.NEXT_PUBLIC_ADMIN_BASE_PATH || "").replace(/\/$/, "")

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
  mediaDisplayMode: "icon" | "effect" | "comparison"
  modelIconUrl: string
  comparisonOriginalUrl: string
  comparisonEffectUrl: string
  audioPreviewUrl: string
  beforeVideoUrl: string
  afterVideoUrl: string
  icon: LucideIcon
  credits: number
  status: boolean
  rawStatus: string
  modelConfigId: number | null
  modelConfigIds: number[]
  defaultModelConfigId: number | null
  modelConfigName: string | null
  modelName: string | null
  executionHandler?: string | null
  requiredModelCapabilities: string[]
}

interface AgentSkillForm {
  skillCode: string
  displayName: string
  description: string
  toolCodesText: string
  whenToUse: string
  whenNotToUse: string
  sopRules: string
  examplesJson: string
  fieldPolicyJson: string
  status: string
  version?: number | null
}

const emptySkillForm: AgentSkillForm = {
  skillCode: "",
  displayName: "",
  description: "",
  toolCodesText: "",
  whenToUse: "",
  whenNotToUse: "",
  sopRules: "",
  examplesJson: "[]",
  fieldPolicyJson: "{}",
  status: "DRAFT",
  version: null,
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
  mediaDisplayMode: "icon" | "effect" | "comparison"
  modelIconUrl: string
  comparisonOriginalUrl: string
  comparisonEffectUrl: string
  audioPreviewUrl: string
  beforeVideoUrl: string
  afterVideoUrl: string
  estimatedCreditCost: string
  pricingRulesJson: string
  modelConfigIds: string[]
  defaultModelConfigId: string
  templateCode: string
  requiredModelCapabilities: string[]
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
  mediaDisplayMode: "icon",
  modelIconUrl: "",
  comparisonOriginalUrl: "",
  comparisonEffectUrl: "",
  audioPreviewUrl: "",
  beforeVideoUrl: "",
  afterVideoUrl: "",
  estimatedCreditCost: "5",
  pricingRulesJson: "[]",
  modelConfigIds: [],
  defaultModelConfigId: "",
  templateCode: "text_generation_default",
  requiredModelCapabilities: ["TEXT_GENERATION"],
}

const toolTypeOptions = [
  { value: "TEXT_GENERATION", label: "文本生成", hint: "文案、摘要、客服回复等文本类工具" },
  { value: "IMAGE_GENERATION", label: "文生图", hint: "输入提示词，输出图片" },
  { value: "IMAGE_TO_IMAGE", label: "图生图", hint: "输入参考图或原图，输出图片" },
  { value: "IMAGE_UNDERSTANDING", label: "图片理解", hint: "输入图片，输出识别/分析文本" },
  { value: "SPEECH_TO_TEXT", label: "语音转文字", hint: "输入音频，输出文本" },
  { value: "TEXT_TO_SPEECH", label: "文字转语音", hint: "输入文本，输出音频" },
  { value: "MUSIC_GENERATION", label: "音乐生成", hint: "输入提示词，输出歌曲/音频" },
  { value: "VIDEO_GENERATION", label: "视频生成", hint: "输入文本/素材，输出视频" },
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
  MUSIC_GENERATION: { input: "TEXT", output: "AUDIO" },
  VIDEO_GENERATION: { input: "TEXT", output: "VIDEO" },
  AGENT: { input: "MULTIMODAL", output: "TEXT" },
}

function optionLabel(options: Array<{ value: string; label: string }>, value?: string | null) {
  return options.find((item) => item.value === value)?.label || value || "-"
}

const templateCodeByToolType: Record<string, string> = {
  TEXT_GENERATION: "text_generation_default",
  IMAGE_GENERATION: "image_generation_default",
  TEXT_TO_SPEECH: "text_to_speech_default",
  MUSIC_GENERATION: "music_generation_default",
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

function textFromEffectPayload(payload: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = payload[key]
    if (typeof value === "string" && value.trim()) return value.trim()
  }
  return ""
}

function safePreviewFields(json: string): EditableField[] {
  try {
    return parseFieldsJson(json)
  } catch {
    return []
  }
}

function EmbeddedOnOffSwitch({
  checked,
  disabled,
  label,
  onCheckedChange,
}: {
  checked: boolean
  disabled?: boolean
  label: string
  onCheckedChange: (checked: boolean) => void
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onCheckedChange(!checked)}
      className={cn(
        "relative inline-flex h-7 w-[62px] shrink-0 items-center overflow-hidden rounded-full border px-1 text-[10px] font-black shadow-sm transition-all duration-300",
        checked
          ? "border-blue-500 bg-blue-500 text-slate-950 shadow-blue-200"
          : "border-slate-200 bg-slate-100 text-slate-400 shadow-slate-100",
        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:shadow-md",
      )}
    >
      <span
        className={cn(
          "absolute left-0.5 top-0.5 h-6 w-6 rounded-full bg-white shadow-[0_2px_6px_rgba(15,23,42,0.22)] ring-1 ring-slate-200 transition-transform duration-300",
          checked ? "translate-x-[34px]" : "translate-x-0",
        )}
      />
      <span className={cn("z-10 w-full text-center tracking-wide transition-all duration-300", checked ? "pr-7" : "pl-7")}>
        {checked ? "ON" : "OFF"}
      </span>
    </button>
  )
}

function vendorIconAssetForKey(key: string, config?: AgentModelConfig | null) {
  if (config?.channelIconAsset?.trim()) return config.channelIconAsset.trim()
  const icons: Record<string, string> = {
    aliyun: "qwen",
    anthropic: "anthropic",
    deepseek: "deepseek",
    google: "gemini",
    kling: "kling",
    minimax: "minimax",
    moonshot: "moonshot",
    openai: "openai",
    qwen: "qwen",
    siliconflow: "siliconflow",
    vidu: "vidu",
    volcengine: "doubao",
    zhipu: "zhipu",
  }
  return icons[key] || "api"
}

function VendorIconBadge({ iconAsset, label, className }: { iconAsset: string; label: string; className?: string }) {
  const [failed, setFailed] = useState(false)
  return (
    <span className={cn("inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border bg-white p-1 text-xs font-semibold text-slate-600", className)}>
      {failed ? (
        <span>{label.slice(0, 1).toUpperCase()}</span>
      ) : (
        <img
          src={`${adminBasePath}/assets/vendor-icons/${iconAsset || "api"}.svg`}
          alt={label}
          className="h-full w-full object-contain"
          onError={() => setFailed(true)}
        />
      )}
    </span>
  )
}

function displayCategoryForTool(
  tool: Pick<ToolRow, "toolCode" | "toolType" | "executionHandler" | "inputModality" | "outputModality" | "category" | "name" | "configNote">,
) {
  if (isWorkflowTool(tool)) return "工作流"
  if ((tool.toolType || "").toUpperCase() === "AGENT") return "智能体"
  const input = (tool.inputModality || "").toUpperCase()
  const output = (tool.outputModality || "").toUpperCase()
  if (input === "TEXT" && output === "IMAGE") return "文生图"
  if ((input === "TEXT" || input === "IMAGE" || input === "MULTIMODAL") && output === "VIDEO") return input === "TEXT" ? "文生视频" : "图生视频"
  if (input === "TEXT" && output === "TEXT") return "文案生成"
  if (output === "AUDIO") return "文生音频"
  if (input === "AUDIO" && output === "TEXT") return "语音转文字"
  if (tool.category && tool.category.toLowerCase() !== "copywriting") return tool.category
  return optionLabel(modalityOptions, output) || "工具"
}

function shouldShowToolCredits(tool: Pick<ToolRow, "toolType" | "outputModality" | "credits">) {
  if (!tool.credits || tool.credits <= 0) return false
  const output = (tool.outputModality || "").toUpperCase()
  const type = (tool.toolType || "").toUpperCase()
  return output !== "TEXT" && type !== "TEXT_GENERATION"
}

function mapTool(tool: ToolSummary): ToolRow {
  const { note, style } = extractFrontendStyle(tool.configNote)
  const configuredCapabilities = normalizeLegacyRequiredModelCapabilities(tool.requiredModelCapabilities)
  const legacyModelConfigId = tool.modelConfigId ?? null
  const modelConfigIds = Array.from(new Set(
    (tool.modelConfigIds && tool.modelConfigIds.length > 0
      ? tool.modelConfigIds
      : tool.supportedModels && tool.supportedModels.length > 0
        ? tool.supportedModels.map((model) => model.id)
        : legacyModelConfigId != null
          ? [legacyModelConfigId]
          : [])
      .filter((id): id is number => Number.isInteger(id) && id > 0),
  ))
  const supportedDefaultModelConfigId = tool.supportedModels?.find((model) => model.isDefault)?.id ?? null
  const defaultModelConfigId = tool.defaultModelConfigId != null && modelConfigIds.includes(tool.defaultModelConfigId)
    ? tool.defaultModelConfigId
    : supportedDefaultModelConfigId != null && modelConfigIds.includes(supportedDefaultModelConfigId)
      ? supportedDefaultModelConfigId
      : legacyModelConfigId != null && modelConfigIds.includes(legacyModelConfigId)
        ? legacyModelConfigId
        : modelConfigIds[0] ?? null
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
    mediaDisplayMode: style.mediaDisplayMode,
    modelIconUrl: style.modelIconUrl,
    comparisonOriginalUrl: style.comparisonOriginalUrl,
    comparisonEffectUrl: style.comparisonEffectUrl,
    audioPreviewUrl: style.audioPreviewUrl,
    beforeVideoUrl: style.beforeVideoUrl,
    afterVideoUrl: style.afterVideoUrl,
    icon: pickIcon(tool.categoryName),
    credits: tool.estimatedCreditCost ?? 0,
    status: isToolAvailableToUsers(tool),
    rawStatus: tool.status,
    modelConfigId: defaultModelConfigId,
    modelConfigIds,
    defaultModelConfigId,
    modelConfigName: tool.modelConfigName || null,
    modelName: tool.modelName || null,
    executionHandler: tool.executionHandler ?? null,
    requiredModelCapabilities: configuredCapabilities.length > 0
      ? configuredCapabilities
      : defaultRequiredModelCapabilitiesForTool(tool),
  }
}

type ToolStatusFilter = "ALL" | "ONLINE" | "OFFLINE"

function isAgentTool(
  tool: Pick<
    ToolRow,
    "toolCode" | "toolType" | "executionHandler" | "category" | "name" | "inputModality" | "outputModality" | "configNote"
  >,
) {
  return isWorkflowTool(tool)
}

function modelVendorKey(config?: AgentModelConfig | null) {
  if (!config) return "unbound"
  if (config.channelCode?.trim()) return config.channelCode.trim()
  const text = [config.provider, config.displayName, config.modelName, config.baseUrl].filter(Boolean).join(" ").toLowerCase()
  if (text.includes("deepseek")) return "deepseek"
  if (text.includes("doubao") || text.includes("volc") || text.includes("ark.cn")) return "volcengine"
  if (text.includes("siliconflow")) return "siliconflow"
  if (text.includes("qwen") || text.includes("dashscope") || text.includes("aliyun")) return "aliyun"
  if (text.includes("kling")) return "kling"
  if (text.includes("minimax")) return "minimax"
  if (text.includes("openai")) return "openai"
  if (text.includes("suno")) return "suno"
  if (text.includes("vidu")) return "vidu"
  return config.provider || "other"
}

function modelVendorLabel(key: string, config?: AgentModelConfig | null) {
  if (config?.channelLabel?.trim()) return config.channelLabel.trim()
  const labels: Record<string, string> = {
    deepseek: "DeepSeek",
    volcengine: "火山引擎 / 豆包",
    siliconflow: "SiliconFlow",
    aliyun: "阿里云百炼",
    kling: "可灵",
    minimax: "MiniMax",
    suno: "Suno",
    vidu: "Vidu (\u751F\u6570\u79D1\u6280)",
    openai: "OpenAI",
    google: "Google Gemini",
    qwen: "阿里云百炼",
    zhipu: "智谱 GLM",
    moonshot: "Moonshot / Kimi",
    anthropic: "Anthropic Claude",
    unbound: "未绑定模型",
    other: "其他厂商",
  }
  return labels[key] || key
}

function modelContractStatus(config: Pick<AgentModelConfig, "contractStatus">) {
  return (config.contractStatus || "DOCS_PENDING").trim().toUpperCase()
}

function isModelContractReady(config: Pick<AgentModelConfig, "contractStatus">) {
  return modelContractStatus(config) === "READY"
}

type ToolManagementMode = "models" | "agents"

export function ToolManagementPage({ mode = "models" }: { mode?: ToolManagementMode } = {}) {
  const [searchQuery, setSearchQuery] = useState("")
  const [selectedOutputModality, setSelectedOutputModality] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState<ToolStatusFilter>("ALL")
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<ToolRow | null>(null)
  const [toolList, setToolList] = useState<ToolRow[]>([])
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [providerCapabilities, setProviderCapabilities] = useState<Record<string, string[]>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [importResult, setImportResult] = useState<ConfigBundleImportResult | null>(null)
  const [saveFeedback, setSaveFeedback] = useState<{ type: "success" | "error"; title: string; detail: string } | null>(null)
  const [bundleBusy, setBundleBusy] = useState(false)
  const [importingBundle, setImportingBundle] = useState(false)
  const [exportDialogOpen, setExportDialogOpen] = useState(false)
  const [exportToolSearch, setExportToolSearch] = useState("")
  const [selectedExportToolCodes, setSelectedExportToolCodes] = useState<string[]>([])
  const [includeExportMediaAssets, setIncludeExportMediaAssets] = useState(false)
  const [togglingId, setTogglingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [coverUploading, setCoverUploading] = useState(false)
  const [coverDragging, setCoverDragging] = useState(false)
  const [form, setForm] = useState<ToolForm>(initialForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [modelSelectionNotice, setModelSelectionNotice] = useState<string | null>(null)
  const [fieldDialogOpen, setFieldDialogOpen] = useState(false)
  const [fieldTool, setFieldTool] = useState<ToolRow | null>(null)
  const [fieldJson, setFieldJson] = useState("[]")
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [fieldLoading, setFieldLoading] = useState(false)
  const [fieldSaving, setFieldSaving] = useState(false)
  const [fieldEditorMode, setFieldEditorMode] = useState<"visual" | "json">("visual")
  const [editableFields, setEditableFields] = useState<EditableField[]>([])
  const [skillDialogOpen, setSkillDialogOpen] = useState(false)
  const [skillTool, setSkillTool] = useState<ToolRow | null>(null)
  const [skillForm, setSkillForm] = useState<AgentSkillForm>(emptySkillForm)
  const [skillLoading, setSkillLoading] = useState(false)
  const [skillSaving, setSkillSaving] = useState(false)
  const [skillError, setSkillError] = useState<string | null>(null)
  const [toolTemplates, setToolTemplates] = useState<ToolTemplateSummary[]>([])
  const [openVendorGroups, setOpenVendorGroups] = useState<Record<string, boolean>>({})

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [toolsResp, cats, providers] = await Promise.all([
        fetchAllAdminTools(),
        fetchAdminToolCategories().catch(() => [] as ToolCategory[]),
        fetchModelProviders(),
      ])
      setToolList(toolsResp.list.map(mapTool))
      setCategories(cats)
      setProviderCapabilities(
        providers.reduce<Record<string, string[]>>((acc, provider) => {
          acc[provider.code.trim().toLowerCase()] = normalizeModelCapabilities(provider.capabilities)
          return acc
        }, {}),
      )
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

  const selectedExportToolCodeSet = useMemo(() => new Set(selectedExportToolCodes), [selectedExportToolCodes])
  const selectedExportTools = useMemo(
    () => toolList.filter((tool) => selectedExportToolCodeSet.has(tool.toolCode)),
    [selectedExportToolCodeSet, toolList],
  )
  const exportToolOptions = useMemo(() => {
    const keyword = exportToolSearch.trim().toLowerCase()
    return toolList.filter((tool) => {
      if (!keyword) return true
      return [tool.name, tool.toolCode, tool.modelConfigName, tool.modelName, displayCategoryForTool(tool)]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword))
    })
  }, [exportToolSearch, toolList])

  function toggleExportTool(toolCode: string, checked: boolean) {
    setSelectedExportToolCodes((prev) => {
      const next = new Set(prev)
      if (checked) {
        next.add(toolCode)
      } else {
        next.delete(toolCode)
      }
      return Array.from(next)
    })
  }

  function selectVisibleExportTools() {
    setSelectedExportToolCodes((prev) => Array.from(new Set([...prev, ...exportToolOptions.map((tool) => tool.toolCode)])))
  }

  async function handleExportBundle(includeSecrets: boolean) {
    setBundleBusy(true)
    setError(null)
    setNotice(null)
    setImportResult(null)
    try {
      const bundle = await exportConfigBundle({
        includeSecrets,
        toolCodes: selectedExportToolCodes,
        includeMediaAssets: includeExportMediaAssets,
      })
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
    setImportingBundle(true)
    setError(null)
    setNotice(null)
    setImportResult(null)
    try {
      const bundle = await readConfigBundleFile(file)
      const result = await importConfigBundle(bundle)
      await loadAll()
      setImportResult(result)
      setNotice(`导入完成：工具 ${result.tools}、字段 ${result.fields}、提示词版本 ${result.promptVersions}、工作流 ${result.workflows}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "导入配置包失败，请确认 JSON 格式正确")
    } finally {
      setImportingBundle(false)
    }
  }

  async function handleImportEffectAsset(file: File | undefined) {
    if (!file) return
    setFormError(null)
    try {
      const payload = JSON.parse(await file.text()) as Record<string, unknown>
      const coverUrl =
        textFromEffectPayload(payload, "coverUrl", "imageUrl", "image_url", "thumbnailUrl", "thumbnail_url") ||
        textFromEffectPayload((payload.media || {}) as Record<string, unknown>, "coverUrl", "imageUrl", "image_url")
      const audioUrl =
        textFromEffectPayload(payload, "audioUrl", "audio_url", "url") ||
        textFromEffectPayload((payload.media || {}) as Record<string, unknown>, "audioUrl", "audio_url", "url")
      if (!coverUrl && !audioUrl) {
        throw new Error("Imported file does not contain coverUrl/imageUrl or audioUrl.")
      }
      if (coverUrl) updateForm("coverUrl", coverUrl)
      if (audioUrl) updateForm("audioPreviewUrl", audioUrl)
      updateForm("mediaDisplayMode", "effect")
      toast.success("Effect asset imported")
    } catch (err) {
      const message = err instanceof Error ? err.message : "Import effect asset failed"
      setFormError(message)
      toast.error("Import effect asset failed", { description: message })
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  useEffect(() => {
    if (!saveFeedback) return
    const timer = window.setTimeout(() => setSaveFeedback(null), 12000)
    return () => window.clearTimeout(timer)
  }, [saveFeedback])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    return toolList.filter((tool) => {
      const agent = isAgentTool(tool)
      if (mode === "models" && agent) return false
      if (mode === "agents" && !agent) return false
      const matchesKeyword =
        !keyword ||
        [tool.name, tool.description, tool.category, tool.toolCode, tool.modelConfigName, tool.modelName].some(
          (value) => value && value.toLowerCase().includes(keyword),
        )
      const matchesOutput =
        !selectedOutputModality ||
        (tool.outputModality || "").trim().toUpperCase() === selectedOutputModality
      const matchesStatus =
        statusFilter === "ALL" ||
        (statusFilter === "ONLINE" ? tool.status : !tool.status)
      return matchesKeyword && matchesOutput && matchesStatus
    })
  }, [toolList, searchQuery, selectedOutputModality, statusFilter, mode])

  const modelConfigById = useMemo(
    () => new Map(modelConfigs.map((config) => [config.id, config])),
    [modelConfigs],
  )

  const groupedModelTools = useMemo(() => {
    const groups = new Map<string, { label: string; iconAsset: string; tools: ToolRow[] }>()
    for (const tool of filteredTools) {
      const config = tool.modelConfigId ? modelConfigById.get(tool.modelConfigId) : null
      const key = mode === "agents" ? "agent-workflow" : modelVendorKey(config)
      const label = mode === "agents" ? "工作流" : modelVendorLabel(key, config)
      const iconAsset = mode === "agents" ? "api" : vendorIconAssetForKey(key, config)
      const existing = groups.get(key)
      groups.set(key, { label: existing?.label || label, iconAsset: existing?.iconAsset || iconAsset, tools: [...(existing?.tools || []), tool] })
    }
    return [...groups.entries()]
      .sort(([, a], [, b]) => a.label.localeCompare(b.label, "zh-CN"))
      .map(([key, group]) => ({
        key,
        label: group.label,
        iconAsset: group.iconAsset,
        tools: group.tools,
      }))
  }, [filteredTools, modelConfigById, mode])

  const outputModalityFilters = useMemo(() => {
    const counts = new Map<string, number>()
    for (const tool of toolList) {
      const agent = isAgentTool(tool)
      if (mode === "models" && agent) continue
      if (mode === "agents" && !agent) continue
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
  }, [toolList, mode])

  const statusFilterOptions = useMemo(() => {
    const scoped = toolList.filter((tool) => {
      const agent = isAgentTool(tool)
      if (mode === "models" && agent) return false
      if (mode === "agents" && !agent) return false
      return true
    })
    const online = scoped.filter((tool) => tool.status).length
    return [
      { value: "ALL" as const, label: "全部状态", count: scoped.length },
      { value: "ONLINE" as const, label: "已上线", count: online },
      { value: "OFFLINE" as const, label: "未上线", count: scoped.length - online },
    ]
  }, [toolList, mode])

  const availableRequiredModelCapabilities = useMemo(
    () => selectableModelCapabilities(providerCapabilities),
    [providerCapabilities],
  )

  const requiredModelCapabilities = useMemo(
    () => normalizeRequiredModelCapabilities(form.requiredModelCapabilities),
    [form.requiredModelCapabilities],
  )

  const requiredModelCapabilityLabels = useMemo(
    () => requiredModelCapabilities.map(capabilityLabel).join("、"),
    [requiredModelCapabilities],
  )

  const selectedToolTemplate = useMemo(
    () => toolTemplates.find((template) => template.templateCode === form.templateCode) || null,
    [form.templateCode, toolTemplates],
  )

  const requiredExecutionHandler = useMemo(() => {
    if (selectedToolTemplate?.executionHandler) return selectedToolTemplate.executionHandler
    if (
      editingTool?.executionHandler
      && editingTool.toolType.toUpperCase() === form.toolType.toUpperCase()
    ) {
      return editingTool.executionHandler
    }
    return defaultExecutionHandlerForToolType(form.toolType)
  }, [editingTool, form.toolType, selectedToolTemplate])

  const filteredModelConfigs = useMemo(
    () => modelConfigs.filter((c) => modelConfigSupportsToolRequirements(
      c,
      requiredModelCapabilities,
      providerCapabilities,
      requiredExecutionHandler,
    )),
    [modelConfigs, providerCapabilities, requiredExecutionHandler, requiredModelCapabilities],
  )

  const selectedModelConfigs = useMemo(
    () => form.modelConfigIds
      .map((id) => modelConfigs.find((config) => String(config.id) === id))
      .filter((config): config is AgentModelConfig => Boolean(config)),
    [form.modelConfigIds, modelConfigs],
  )

  const integrationPluginId = useMemo(
    () => (editingTool ? resolveIntegrationPluginId(editingTool) : null),
    [editingTool],
  )

  useEffect(() => {
    if (form.modelConfigIds.length === 0) return
    const validIds = form.modelConfigIds.filter((id) => {
      const selected = modelConfigs.find((config) => String(config.id) === id)
      return Boolean(selected && modelConfigSupportsToolRequirements(
        selected,
        requiredModelCapabilities,
        providerCapabilities,
        requiredExecutionHandler,
      ))
    })
    if (validIds.length !== form.modelConfigIds.length) {
      const nextDefault = validIds.includes(form.defaultModelConfigId)
        ? form.defaultModelConfigId
        : validIds.find((id) => {
          const config = modelConfigs.find((item) => String(item.id) === id)
          return Boolean(config && isModelContractReady(config))
        }) || validIds[0] || ""
      setForm((current) => ({
        ...current,
        modelConfigIds: validIds,
        defaultModelConfigId: nextDefault,
        pricingRulesJson: nextDefault === current.defaultModelConfigId ? current.pricingRulesJson : "[]",
      }))
      const notice = `已清除不匹配的模型，请重新选择兼容当前执行处理器且同时支持 ${requiredModelCapabilityLabels || "所需能力"} 的模型。`
      setModelSelectionNotice(notice)
      toast.warning("已清除不匹配的模型", { description: notice })
    }
  }, [form.defaultModelConfigId, form.modelConfigIds, modelConfigs, providerCapabilities, requiredExecutionHandler, requiredModelCapabilities, requiredModelCapabilityLabels])

  async function toggleToolStatus(id: string) {
    const target = toolList.find((tool) => tool.id === id)
    if (!target) return
    const action = target.status ? "下线" : "上线"
    setTogglingId(target.rawId)
    try {
      const updated = target.status
        ? await offlineTool(target.rawId)
        : await publishTool(target.rawId)
      const mapped = mapTool(updated)
      setToolList((prev) => prev.map((tool) => (tool.id === id ? mapped : tool)))
      toast.success(`工具已${action}`, {
        description: target.status
          ? `「${target.name}」已停止接受新任务，运行中的任务不受影响。`
          : `「${target.name}」已通过校验并可供用户使用。`,
      })
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "更新工具状态失败"
      const detail = err instanceof ApiError && err.traceId ? `${message}（traceId: ${err.traceId}）` : message
      setSaveFeedback({ type: "error", title: `工具${action}失败`, detail })
      toast.error(`工具${action}失败`, { description: detail })
    } finally {
      setTogglingId(null)
    }
  }

  function updateForm<K extends keyof ToolForm>(key: K, value: ToolForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function toggleToolModelConfig(config: AgentModelConfig) {
    const id = String(config.id)
    const selected = form.modelConfigIds.includes(id)
    if (!selected && !isModelContractReady(config)) return

    if (selected) {
      const modelConfigIds = form.modelConfigIds.filter((currentId) => currentId !== id)
      const defaultModelConfigId = form.defaultModelConfigId === id
        ? modelConfigIds.find((currentId) => {
          const candidate = modelConfigs.find((item) => String(item.id) === currentId)
          return Boolean(candidate && isModelContractReady(candidate))
        }) || modelConfigIds[0] || ""
        : form.defaultModelConfigId
      setForm((current) => ({
        ...current,
        modelConfigIds,
        defaultModelConfigId,
        pricingRulesJson: defaultModelConfigId === current.defaultModelConfigId ? current.pricingRulesJson : "[]",
      }))
      if (defaultModelConfigId !== form.defaultModelConfigId) void loadPricingRulesJson(defaultModelConfigId)
      return
    }

    const modelConfigIds = [...form.modelConfigIds, id]
    const defaultModelConfigId = form.defaultModelConfigId || id
    setForm((current) => ({ ...current, modelConfigIds, defaultModelConfigId }))
    if (!form.defaultModelConfigId) void loadPricingRulesJson(id)
  }

  function selectDefaultToolModel(modelConfigId: string) {
    const config = modelConfigs.find((item) => String(item.id) === modelConfigId)
    if (!config || !form.modelConfigIds.includes(modelConfigId) || !isModelContractReady(config)) return
    setForm((current) => ({ ...current, defaultModelConfigId: modelConfigId, pricingRulesJson: "[]" }))
    setModelSelectionNotice(null)
    void loadPricingRulesJson(modelConfigId)
  }

  async function loadPricingRulesJson(modelConfigId: string) {
    if (!modelConfigId) {
      updateForm("pricingRulesJson", "[]")
      return
    }
    try {
      const all = await fetchPricingRules()
      const items = pricingRulesForModelExport(all, Number(modelConfigId))
      updateForm("pricingRulesJson", JSON.stringify(items, null, 2))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载定价规则失败"
      toast.error(message)
      updateForm("pricingRulesJson", "[]")
    }
  }

  async function syncModelPricingRules(modelConfigId: number, json: string) {
    const items = parsePricingRulesJson(json)
    const all = await fetchPricingRules()
    const existing = all.filter((rule) => rule.scopeType === "MODEL" && (rule.scopeRef ?? 0) === modelConfigId)
    for (const rule of existing) {
      if (rule.id) await deletePricingRule(rule.id)
    }
    for (const item of items) {
      await savePricingRule(pricingRuleJsonToPayload(modelConfigId, item))
    }
  }

  function selectedModelName(): string {
    const selected = form.defaultModelConfigId
      ? modelConfigs.find((config) => String(config.id) === form.defaultModelConfigId)
      : null
    return selected?.displayName || selected?.modelName || ""
  }

  async function handleCoverUpload(
    file?: File | null,
    target: "coverUrl" | "comparisonOriginalUrl" | "comparisonEffectUrl" | "beforeVideoUrl" | "afterVideoUrl" = "coverUrl",
  ) {
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
      updateForm(target, uploaded.url)
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
      requiredModelCapabilities: defaultRequiredModelCapabilitiesForTool(
        { toolType: initialForm.toolType },
      ),
    })
    setFormError(null)
    setModelSelectionNotice(null)
    setCoverUploading(false)
    setCoverDragging(false)
    setIsAddDialogOpen(true)
  }

  function updateToolType(value: string) {
    const defaults = defaultModalitiesByType[value] || defaultModalitiesByType.TEXT_GENERATION
    const templateCode = templateCodeByToolType[value] || ""
    const template = toolTemplates.find((item) => item.templateCode === templateCode)
    setForm((prev) => ({
      ...prev,
      toolType: value,
      inputModality: defaults.input,
      outputModality: defaults.output,
      templateCode,
      requiredModelCapabilities: defaultRequiredModelCapabilitiesForTool(
        { toolType: value, executionHandler: template?.executionHandler },
      ),
    }))
    setModelSelectionNotice(null)
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
      requiredModelCapabilities: defaultRequiredModelCapabilitiesForTool(
        { toolType: template.toolType, executionHandler: template.executionHandler },
      ),
    }))
    setModelSelectionNotice(null)
  }

  function toggleRequiredModelCapability(capability: string) {
    const normalized = normalizeRequiredModelCapabilities(form.requiredModelCapabilities)
    const selected = normalized.includes(capability)
    if (selected && normalized.length === 1) {
      setFormError("请至少保留一种所需模型能力。")
      return
    }
    setFormError(null)
    setForm((current) => ({
      ...current,
      requiredModelCapabilities: selected
        ? normalized.filter((item) => item !== capability)
        : [...normalized, capability],
    }))
  }

  async function openEditDialog(tool: ToolRow) {
    let resolvedTool = tool
    try {
      resolvedTool = mapTool(await fetchAdminToolDetail(tool.rawId))
    } catch (err) {
      toast.warning("未能加载完整模型绑定", {
        description: err instanceof ApiError ? err.message : "将按列表中的默认模型继续编辑。",
      })
    }
    setEditingTool(resolvedTool)
    const modelIds = resolvedTool.modelConfigIds.map(String)
    const defaultModelId = resolvedTool.defaultModelConfigId ? String(resolvedTool.defaultModelConfigId) : modelIds[0] || ""
    setForm({
      toolCode: resolvedTool.toolCode,
      toolName: resolvedTool.name,
      description: resolvedTool.description === "暂无描述" ? "" : resolvedTool.description,
      categoryId: resolvedTool.categoryId ? String(resolvedTool.categoryId) : "",
      toolType: resolvedTool.toolType,
      inputModality: resolvedTool.inputModality,
      outputModality: resolvedTool.outputModality,
      configNote: resolvedTool.configNote || "",
      coverUrl: resolvedTool.coverUrl || "",
      mediaDisplayMode: resolvedTool.mediaDisplayMode || "icon",
      modelIconUrl: resolvedTool.modelIconUrl || "",
      comparisonOriginalUrl: resolvedTool.comparisonOriginalUrl || "",
      comparisonEffectUrl: resolvedTool.comparisonEffectUrl || "",
      audioPreviewUrl: resolvedTool.audioPreviewUrl || "",
      beforeVideoUrl: resolvedTool.beforeVideoUrl || "",
      afterVideoUrl: resolvedTool.afterVideoUrl || "",
      estimatedCreditCost: String(resolvedTool.credits),
      pricingRulesJson: "[]",
      modelConfigIds: modelIds,
      defaultModelConfigId: defaultModelId,
      templateCode: "",
      requiredModelCapabilities: resolvedTool.requiredModelCapabilities,
    })
    setFormError(null)
    setModelSelectionNotice(null)
    setCoverUploading(false)
    setCoverDragging(false)
    setIsAddDialogOpen(true)
    if (defaultModelId) void loadPricingRulesJson(defaultModelId)
  }

  function reportSaveValidationError(message: string) {
    setFormError(message)
    toast.error("无法保存", { description: message })
  }

  async function handleSaveTool() {
    setFormError(null)
    setSaveFeedback(null)
    if (!form.toolName.trim()) {
      reportSaveValidationError("请填写工具名称。")
      return
    }
    if (!form.categoryId) {
      reportSaveValidationError(categories.length === 0 ? "请先在「分类管理」中创建一个工具分类。" : "请选择工具分类。")
      return
    }
    const credits = Number(form.estimatedCreditCost)
    if (!Number.isFinite(credits) || credits < 0) {
      reportSaveValidationError("消耗算力必须是大于等于 0 的数字。")
      return
    }
    if (requiredModelCapabilities.length === 0) {
      reportSaveValidationError("请至少选择一种所需模型能力。")
      return
    }
    const unsupportedRequiredModelCapabilities = requiredModelCapabilities.filter(
      (capability) => !availableRequiredModelCapabilities.includes(capability),
    )
    if (unsupportedRequiredModelCapabilities.length > 0) {
      reportSaveValidationError(`存在不可选的模型能力：${unsupportedRequiredModelCapabilities.join("、")}，请重新选择。`)
      return
    }
    if (form.modelConfigIds.length === 0) {
      reportSaveValidationError(`请至少绑定一个同时支持「${requiredModelCapabilityLabels}」的契约就绪模型。`)
      return
    }
    const existingModelConfigIds = new Set((editingTool?.modelConfigIds || []).map(String))
    const existingDefaultModelConfigId = String(editingTool?.defaultModelConfigId || "")
    const selectionChanged = existingModelConfigIds.size !== form.modelConfigIds.length
      || form.modelConfigIds.some((modelConfigId) => !existingModelConfigIds.has(modelConfigId))
      || existingDefaultModelConfigId !== form.defaultModelConfigId
    for (const modelConfigId of form.modelConfigIds) {
      const selectedModel = modelConfigs.find((config) => String(config.id) === modelConfigId)
      if (!selectedModel) {
        reportSaveValidationError(`模型 #${modelConfigId} 不可用或不存在，请重新选择。`)
        return
      }
      if (!modelConfigSupportsToolRequirements(
        selectedModel,
        requiredModelCapabilities,
        providerCapabilities,
        requiredExecutionHandler,
      )) {
        reportSaveValidationError(`模型「${selectedModel.displayName || selectedModel.modelName}」未同时支持「${requiredModelCapabilityLabels}」或不兼容当前执行处理器。`)
        return
      }
      if (!isModelContractReady(selectedModel) && !existingModelConfigIds.has(modelConfigId)) {
        reportSaveValidationError(`模型「${selectedModel.displayName || selectedModel.modelName}」的 API 文档待补，暂不能新增绑定。`)
        return
      }
      if (!isModelContractReady(selectedModel) && selectionChanged) {
        reportSaveValidationError(`模型「${selectedModel.displayName || selectedModel.modelName}」仍处于文档待补；修改绑定前请先将它移出当前工具。`)
        return
      }
    }
    if (!form.defaultModelConfigId || !form.modelConfigIds.includes(form.defaultModelConfigId)) {
      reportSaveValidationError("请从已绑定模型中指定一个默认模型。")
      return
    }
    const defaultModel = modelConfigs.find((config) => String(config.id) === form.defaultModelConfigId)
    if (!defaultModel) {
      reportSaveValidationError("默认模型不可用或不存在，请重新选择。")
      return
    }
    if (
      !isModelContractReady(defaultModel)
      && existingDefaultModelConfigId !== form.defaultModelConfigId
    ) {
      reportSaveValidationError(`模型「${defaultModel.displayName || defaultModel.modelName}」的 API 文档待补，不能设为新的默认模型。`)
      return
    }
    try {
      parsePricingRulesJson(form.pricingRulesJson)
    } catch (err) {
      reportSaveValidationError(err instanceof Error ? err.message : "定价规则 JSON 格式无效")
      return
    }
    if (form.mediaDisplayMode === "comparison" && (!form.comparisonOriginalUrl.trim() || !form.comparisonEffectUrl.trim())) {
      reportSaveValidationError("选择「效果对比」时，请同时配置原图和模型效果图。")
      return
    }
    setSubmitting(true)
    const toastId = toast.loading(editingTool ? "正在保存工具..." : "正在创建工具...")
    try {
      const style = {
        mediaDisplayMode: form.mediaDisplayMode,
        modelIconUrl: form.modelIconUrl,
        comparisonOriginalUrl: form.comparisonOriginalUrl,
        comparisonEffectUrl: form.comparisonEffectUrl,
        audioPreviewUrl: form.audioPreviewUrl,
        beforeVideoUrl: form.beforeVideoUrl,
        afterVideoUrl: form.afterVideoUrl,
      }
      let preservedMarkers: string[] | undefined
      if (integrationPluginId && editingTool) {
        const toolsResp = await fetchAllAdminTools()
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
        modelConfigId: Number(form.defaultModelConfigId),
        modelConfigIds: form.modelConfigIds.map(Number),
        defaultModelConfigId: Number(form.defaultModelConfigId),
        executionHandler: requiredExecutionHandler,
        requiredModelCapabilities,
        templateCode: !editingTool && form.templateCode ? form.templateCode : undefined,
      }
      let saved: ToolSummary
      if (editingTool) {
        saved = await updateTool(editingTool.rawId, payload)
        setToolList((prev) => prev.map((tool) => (tool.rawId === editingTool.rawId ? mapTool(saved) : tool)))
      } else {
        saved = await createTool(payload)
        setToolList((prev) => [mapTool(saved), ...prev])
      }
      await syncModelPricingRules(Number(form.defaultModelConfigId), form.pricingRulesJson)
      const successTitle = editingTool ? "工具已保存" : "工具已创建"
      const successDetail = `「${saved.toolName}」的资料已保存；是否对用户开放，以列表中的上线开关为准。`
      setNotice(successDetail)
      setSaveFeedback({ type: "success", title: successTitle, detail: successDetail })
      toast.success(successTitle, { id: toastId, description: successDetail })
      setError(null)
      setForm(initialForm)
      setModelSelectionNotice(null)
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
      setSaveFeedback({
        type: "error",
        title: "保存工具失败",
        detail: err instanceof ApiError && err.traceId ? `${message}（traceId: ${err.traceId}）` : message,
      })
      toast.error("保存工具失败", { id: toastId, description: message })
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

  function defaultSkillCodeForTool(tool: ToolRow): string {
    const code = `${tool.toolCode} ${tool.toolType} ${tool.outputModality}`.toLowerCase()
    if (code.includes("image") || code.includes("图")) return "image_generation"
    if (code.includes("video")) return "video_generation"
    if (code.includes("music") || code.includes("audio")) return "audio_generation"
    return `${tool.toolCode}_skill`.replace(/[^a-zA-Z0-9_]/g, "_").replace(/_+/g, "_")
  }

  function formFromSkill(skill: AgentSkillBundle): AgentSkillForm {
    return {
      skillCode: skill.skillCode,
      displayName: skill.displayName || skill.skillCode,
      description: skill.description || "",
      toolCodesText: (skill.toolCodes || []).join(", "),
      whenToUse: skill.whenToUse || "",
      whenNotToUse: skill.whenNotToUse || "",
      sopRules: skill.sopRules || "",
      examplesJson: JSON.stringify(skill.examples ?? [], null, 2),
      fieldPolicyJson: JSON.stringify(skill.fieldPolicy ?? {}, null, 2),
      status: skill.status || "DRAFT",
      version: skill.version,
    }
  }

  async function openSkillDialog(tool: ToolRow) {
    const skillCode = defaultSkillCodeForTool(tool)
    setSkillTool(tool)
    setSkillDialogOpen(true)
    setSkillError(null)
    setSkillLoading(true)
    setSkillForm({ ...emptySkillForm, skillCode, displayName: skillCode })
    try {
      const skill = await fetchAgentSkill(skillCode)
      setSkillForm(formFromSkill(skill))
    } catch (err) {
      setSkillError(err instanceof ApiError ? err.message : "加载 Agent Skill 失败，可先保存为新草稿")
      setSkillForm({
        ...emptySkillForm,
        skillCode,
        displayName: skillCode === "image_generation" ? "图像生成" : skillCode,
        description: tool.description || tool.name,
        toolCodesText: tool.toolCode,
      })
    } finally {
      setSkillLoading(false)
    }
  }

  function skillPayloadFromForm() {
    let examples: unknown
    let fieldPolicy: unknown
    try {
      examples = skillForm.examplesJson.trim() ? JSON.parse(skillForm.examplesJson) : []
      fieldPolicy = skillForm.fieldPolicyJson.trim() ? JSON.parse(skillForm.fieldPolicyJson) : {}
    } catch {
      throw new Error("示例或字段策略 JSON 格式错误")
    }
    return {
      displayName: skillForm.displayName,
      description: skillForm.description,
      toolCodes: skillForm.toolCodesText.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean),
      whenToUse: skillForm.whenToUse,
      whenNotToUse: skillForm.whenNotToUse,
      sopRules: skillForm.sopRules,
      examples,
      fieldPolicy,
    }
  }

  async function saveSkillDraft() {
    if (!skillForm.skillCode) return
    setSkillSaving(true)
    setSkillError(null)
    try {
      const saved = await saveAgentSkillDraft(skillForm.skillCode, skillPayloadFromForm())
      setSkillForm(formFromSkill(saved))
      toast.success("Agent Skill 草稿已保存")
    } catch (err) {
      setSkillError(err instanceof Error ? err.message : "保存 Agent Skill 失败")
    } finally {
      setSkillSaving(false)
    }
  }

  async function publishSkillDraft() {
    if (!skillForm.skillCode) return
    setSkillSaving(true)
    setSkillError(null)
    try {
      await saveAgentSkillDraft(skillForm.skillCode, skillPayloadFromForm())
      const saved = await publishAgentSkill(skillForm.skillCode)
      setSkillForm(formFromSkill(saved))
      toast.success("Agent Skill 已发布")
    } catch (err) {
      setSkillError(err instanceof ApiError ? err.message : "发布 Agent Skill 失败")
    } finally {
      setSkillSaving(false)
    }
  }

  const headerDescription = error
    ? `操作失败：${error}`
    : notice
      ? notice
    : loading
      ? "正在加载工具列表..."
      : mode === "agents"
        ? "管理工作流工具、上线状态和工作流画布。"
        : "按模型厂商管理大模型工具，卡片预览为用户端实际展示效果。"

  return (
    <AdminLayout>
      <AdminHeader title={mode === "agents" ? "工作流管理" : "大模型管理"} description={headerDescription} />

      <div className="space-y-6 p-6">
        {saveFeedback ? (
          <Alert
            variant={saveFeedback.type === "error" ? "destructive" : "default"}
            className={cn(
              "relative pr-10",
              saveFeedback.type === "success" && "border-emerald-500/40 bg-emerald-500/10",
            )}
          >
            {saveFeedback.type === "success" ? <CheckCircle2 className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
            <AlertTitle>{saveFeedback.title}</AlertTitle>
            <AlertDescription>{saveFeedback.detail}</AlertDescription>
            <button
              type="button"
              className="absolute right-3 top-3 rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
              aria-label="关闭提示"
              onClick={() => setSaveFeedback(null)}
            >
              <X className="h-4 w-4" />
            </button>
          </Alert>
        ) : null}
        {importResult ? (
          <Alert
            variant={importResult.warnings?.length ? "default" : "default"}
            className={cn(importResult.warnings?.length ? "border-amber-500/40 bg-amber-500/10" : "border-emerald-500/40 bg-emerald-500/10")}
          >
            {importResult.warnings?.length ? <AlertCircle className="h-4 w-4 text-amber-500" /> : <CheckCircle2 className="h-4 w-4 text-emerald-500" />}
            <AlertTitle>配置包导入结果</AlertTitle>
            <AlertDescription>
              <div className="space-y-3">
                <p>
                  模型 {importResult.modelConfigs}、分类 {importResult.categories}、工具 {importResult.tools}、字段 {importResult.fields}、提示词版本 {importResult.promptVersions}、工作流 {importResult.workflows}
                </p>
                {importResult.warnings?.length ? (
                  <div className="rounded-md border border-amber-500/25 bg-background/60 p-3">
                    <div className="mb-2 text-xs font-medium">发现 {importResult.warnings.length} 条需要处理的问题</div>
                    <ul className="max-h-60 space-y-1 overflow-auto text-xs">
                      {importResult.warnings.slice(0, 20).map((warning, index) => (
                        <li key={`${warning}-${index}`} className="rounded bg-muted/60 px-2 py-1">
                          {warning}
                        </li>
                      ))}
                    </ul>
                    {importResult.warnings.length > 20 ? (
                      <p className="mt-2 text-xs text-muted-foreground">还有 {importResult.warnings.length - 20} 条未显示。</p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </AlertDescription>
            <button
              type="button"
              className="absolute right-3 top-3 rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
              aria-label="关闭导入结果"
              onClick={() => setImportResult(null)}
            >
              <X className="h-4 w-4" />
            </button>
          </Alert>
        ) : null}
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
          <div className="relative w-full min-w-0 sm:max-w-md sm:flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索工具名称、分类、描述..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <div className="flex w-full min-w-0 flex-wrap items-center gap-2 sm:w-auto sm:flex-nowrap">
            <Button variant="outline" className="gap-2" onClick={() => setExportDialogOpen(true)} disabled={bundleBusy || loading}>
              <Download className="h-4 w-4" />
              导出
            </Button>
            <Button variant="outline" className="relative gap-2" disabled={bundleBusy || importingBundle || loading}>
              {importingBundle ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
              {importingBundle ? "导入中..." : "导入"}
              <input
                type="file"
                accept="application/json,.json"
                className="absolute inset-0 cursor-pointer opacity-0"
                disabled={bundleBusy || importingBundle || loading}
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

        <div className="flex flex-wrap items-center gap-3">
          <div className="min-w-[160px] space-y-1">
            <Label className="text-xs text-muted-foreground">上线状态</Label>
            <Select value={statusFilter} onValueChange={(value) => setStatusFilter(value as ToolStatusFilter)}>
              <SelectTrigger className="h-9 bg-background">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {statusFilterOptions.map((item) => (
                  <SelectItem key={item.value} value={item.value}>
                    {item.label} {item.count}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="min-w-[180px] space-y-1">
            <Label className="text-xs text-muted-foreground">输出模态</Label>
            <Select value={selectedOutputModality ?? "ALL"} onValueChange={(value) => setSelectedOutputModality(value === "ALL" ? null : value)}>
              <SelectTrigger className="h-9 bg-background">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部 {statusFilterOptions[0]?.count ?? toolList.length}</SelectItem>
                {outputModalityFilters.map((item) => (
                  <SelectItem key={item.key} value={item.key}>
                    {item.label} {item.count}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

          <Dialog
            open={isAddDialogOpen}
            onOpenChange={(open) => {
              setIsAddDialogOpen(open)
              if (!open) {
                setForm(initialForm)
                setEditingTool(null)
                setFormError(null)
                setModelSelectionNotice(null)
                setCoverUploading(false)
                setCoverDragging(false)
              }
            }}
          >
            <DialogContent
              className={cn(
                "max-h-[92vh] overflow-y-auto border-border bg-card [&>*]:min-w-0",
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
                  <label className="inline-flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-xs font-medium hover:bg-secondary">
                    <Upload className="h-3.5 w-3.5" />
                    导入模型效果素材 JSON
                    <input
                      type="file"
                      accept="application/json,.json"
                      className="hidden"
                      onChange={(event) => {
                        const file = event.target.files?.[0]
                        void handleImportEffectAsset(file)
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
                      <Select value={form.mediaDisplayMode} onValueChange={(value) => updateForm("mediaDisplayMode", value as "icon" | "effect" | "comparison")}>
                        <SelectTrigger className="w-full min-w-0">
                          <SelectValue placeholder="选择模型卡片展示方式" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="icon">模型图标</SelectItem>
                          <SelectItem value="effect">模型效果</SelectItem>
                          <SelectItem value="comparison">效果对比</SelectItem>
                        </SelectContent>
                      </Select>
                      <p className="text-xs text-muted-foreground">
                        模型图标会在 C 端展示圆形图标；模型效果使用展示素材；效果对比会用原图和效果图做自动扫线封面预览。
                      </p>
                    </div>
                    <div className="space-y-2 sm:col-span-2">
                      <Label>模型图标 URL</Label>
                      <Input
                        value={form.modelIconUrl}
                        onChange={(event) => updateForm("modelIconUrl", event.target.value)}
                        placeholder="用于模型卡片；不填则按绑定模型或展示素材自动生成"
                      />
                    </div>
                    <div className="space-y-2 sm:col-span-2">
                      <Label>音频预览 URL</Label>
                      <Input
                        value={form.audioPreviewUrl}
                        onChange={(event) => updateForm("audioPreviewUrl", event.target.value)}
                        placeholder="Suno 生成的 mp3/m4a 地址；配合展示素材 URL 作为封面"
                      />
                      {form.audioPreviewUrl.trim() ? (
                        <audio src={normalizeToolMediaUrl(form.audioPreviewUrl)} controls preload="metadata" className="w-full" />
                      ) : (
                        <p className="text-xs text-muted-foreground">音频工具可填入试听地址，保存后会写入 ai-tool-ui.audioPreviewUrl。</p>
                      )}
                    </div>
                    {form.mediaDisplayMode === "comparison" ? (
                      <div className="space-y-3 rounded-lg border border-border bg-card p-3 sm:col-span-2">
                        <div>
                          <Label>模型效果对比</Label>
                          <p className="mt-1 text-xs text-muted-foreground">左侧放原始素材，右侧放模型效果。每侧可独立上传图片或视频，支持任意组合（图-图、图-视频、视频-图、视频-视频）。</p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <div className="space-y-2">
                            <Label>原始素材 URL（图片/视频）</Label>
                            <Input
                              value={form.comparisonOriginalUrl}
                              onChange={(event) => updateForm("comparisonOriginalUrl", event.target.value)}
                              placeholder="上传或填写原始素材地址"
                            />
                            <label className="inline-flex cursor-pointer items-center rounded-md border px-3 py-2 text-xs font-medium hover:bg-secondary">
                              上传原始素材
                              <input
                                type="file"
                                accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,video/quicktime"
                                className="hidden"
                                onChange={(event) => {
                                  const file = event.target.files?.[0]
                                  void handleCoverUpload(file, "comparisonOriginalUrl")
                                  event.currentTarget.value = ""
                                }}
                              />
                            </label>
                            {form.comparisonOriginalUrl.trim() ? (
                              /\.(mp4|webm|mov|m4v)(\?|$)/i.test(form.comparisonOriginalUrl) ? (
                                <video src={normalizeToolMediaUrl(form.comparisonOriginalUrl)} controls preload="metadata" className="w-full rounded" />
                              ) : (
                                <img src={normalizeToolMediaUrl(form.comparisonOriginalUrl)} alt="original preview" className="w-full rounded" />
                              )
                            ) : null}
                          </div>
                          <div className="space-y-2">
                            <Label>模型效果 URL（图片/视频）</Label>
                            <Input
                              value={form.comparisonEffectUrl}
                              onChange={(event) => updateForm("comparisonEffectUrl", event.target.value)}
                              placeholder="上传或填写效果素材地址"
                            />
                            <label className="inline-flex cursor-pointer items-center rounded-md border px-3 py-2 text-xs font-medium hover:bg-secondary">
                              上传效果素材
                              <input
                                type="file"
                                accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,video/quicktime"
                                className="hidden"
                                onChange={(event) => {
                                  const file = event.target.files?.[0]
                                  void handleCoverUpload(file, "comparisonEffectUrl")
                                  event.currentTarget.value = ""
                                }}
                              />
                            </label>
                            {form.comparisonEffectUrl.trim() ? (
                              /\.(mp4|webm|mov|m4v)(\?|$)/i.test(form.comparisonEffectUrl) ? (
                                <video src={normalizeToolMediaUrl(form.comparisonEffectUrl)} controls preload="metadata" className="w-full rounded" />
                              ) : (
                                <img src={normalizeToolMediaUrl(form.comparisonEffectUrl)} alt="effect preview" className="w-full rounded" />
                              )
                            ) : null}
                          </div>
                        </div>
                      </div>
                    ) : null}
                  </div>
                  <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-3">
                    {form.mediaDisplayMode === "comparison" && form.comparisonOriginalUrl.trim() && form.comparisonEffectUrl.trim() ? (
                      <ComparisonSweepPreview
                        originalUrl={normalizeToolMediaUrl(form.comparisonOriginalUrl)}
                        effectUrl={normalizeToolMediaUrl(form.comparisonEffectUrl)}
                        originalAlt="comparison original preview"
                        effectAlt="comparison effect preview"
                        className="h-16 w-28 shrink-0 rounded-lg ring-1 ring-border"
                        showFooter={false}
                      />
                    ) : form.modelIconUrl.trim() ? (
                      <div className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full ring-1 ring-border bg-muted/40">
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
                    ) : form.audioPreviewUrl.trim() ? (
                      <div className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full ring-1 ring-border bg-muted/40">
                        <Music className="h-5 w-5 text-primary" />
                      </div>
                    ) : (
                    <div className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full ring-1 ring-border bg-muted/40">
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
                        {form.description || "工具描述预览"}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label>分类</Label>
                    <Select value={form.categoryId} onValueChange={(value) => updateForm("categoryId", value)}>
                      <SelectTrigger className="w-full min-w-0">
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
                    <Label>预估算力（兜底）</Label>
                    <Input
                      type="number"
                      value={form.estimatedCreditCost}
                      onChange={(event) => updateForm("estimatedCreditCost", event.target.value)}
                    />
                    <p className="text-xs text-muted-foreground">无法按模型计价时的静态兜底；有参数规则时以前端实时预估为准。</p>
                  </div>
                </div>
                {form.defaultModelConfigId ? (
                  <div className="space-y-2 rounded-lg border border-border bg-secondary/20 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <Label>参数定价规则 JSON</Label>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            updateForm("pricingRulesJson", JSON.stringify(HAPPYHORSE_PRICING_RULES_EXAMPLE, null, 2))
                          }
                        >
                          填入 HappyHorse 示例
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            updateForm("pricingRulesJson", JSON.stringify(GPT_IMAGE2_PRICING_RULES_EXAMPLE, null, 2))
                          }
                        >
                          填入 GPT Image2 示例
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            updateForm("pricingRulesJson", JSON.stringify(KLING_VIDEO_PRICING_RULES_EXAMPLE, null, 2))
                          }
                        >
                          填入可灵视频示例
                        </Button>
                      </div>
                    </div>
                    <Textarea
                      rows={8}
                      className="font-mono text-xs"
                      value={form.pricingRulesJson}
                      onChange={(event) => updateForm("pricingRulesJson", event.target.value)}
                      placeholder='[{"paramKey":"resolution","ruleType":"MULTIPLIER","matchOp":"EQ","matchValue":"1080P","factor":1.7778,"priority":50}]'
                    />
                    <p className="text-xs text-muted-foreground">
                      保存工具时自动写入 pricing_rules，无需手写 SQL。枚举参数用 matchOp=EQ；数量类参数用 matchOp=VALUE（如 count=3 即 ×3）。
                      也可在「定价配置」页单独维护；导出配置包时写入 modelConfigs.pricingRules。
                    </p>
                  </div>
                ) : (
                  <p className="text-xs text-muted-foreground">
                    指定默认模型后，可在此编辑该模型的参数倍率 JSON（resolution / duration 等）。
                  </p>
                )}
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
                      <SelectTrigger className="w-full min-w-0">
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
                  <Label>工具类型</Label>
                  <Select value={form.toolType} onValueChange={updateToolType}>
                    <SelectTrigger className="w-full min-w-0">
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
                <div className="space-y-3 rounded-lg border border-border bg-secondary/30 p-3">
                  <Label>所需模型能力</Label>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {availableRequiredModelCapabilities.map((capability) => {
                      const checked = requiredModelCapabilities.includes(capability)
                      const capabilityControlId = `required-model-capability-${capability.toLowerCase()}`
                      return (
                        <div
                          key={capability}
                          className={cn(
                            "flex min-h-10 items-center gap-2 rounded-md border px-3 py-2 text-sm transition-colors",
                            checked
                              ? "border-primary/60 bg-primary/10 text-foreground"
                              : "border-border bg-background text-muted-foreground hover:text-foreground",
                          )}
                        >
                          <Checkbox
                            id={capabilityControlId}
                            checked={checked}
                            onCheckedChange={() => toggleRequiredModelCapability(capability)}
                          />
                          <Label
                            htmlFor={capabilityControlId}
                            className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 font-normal"
                          >
                            <span className="min-w-0 flex-1">{capabilityLabel(capability)}</span>
                            <span className="break-all font-mono text-[10px] opacity-70">{capability}</span>
                          </Label>
                        </div>
                      )
                    })}
                  </div>
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label>输入模态</Label>
                    <Select value={form.inputModality} onValueChange={(value) => updateForm("inputModality", value)}>
                      <SelectTrigger className="w-full min-w-0">
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
                      <SelectTrigger className="w-full min-w-0">
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
                  {integrationPluginId && editingTool ? (
                    <div className="space-y-3 rounded-lg border border-border p-3">
                      <p className="text-xs text-amber-700">
                        工作台类工具的大模型绑定保存在下方「保存配置」中；仅点对话框底部「保存工具」不会写入文本/文生图模型。
                      </p>
                      <ToolIntegrationApiSection
                        pluginId={integrationPluginId}
                        toolId={editingTool.rawId}
                        onSaved={async () => {
                          const toolsResp = await fetchAllAdminTools()
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
                    <div className="space-y-3 rounded-md border border-border p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div>
                          <p className="text-sm font-medium">绑定模型</p>
                          <p className="mt-0.5 text-xs text-muted-foreground">所需能力：{requiredModelCapabilityLabels || "未选择"}</p>
                        </div>
                        <Badge variant="outline">已选 {form.modelConfigIds.length}</Badge>
                      </div>
                      {filteredModelConfigs.length > 0 ? (
                        <ScrollArea className="h-56 rounded-md border bg-background">
                          <div className="divide-y">
                            {filteredModelConfigs.map((config) => {
                              const id = String(config.id)
                              const checked = form.modelConfigIds.includes(id)
                              const ready = isModelContractReady(config)
                              const disabled = !ready && !checked
                              const controlId = `tool-model-${config.id}`
                              return (
                                <div
                                  key={config.id}
                                  className={cn(
                                    "flex min-h-14 items-center gap-3 px-3 py-2",
                                    checked ? "bg-primary/5" : "hover:bg-muted/40",
                                    disabled && "opacity-60",
                                  )}
                                >
                                  <Checkbox
                                    id={controlId}
                                    checked={checked}
                                    disabled={disabled}
                                    onCheckedChange={() => {
                                      toggleToolModelConfig(config)
                                      setModelSelectionNotice(null)
                                    }}
                                  />
                                  <Label htmlFor={controlId} className={cn("min-w-0 flex-1", disabled ? "cursor-not-allowed" : "cursor-pointer")}>
                                    <span className="block truncate text-sm font-medium">{config.displayName || config.modelName}</span>
                                    <span className="mt-0.5 block truncate text-xs font-normal text-muted-foreground">
                                      {config.provider} · {config.modelName}
                                    </span>
                                  </Label>
                                  {ready ? (
                                    <Badge variant="outline" className="shrink-0 border-emerald-200 bg-emerald-50 text-emerald-700">可绑定</Badge>
                                  ) : (
                                    <Badge variant="outline" className="shrink-0 border-amber-200 bg-amber-50 text-amber-800">文档待补</Badge>
                                  )}
                                </div>
                              )
                            })}
                          </div>
                        </ScrollArea>
                      ) : (
                        <div className="rounded-md border border-dashed px-3 py-6 text-center text-sm text-muted-foreground">
                          暂无匹配模型配置
                        </div>
                      )}
                      {selectedModelConfigs.length > 0 ? (
                        <div className="space-y-2 border-t pt-3">
                          <Label>默认模型</Label>
                          <RadioGroup
                            value={form.defaultModelConfigId}
                            onValueChange={selectDefaultToolModel}
                            className="grid gap-2 sm:grid-cols-2"
                          >
                            {selectedModelConfigs.map((config) => {
                              const id = String(config.id)
                              const ready = isModelContractReady(config)
                              const controlId = `tool-default-model-${config.id}`
                              return (
                                <div key={config.id} className="flex min-w-0 items-center gap-2 rounded-md border px-3 py-2">
                                  <RadioGroupItem id={controlId} value={id} disabled={!ready} />
                                  <Label htmlFor={controlId} className={cn("min-w-0 flex-1 truncate font-normal", ready ? "cursor-pointer" : "cursor-not-allowed text-muted-foreground")}>
                                    {config.displayName || config.modelName}
                                  </Label>
                                </div>
                              )
                            })}
                          </RadioGroup>
                        </div>
                      ) : null}
                      {modelSelectionNotice ? (
                        <p role="status" className="text-xs text-amber-700">{modelSelectionNotice}</p>
                      ) : null}
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

        <div className="space-y-4">
          {groupedModelTools.length === 0 ? (
            <div className="rounded-lg border border-dashed border-border bg-card px-6 py-12 text-center text-sm text-muted-foreground">
              {mode === "agents" ? "暂无匹配的工作流。" : "暂无匹配的大模型工具。"}
            </div>
          ) : groupedModelTools.map((group) => {
            const isOpen = openVendorGroups[group.key] ?? true
            return (
              <Collapsible
                key={group.key}
                open={isOpen}
                onOpenChange={(open) => setOpenVendorGroups((prev) => ({ ...prev, [group.key]: open }))}
                className="overflow-hidden rounded-xl border border-border bg-card"
              >
                <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
                  <CollapsibleTrigger className="flex min-w-0 flex-1 items-center gap-3 text-left">
                    <ChevronDown className={cn("h-4 w-4 shrink-0 transition-transform", !isOpen && "-rotate-90")} />
                    <VendorIconBadge iconAsset={group.iconAsset} label={group.label} />
                    <div className="min-w-0">
                      <h2 className="truncate text-base font-semibold text-card-foreground">{group.label}</h2>
                      <p className="text-xs text-muted-foreground">
                        {mode === "agents"
                          ? "工作流 · 点击展开或收起"
                          : `${group.tools.length} 个模型 · 卡片预览为用户端实际展示效果`}
                      </p>
                    </div>
                  </CollapsibleTrigger>
                  <div className="flex items-center gap-2">
                    <Badge variant="secondary">{group.tools.length} 个工具</Badge>
                    <Badge variant="outline" className="text-xs">
                      {group.tools.filter((tool) => tool.status).length} 已上线
                    </Badge>
                  </div>
                </div>
                <CollapsibleContent className="px-4 py-4">
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-[repeat(auto-fill,minmax(280px,1fr))]">
                    {group.tools.map((tool) => (
                      <div
                        key={tool.id}
                        className={cn(
                          "group overflow-hidden rounded-xl border border-border bg-card transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5",
                          !tool.status && "opacity-70",
                        )}
                      >
                        <ToolUserPreviewCard tool={tool} className="rounded-none border-0 shadow-none" />
                        <div className="space-y-3 border-t border-border p-3">
                          <div className="flex items-start justify-between gap-2">
                            <div className="min-w-0">
                              <p className="truncate text-sm font-medium">{tool.name}</p>
                              <div className="mt-1 flex flex-wrap gap-1.5">
                                <Badge variant="secondary" className="text-[10px] font-normal">
                                  {displayCategoryForTool(tool)}
                                </Badge>
                                {!tool.status ? (
                                  <Badge variant="outline" className="text-[10px] font-normal text-muted-foreground">
                                    未上线
                                  </Badge>
                                ) : null}
                              </div>
                            </div>
                            <div className="flex shrink-0 items-center gap-1.5">
                              {shouldShowToolCredits(tool) ? (
                                <span className="inline-flex items-center rounded-full border border-primary/20 bg-primary/5 px-2 py-0.5 text-[10px] font-medium">
                                  {tool.credits} 算力
                                </span>
                              ) : null}
                              <EmbeddedOnOffSwitch
                                checked={tool.status}
                                disabled={togglingId === tool.rawId}
                                label={`${tool.name} 上线状态`}
                                onCheckedChange={() => toggleToolStatus(tool.id)}
                              />
                              <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                  <Button variant="ghost" size="icon" className="h-8 w-8">
                                    <MoreHorizontal className="h-4 w-4" />
                                  </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="border-border bg-card">
                                  <DropdownMenuItem className="gap-2" onClick={() => openEditDialog(tool)}>
                                    <Pencil className="h-4 w-4" /> 编辑
                                  </DropdownMenuItem>
                                  {mode === "agents" ? (
                                    <DropdownMenuItem asChild className="gap-2">
                                      <Link href={`/tools/${tool.rawId}/workflow`}>
                                        <Workflow className="h-4 w-4" /> 工作流画布
                                      </Link>
                                    </DropdownMenuItem>
                                  ) : null}
                                  <DropdownMenuItem className="gap-2" onClick={() => openFieldDialog(tool)}>
                                    <FileText className="h-4 w-4" /> 字段配置
                                  </DropdownMenuItem>
                                  <DropdownMenuItem className="gap-2" onClick={() => openSkillDialog(tool)}>
                                    <Sparkles className="h-4 w-4" /> Agent Skill
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
                          </div>
                          {mode === "agents" ? (
                            <Button asChild variant="outline" size="sm" className="w-full gap-2">
                              <Link href={`/tools/${tool.rawId}/workflow`}>
                                <Workflow className="h-4 w-4" />
                                编辑工作流
                              </Link>
                            </Button>
                          ) : null}
                        </div>
                      </div>
                    ))}
                  </div>
                </CollapsibleContent>
              </Collapsible>
            )
          })}
        </div>
      </div>

      <Dialog open={exportDialogOpen} onOpenChange={setExportDialogOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>导出配置包</DialogTitle>
            <DialogDescription>
              可按工具筛选导出；未选择工具时导出全量。含密钥文件只适合可信成员之间临时流转。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <Label className="text-sm font-medium">导出范围</Label>
                <p className="mt-1 text-xs text-muted-foreground">
                  {selectedExportTools.length > 0
                    ? `已选择 ${selectedExportTools.length} 个工具，将自动带上绑定模型、分类和厂商账号。`
                    : "当前为全量导出。"}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Button type="button" variant="outline" size="sm" onClick={selectVisibleExportTools} disabled={bundleBusy || exportToolOptions.length === 0}>
                  选择当前列表
                </Button>
                <Button type="button" variant="ghost" size="sm" onClick={() => setSelectedExportToolCodes([])} disabled={bundleBusy || selectedExportToolCodes.length === 0}>
                  清空
                </Button>
              </div>
            </div>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={exportToolSearch}
                onChange={(event) => setExportToolSearch(event.target.value)}
                placeholder="搜索工具、编码、模型，例如 doubao / 视频"
                className="pl-9"
              />
            </div>
            <ScrollArea className="h-56 rounded-lg border border-border">
              <div className="divide-y divide-border">
                {exportToolOptions.length === 0 ? (
                  <div className="px-3 py-8 text-center text-sm text-muted-foreground">没有匹配的工具</div>
                ) : (
                  exportToolOptions.map((tool) => (
                    <label key={tool.toolCode} className="flex cursor-pointer items-start gap-3 px-3 py-2.5 hover:bg-secondary/60">
                      <Checkbox
                        checked={selectedExportToolCodeSet.has(tool.toolCode)}
                        onCheckedChange={(checked) => toggleExportTool(tool.toolCode, checked === true)}
                        disabled={bundleBusy}
                        className="mt-0.5"
                      />
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-sm font-medium">{tool.name}</span>
                          <Badge variant="secondary" className="text-[10px] font-normal">
                            {displayCategoryForTool(tool)}
                          </Badge>
                        </span>
                        <span className="mt-1 block truncate text-xs text-muted-foreground">
                          {tool.toolCode}
                          {tool.modelConfigName || tool.modelName ? ` · ${tool.modelConfigName || tool.modelName}` : ""}
                        </span>
                      </span>
                    </label>
                  ))
                )}
              </div>
            </ScrollArea>
            <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-border px-3 py-3">
              <Checkbox
                checked={includeExportMediaAssets}
                onCheckedChange={(checked) => setIncludeExportMediaAssets(checked === true)}
                disabled={bundleBusy}
                className="mt-0.5"
              />
              <span>
                <span className="block text-sm font-medium">包含展示素材 URL</span>
                <span className="mt-1 block text-xs leading-5 text-muted-foreground">
                  关闭时会清空工具展示素材和模型效果图/对比图/试听 URL；导入到已有工具时会保留目标环境已有 OSS 地址。
                </span>
              </span>
            </label>
          </div>

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

      <Dialog open={skillDialogOpen} onOpenChange={setSkillDialogOpen}>
        <DialogContent className="!w-[980px] !max-w-[calc(100vw-2rem)] max-h-[90vh] overflow-y-auto bg-card border-border">
          <DialogHeader>
            <DialogTitle>Agent Skill</DialogTitle>
            <DialogDescription className={skillError ? "text-destructive" : undefined}>
              {skillError || `配置 ${skillTool?.name || ""} 的 Agent 技能包；运行时首轮只注入能力菜单，SOP 会在工具调用前按需水合。`}
            </DialogDescription>
          </DialogHeader>
          {skillLoading ? (
            <div className="flex min-h-60 items-center justify-center text-muted-foreground">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" /> 正在读取 Skill Bundle...
            </div>
          ) : (
            <div className="space-y-4">
              <div className="grid gap-4 md:grid-cols-3">
                <div className="space-y-1.5">
                  <Label>Skill Code</Label>
                  <Input
                    value={skillForm.skillCode}
                    disabled={skillSaving}
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, skillCode: event.target.value }))}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>显示名</Label>
                  <Input
                    value={skillForm.displayName}
                    disabled={skillSaving}
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, displayName: event.target.value }))}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>状态</Label>
                  <div className="flex h-10 items-center gap-2 rounded-md border border-border px-3 text-sm">
                    <Badge variant={skillForm.status === "PUBLISHED" ? "default" : "secondary"}>{skillForm.status}</Badge>
                    <span className="text-muted-foreground">v{skillForm.version || 1}</span>
                  </div>
                </div>
              </div>
              <div className="space-y-1.5">
                <Label>轻量菜单描述</Label>
                <Textarea
                  value={skillForm.description}
                  disabled={skillSaving}
                  rows={2}
                  onChange={(event) => setSkillForm((prev) => ({ ...prev, description: event.target.value }))}
                />
              </div>
              <div className="space-y-1.5">
                <Label>绑定工具 Codes</Label>
                <Input
                  value={skillForm.toolCodesText}
                  disabled={skillSaving}
                  placeholder="gpt_image, gpt_image2, openai_image"
                  onChange={(event) => setSkillForm((prev) => ({ ...prev, toolCodesText: event.target.value }))}
                />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-1.5">
                  <Label>什么时候使用</Label>
                  <Textarea
                    value={skillForm.whenToUse}
                    disabled={skillSaving}
                    rows={4}
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, whenToUse: event.target.value }))}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>什么时候不要使用</Label>
                  <Textarea
                    value={skillForm.whenNotToUse}
                    disabled={skillSaving}
                    rows={4}
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, whenNotToUse: event.target.value }))}
                  />
                </div>
              </div>
              <div className="space-y-1.5">
                <Label>SOP Rules（JIT 注入）</Label>
                <Textarea
                  value={skillForm.sopRules}
                  disabled={skillSaving}
                  rows={12}
                  className="font-mono text-xs"
                  onChange={(event) => setSkillForm((prev) => ({ ...prev, sopRules: event.target.value }))}
                />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-1.5">
                  <Label>字段策略 JSON</Label>
                  <Textarea
                    value={skillForm.fieldPolicyJson}
                    disabled={skillSaving}
                    rows={6}
                    className="font-mono text-xs"
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, fieldPolicyJson: event.target.value }))}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>示例 JSON</Label>
                  <Textarea
                    value={skillForm.examplesJson}
                    disabled={skillSaving}
                    rows={6}
                    className="font-mono text-xs"
                    onChange={(event) => setSkillForm((prev) => ({ ...prev, examplesJson: event.target.value }))}
                  />
                </div>
              </div>
            </div>
          )}
          <DialogFooter className="gap-2">
            <Button variant="outline" onClick={() => setSkillDialogOpen(false)} disabled={skillSaving}>
              关闭
            </Button>
            <Button variant="outline" onClick={saveSkillDraft} disabled={skillLoading || skillSaving}>
              {skillSaving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}保存草稿
            </Button>
            <Button onClick={publishSkillDraft} disabled={skillLoading || skillSaving}>
              发布版本
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}

export default function ToolsPage() {
  return <ToolManagementPage mode="models" />
}
