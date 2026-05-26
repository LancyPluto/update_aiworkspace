"use client"

import { useEffect, useMemo, useState } from "react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import {
  createAgentModelConfig,
  deleteAgentModelConfig,
  fetchAgentModelConfigs,
  setDefaultAgentModelConfig,
  testAgentModelConfig,
  testSavedAgentModelConfig,
  updateAgentModelConfig,
} from "@/lib/api/agent-model"
import { fetchModelProviders } from "@/lib/api/model-providers"
import type {
  AgentModelConfig,
  AgentModelConfigPayload,
  AgentModelConfigTestResult,
  ModelProviderDescriptor,
} from "@/lib/api/types"
import { AlertCircle, CheckCircle2, KeyRound, Plus, RefreshCw, Save, ServerCog, Star, Trash2, Zap } from "lucide-react"
import { Checkbox } from "@/components/ui/checkbox"

interface ModelForm {
  id: number | null
  displayName: string
  configCode: string
  provider: string
  modelName: string
  baseUrl: string
  apiKey: string
  apiKeyMasked: string
  extraAuthJson: string
  extraAuthJsonMasked: string
  consoleUrl: string
  balanceUrl: string
  docsUrl: string
  timeoutSeconds: string
  inputTokenPricePer1m: string
  outputTokenPricePer1m: string
  billingUnit: "TOKEN_PER_M" | "PER_CALL" | "IMAGE_TOKEN"
  unitPrice: string
  enabled: boolean
  agentEnabled: boolean
  isDefault: boolean
  capabilities: string[]
}

interface VendorMeta {
  label: string
  shortName: string
  mark: string
  /** 瀵瑰簲 `public/assets/vendor-icons/{iconAsset}.svg`锛堢櫧搴曠礌鏉愶級 */
  iconAsset: string
}

type ModelConfigWithTest = AgentModelConfig & {
  lastTestAt?: string | null
  lastTestSuccess?: boolean | null
}

type ModalityFilter = "ALL" | "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "MULTIMODAL" | "OTHER"

const modalityFilters: Array<{ value: ModalityFilter; label: string }> = [
  { value: "ALL", label: "全部" },
  { value: "TEXT", label: "文本" },
  { value: "IMAGE", label: "图片" },
  { value: "VIDEO", label: "视频" },
  { value: "AUDIO", label: "音频" },
  { value: "MULTIMODAL", label: "多模态" },
  { value: "OTHER", label: "其他" },
]

const FALLBACK_PROVIDER: ModelProviderDescriptor = {
  code: "openai_compatible",
  label: "OpenAI compatible",
  capabilities: ["TEXT_GENERATION"],
  defaultBaseUrl: "https://api.openai.com/v1",
  defaultModel: "gpt-4o-mini",
  billingDefault: "TOKEN_PER_M",
  providerProtocol: "",
  vendorKind: "",
  upstreamVendor: "",
  testStrategy: "agent_service",
  workerReady: true,
  description: "OpenAI-compatible chat completion endpoint.",
}

function pickMeta(catalog: ModelProviderDescriptor[], code: string): ModelProviderDescriptor {
  return catalog.find((item) => item.code === code) || FALLBACK_PROVIDER
}

const vendorFallback: VendorMeta = {
  label: "妯″瀷 API",
  shortName: "API",
  mark: "AI",
  iconAsset: "api",
}

const vendorCatalog: Record<string, VendorMeta> = {
  openai: { label: "OpenAI", shortName: "OpenAI", mark: "OA", iconAsset: "openai" },
  claude: { label: "Claude", shortName: "Claude", mark: "C", iconAsset: "claude" },
  anthropic: { label: "Anthropic", shortName: "Anthropic", mark: "A", iconAsset: "anthropic" },
  minimax: { label: "MiniMax", shortName: "MiniMax", mark: "MM", iconAsset: "minimax" },
  siliconflow: { label: "SiliconFlow", shortName: "SiliconFlow", mark: "SF", iconAsset: "siliconflow" },
  deepseek: { label: "DeepSeek", shortName: "DeepSeek", mark: "DS", iconAsset: "deepseek" },
  moonshot: { label: "Moonshot AI", shortName: "Kimi", mark: "K", iconAsset: "moonshot" },
  zhipu: { label: "Zhipu AI", shortName: "GLM", mark: "Z", iconAsset: "zhipu" },
  qwen: { label: "Qwen", shortName: "Qwen", mark: "QW", iconAsset: "qwen" },
  alibabacloud: { label: "Alibaba Cloud", shortName: "Alibaba", mark: "ALI", iconAsset: "alibabacloud" },
  google: { label: "Google", shortName: "Gemini", mark: "G", iconAsset: "gemini" },
  openrouter: { label: "OpenRouter", shortName: "OpenRouter", mark: "OR", iconAsset: "openrouter" },
  doubao: { label: "Doubao", shortName: "Doubao", mark: "DB", iconAsset: "doubao" },
  baidu: { label: "Baidu", shortName: "ERNIE", mark: "BD", iconAsset: "baidu" },
}

const emptyForm: ModelForm = {
  id: null,
  displayName: "",
  configCode: "",
  provider: "openai_compatible",
  modelName: "gpt-4o-mini",
  baseUrl: "https://api.openai.com/v1",
  apiKey: "",
  apiKeyMasked: "",
  extraAuthJson: "",
  extraAuthJsonMasked: "",
  consoleUrl: "",
  balanceUrl: "",
  docsUrl: "",
  timeoutSeconds: "60",
  inputTokenPricePer1m: "0",
  outputTokenPricePer1m: "0",
  billingUnit: "TOKEN_PER_M",
  unitPrice: "0",
  enabled: true,
  agentEnabled: true,
  isDefault: false,
  capabilities: ["TEXT_GENERATION"],
}

function toForm(config: AgentModelConfig, catalog: ModelProviderDescriptor[]): ModelForm {
  const meta = pickMeta(catalog, config.provider)
  const caps =
    config.capabilities && config.capabilities.length > 0 ? [...config.capabilities] : [...meta.capabilities]
  return {
    id: config.id,
    displayName: config.displayName || config.modelName || "",
    configCode: config.configCode || "",
    provider: config.provider,
    modelName: config.modelName || meta.defaultModel,
    baseUrl: config.baseUrl || meta.defaultBaseUrl,
    apiKey: "",
    apiKeyMasked: config.apiKeyMasked || "",
    extraAuthJson: "",
    extraAuthJsonMasked: config.extraAuthJsonMasked || "",
    consoleUrl: config.consoleUrl || "",
    balanceUrl: config.balanceUrl || "",
    docsUrl: config.docsUrl || "",
    timeoutSeconds: String(config.timeoutSeconds || 60),
    inputTokenPricePer1m: String(config.inputTokenPricePer1m ?? ((config.inputTokenPricePer1k ?? 0) * 1000)),
    outputTokenPricePer1m: String(config.outputTokenPricePer1m ?? ((config.outputTokenPricePer1k ?? 0) * 1000)),
    billingUnit:
      config.billingUnit === "PER_CALL"
        ? "PER_CALL"
        : config.billingUnit === "IMAGE_TOKEN"
          ? "IMAGE_TOKEN"
          : "TOKEN_PER_M",
    unitPrice: String(config.unitPrice ?? 0),
    enabled: config.enabled !== false,
    agentEnabled: config.agentEnabled !== false,
    isDefault: Boolean(config.isDefault),
    capabilities: caps,
  }
}

function toPayload(form: ModelForm): AgentModelConfigPayload {
  return {
    displayName: form.displayName.trim(),
    configCode: form.configCode.trim(),
    provider: form.provider,
    modelName: form.modelName.trim(),
    baseUrl: form.baseUrl.trim(),
    apiKey: form.apiKey.trim(),
    extraAuthJson: form.extraAuthJson.trim(),
    consoleUrl: form.consoleUrl.trim(),
    balanceUrl: form.balanceUrl.trim(),
    docsUrl: form.docsUrl.trim(),
    timeoutSeconds: Number(form.timeoutSeconds) || 60,
    inputTokenPricePer1m: Number(form.inputTokenPricePer1m) || 0,
    outputTokenPricePer1m: Number(form.outputTokenPricePer1m) || 0,
    billingUnit: form.billingUnit,
    unitPrice: Number(form.unitPrice) || 0,
    enabled: form.enabled,
    agentEnabled: form.agentEnabled,
    isDefault: form.isDefault,
    capabilities: form.capabilities,
  }
}

function normalizeConfigCode(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "")
}

function normalizeBillingUnit(value?: string | null): ModelForm["billingUnit"] {
  if (value === "PER_CALL") return "PER_CALL"
  if (value === "IMAGE_TOKEN") return "IMAGE_TOKEN"
  return "TOKEN_PER_M"
}

function resolveVendorMeta(parts: {
  displayName?: string | null
  modelName?: string | null
  configCode?: string | null
  baseUrl?: string | null
  provider?: string | null
}): VendorMeta {
  const modelText = `${parts.displayName || ""} ${parts.modelName || ""} ${parts.configCode || ""}`.toLowerCase()
  const baseText = `${parts.baseUrl || ""} ${parts.provider || ""}`.toLowerCase()
  const allText = `${modelText} ${baseText}`

  if (modelText.includes("moonshot") || modelText.includes("kimi")) return vendorCatalog.moonshot
  if (modelText.includes("doubao") || modelText.includes("seed") || modelText.includes("bytedance") || modelText.includes("volc")) return vendorCatalog.doubao
  if (baseText.includes("volces.com") || baseText.includes("volcengine")) return vendorCatalog.doubao
  if (modelText.includes("deepseek")) return vendorCatalog.deepseek
  if (modelText.includes("minimax")) return vendorCatalog.minimax
  if (modelText.includes("zhipu") || modelText.includes("glm") || modelText.includes("zai-org")) return vendorCatalog.zhipu
  if (modelText.includes("qwen") || modelText.includes("tongyi") || modelText.includes("dashscope")) return vendorCatalog.qwen
  if (modelText.includes("alibaba") || baseText.includes("aliyuncs.com")) return vendorCatalog.alibabacloud
  if (modelText.includes("ernie") || modelText.includes("wenxin") || modelText.includes("baidu")) return vendorCatalog.baidu
  if (modelText.includes("gemini") || modelText.includes("google")) return vendorCatalog.google
  if (modelText.includes("claude")) return vendorCatalog.claude
  if (baseText.includes("siliconflow")) return vendorCatalog.siliconflow
  if (allText.includes("openrouter")) return vendorCatalog.openrouter
  if (allText.includes("anthropic")) return vendorCatalog.anthropic
  if (modelText.includes("gpt") || modelText.includes("openai") || modelText.includes("o1") || modelText.includes("o3")) return vendorCatalog.openai
  return vendorFallback
}

function detectModelVendor(config: AgentModelConfig): VendorMeta {
  return resolveVendorMeta({
    displayName: config.displayName,
    modelName: config.modelName,
    configCode: config.configCode,
    baseUrl: config.baseUrl,
    provider: config.provider,
  })
}

function resolvedCapabilities(config: AgentModelConfig, catalog: ModelProviderDescriptor[]) {
  if (config.capabilities && config.capabilities.length > 0) {
    return config.capabilities
  }
  return pickMeta(catalog, config.provider).capabilities
}

function capabilityModalities(capabilities: string[]): ModalityFilter[] {
  const normalized = capabilities.map((capability) => capability.trim().toUpperCase()).filter(Boolean)
  const result = new Set<ModalityFilter>()

  normalized.forEach((capability) => {
    if (capability.includes("TEXT") || capability.includes("CHAT") || capability.includes("LLM")) {
      result.add("TEXT")
    }
    if (capability.includes("IMAGE")) {
      result.add("IMAGE")
    }
    if (capability.includes("VIDEO") || capability.includes("DIGITAL_HUMAN")) {
      result.add("VIDEO")
    }
    if (capability.includes("AUDIO") || capability.includes("SPEECH") || capability.includes("VOICE")) {
      result.add("AUDIO")
    }
    if (capability.includes("MULTIMODAL")) {
      result.add("MULTIMODAL")
    }
  })

  if (result.size > 1) {
    result.add("MULTIMODAL")
  }
  if (result.size === 0) {
    result.add("OTHER")
  }

  return Array.from(result)
}

function capabilityModalityLabel(capabilities: string[]) {
  const all = capabilityModalities(capabilities)
  const modalities = all.length > 1 ? all.filter((item) => item !== "MULTIMODAL") : all
  const labels = modalities.map((item) => modalityFilters.find((filter) => filter.value === item)?.label || item)
  return labels.join(" / ")
}

function VendorIcon({ vendor, size = "md" }: { vendor: VendorMeta; size?: "md" | "sm" }) {
  const box = size === "sm" ? "h-11 w-11" : "h-14 w-14"
  const img = size === "sm" ? "h-8 w-8" : "h-10 w-10"
  return (
    <div
      className={`flex shrink-0 items-center justify-center rounded-2xl border border-border/80 bg-white shadow-sm transition group-hover:scale-[1.02] ${box}`}
      title={vendor.label}
    >
      <img
        src={`/assets/vendor-icons/${vendor.iconAsset}.svg`}
        width={size === "sm" ? 32 : 40}
        height={size === "sm" ? 32 : 40}
        className={`${img} object-contain`}
        alt=""
      />
    </div>
  )
}

function testStatusBadge(config: ModelConfigWithTest) {
  if (config.lastTestSuccess === true) return <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700">测试正常</Badge>
  if (config.lastTestSuccess === false) return <Badge variant="outline" className="border-red-200 bg-red-50 text-red-700">测试失败</Badge>
  return null
}

interface AgentModelSettingsProps {
  refreshKey?: number
}

export function AgentModelSettings({ refreshKey = 0 }: AgentModelSettingsProps) {
  const [configs, setConfigs] = useState<ModelConfigWithTest[]>([])
  const [providerCatalog, setProviderCatalog] = useState<ModelProviderDescriptor[]>([])
  const [form, setForm] = useState<ModelForm>(emptyForm)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [testResult, setTestResult] = useState<AgentModelConfigTestResult | null>(null)
  const [modalityFilter, setModalityFilter] = useState<ModalityFilter>("ALL")

  const catalogResolved = providerCatalog.length > 0 ? providerCatalog : [FALLBACK_PROVIDER]

  const meta = useMemo(() => pickMeta(catalogResolved, form.provider), [catalogResolved, form.provider])
  const liveVendor = useMemo(
    () =>
      resolveVendorMeta({
        displayName: form.displayName,
        modelName: form.modelName,
        configCode: form.configCode,
        baseUrl: form.baseUrl,
        provider: form.provider,
      }),
    [form.displayName, form.modelName, form.configCode, form.baseUrl, form.provider],
  )
  const selectedId = dialogOpen ? form.id : null
  const modalityCounts = useMemo(() => {
    const counts = modalityFilters.reduce<Record<ModalityFilter, number>>((acc, filter) => {
      acc[filter.value] = 0
      return acc
    }, {} as Record<ModalityFilter, number>)
    counts.ALL = configs.length
    configs.forEach((config) => {
      const modalities = capabilityModalities(resolvedCapabilities(config, catalogResolved))
      modalities.forEach((modality) => {
        counts[modality] = (counts[modality] ?? 0) + 1
      })
    })
    return counts
  }, [catalogResolved, configs])
  const filteredConfigs = useMemo(() => {
    if (modalityFilter === "ALL") return configs
    return configs.filter((config) =>
      capabilityModalities(resolvedCapabilities(config, catalogResolved)).includes(modalityFilter),
    )
  }, [catalogResolved, configs, modalityFilter])

  async function loadConfigs(nextSelectedId?: number | null) {
    setLoading(true)
    setError(null)
    try {
      const [list, catalog] = await Promise.all([
        fetchAgentModelConfigs(),
        fetchModelProviders().catch(() => [] as ModelProviderDescriptor[]),
      ])
      const resolved = catalog.length > 0 ? catalog : [FALLBACK_PROVIDER]
      setProviderCatalog(resolved)
      setConfigs(list)
      const selected =
        list.find((item) => item.id === nextSelectedId) ||
        list.find((item) => item.id === selectedId) ||
        list.find((item) => item.isDefault) ||
        list[0]
      setForm(selected ? toForm(selected, resolved) : { ...emptyForm, capabilities: [...pickMeta(resolved, emptyForm.provider).capabilities] })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "鍔犺浇妯″瀷閰嶇疆澶辫触")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConfigs()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey])

  function updateForm<K extends keyof ModelForm>(key: K, value: ModelForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
    setSaved(false)
    setTestResult(null)
  }

  function editConfig(config: AgentModelConfig) {
    setForm(toForm(config, catalogResolved))
    setSaved(false)
    setTestResult(null)
    setError(null)
    setDialogOpen(true)
  }

  function createConfig() {
    const m = pickMeta(catalogResolved, emptyForm.provider)
    setForm({
      ...emptyForm,
      configCode: "",
      displayName: "",
      isDefault: configs.length === 0,
      agentEnabled: true,
      capabilities: [...m.capabilities],
      baseUrl: m.defaultBaseUrl,
      modelName: m.defaultModel,
      billingUnit: normalizeBillingUnit(m.billingDefault),
    })
    setSaved(false)
    setTestResult(null)
    setError(null)
    setDialogOpen(true)
  }

  function handleDialogOpenChange(open: boolean) {
    setDialogOpen(open)
    if (!open) {
      setSaved(false)
      setTestResult(null)
      setError(null)
    }
  }

  function applyProvider(value: string) {
    const next = pickMeta(catalogResolved, value)
    setForm((current) => ({
      ...current,
      provider: value,
      modelName:
        current.modelName && current.modelName !== pickMeta(catalogResolved, current.provider).defaultModel
          ? current.modelName
          : next.defaultModel,
      baseUrl: next.defaultBaseUrl,
      billingUnit: normalizeBillingUnit(next.billingDefault),
      capabilities: [...next.capabilities],
    }))
    setSaved(false)
    setTestResult(null)
  }

  function toggleCapability(cap: string) {
    setForm((current) => {
      const set = new Set(current.capabilities)
      if (set.has(cap)) {
        set.delete(cap)
      } else {
        set.add(cap)
      }
      return { ...current, capabilities: Array.from(set) }
    })
    setSaved(false)
    setTestResult(null)
  }

  function validateForm(): string | null {
  if (!form.displayName.trim()) return "请填写配置名称。"
  if (!form.modelName.trim()) return "请填写模型名称。"
  if (form.provider !== "mock" && !form.baseUrl.trim()) return "请填写 Base URL。"
  if (!form.capabilities.length) return "请至少选择一种模型能力。"
    const allowed = new Set(meta.capabilities.map((c) => c.toUpperCase()))
    for (const cap of form.capabilities) {
      if (!allowed.has(cap.toUpperCase())) return `能力 ${cap} 不适用于当前供应商 ${form.provider}。`
    }
    const timeout = Number(form.timeoutSeconds)
    if (!Number.isFinite(timeout) || timeout < 1 || timeout > 300) return "超时时间必须在 1 到 300 秒之间。"
    const inputPrice = Number(form.inputTokenPricePer1m)
    const outputPrice = Number(form.outputTokenPricePer1m)
    const unitPrice = Number(form.unitPrice)
    if (!Number.isFinite(inputPrice) || inputPrice < 0) return "输入 Token 单价必须大于等于 0。"
    if (!Number.isFinite(outputPrice) || outputPrice < 0) return "输出 Token 单价必须大于等于 0。"
    if (!Number.isFinite(unitPrice) || unitPrice < 0) return "单次计费价格必须大于等于 0。"
    if (form.extraAuthJson.trim()) {
      try {
        JSON.parse(form.extraAuthJson)
      } catch {
        return "额外鉴权 JSON 必须是合法 JSON。"
      }
    }
    const hasSecret = Boolean(form.apiKey.trim() || form.apiKeyMasked || form.extraAuthJson.trim() || form.extraAuthJsonMasked)
    if (form.provider !== "mock" && !hasSecret) return "请填写 API Key 或额外鉴权 JSON。"
    return null
  }

  async function saveConfig() {
    const validationError = validateForm()
    if (validationError) {
      setError(validationError)
      return
    }
    setSaving(true)
    setError(null)
    try {
      const savedConfig = form.id
        ? await updateAgentModelConfig(form.id, toPayload(form))
        : await createAgentModelConfig(toPayload(form))
      setSaved(true)
      await loadConfigs(savedConfig.id)
      setDialogOpen(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "淇濆瓨妯″瀷閰嶇疆澶辫触")
    } finally {
      setSaving(false)
    }
  }

  async function setAsDefault(config: AgentModelConfig) {
    setError(null)
    try {
      const updated = await setDefaultAgentModelConfig(config.id)
      await loadConfigs(updated.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "璁剧疆榛樿妯″瀷澶辫触")
    }
  }

  async function deleteConfig() {
    if (!form.id) return
    if (typeof window !== "undefined") {
      const ok = window.confirm(`确认删除模型配置「${form.displayName || form.modelName}」吗？`)
      if (!ok) return
    }
    setSaving(true)
    setError(null)
    try {
      await deleteAgentModelConfig(form.id)
      setDialogOpen(false)
      await loadConfigs()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "鍒犻櫎妯″瀷閰嶇疆澶辫触")
    } finally {
      setSaving(false)
    }
  }

  async function testConfig() {
    const validationError = validateForm()
    if (validationError) {
      setError(validationError)
      return
    }
    setTesting(true)
    setError(null)
    setTestResult(null)
    try {
      setTestResult(form.id && !form.apiKey.trim() && !form.extraAuthJson.trim()
        ? await testSavedAgentModelConfig()
        : await testAgentModelConfig(toPayload(form)))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "杩炴帴娴嬭瘯澶辫触")
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="space-y-6">
      <Alert>
        <ServerCog className="h-4 w-4" />
        <AlertTitle>大模型接入</AlertTitle>
        <AlertDescription>
          维护 AI 工具和 Agent 运行使用的全局模型 API。
        </AlertDescription>
      </Alert>

      {error && !dialogOpen ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <Card className="min-h-[420px]">
        <CardHeader className="flex flex-row items-start justify-between gap-3">
          <div>
            <CardTitle>已配置模型</CardTitle>
            <CardDescription>
              共 {configs.length} 个模型 API
              {modalityFilter === "ALL" ? "" : `，当前筛选 ${filteredConfigs.length} 个`}，点击卡片可编辑。
            </CardDescription>
          </div>
          <Button className="gap-2" onClick={createConfig}>
            <Plus className="h-4 w-4" />
            新增模型
          </Button>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {modalityFilters.map((filter) => (
              <Button
                key={filter.value}
                type="button"
                variant={modalityFilter === filter.value ? "default" : "outline"}
                size="sm"
                className="gap-2"
                onClick={() => setModalityFilter(filter.value)}
              >
                {filter.label}
                <Badge variant={modalityFilter === filter.value ? "secondary" : "outline"}>
                  {modalityCounts[filter.value] ?? 0}
                </Badge>
              </Button>
            ))}
          </div>

          {loading ? (
            <p className="text-sm text-muted-foreground">加载中...</p>
          ) : configs.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center rounded-xl border border-dashed bg-muted/20 p-8 text-center">
              <ServerCog className="mb-3 h-10 w-10 text-muted-foreground" />
              <p className="font-medium">暂无模型配置</p>
              <p className="mt-1 text-sm text-muted-foreground">保存真实配置前，后端会使用内置 Mock 配置。</p>
            </div>
          ) : filteredConfigs.length === 0 ? (
            <div className="flex min-h-48 flex-col items-center justify-center rounded-xl border border-dashed bg-muted/20 p-8 text-center">
              <ServerCog className="mb-3 h-10 w-10 text-muted-foreground" />
              <p className="font-medium">当前模态下暂无模型</p>
              <p className="mt-1 text-sm text-muted-foreground">可以切换筛选条件，或新增支持该模态的模型配置。</p>
            </div>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
              {filteredConfigs.map((config) => {
                const vendor = detectModelVendor(config)
                const capabilities = resolvedCapabilities(config, catalogResolved)
                return (
                  <div
                    key={config.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => editConfig(config)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault()
                        editConfig(config)
                      }
                    }}
                    className={`group w-full rounded-2xl border p-4 text-left transition hover:-translate-y-0.5 hover:border-primary hover:bg-secondary/40 hover:shadow-md ${selectedId === config.id ? "border-primary bg-secondary/70" : "border-border bg-card"}`}
                  >
                    <div className="flex items-start gap-3">
                      <VendorIcon vendor={vendor} />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-base font-semibold">{config.displayName || config.modelName}</p>
                            <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{config.modelName}</p>
                          </div>
                          {config.isDefault ? <Badge>默认</Badge> : null}
                        </div>
                        <div className="mt-3 flex flex-wrap items-center gap-2">
                          <Badge variant="outline">{vendor.shortName}</Badge>
                          <Badge variant="secondary">{pickMeta(catalogResolved, config.provider).label}</Badge>
                          <Badge variant="outline">{capabilityModalityLabel(capabilities)}</Badge>
                          {config.agentEnabled !== false ? <Badge variant="outline">Agent 可选</Badge> : null}
                          <Badge variant={config.enabled ? "default" : "secondary"}>{config.enabled ? "启用" : "停用"}</Badge>
                          {testStatusBadge(config)}
                        </div>
                        {!config.isDefault ? (
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            className="mt-3 gap-2"
                            onClick={(event) => {
                              event.stopPropagation()
                              setAsDefault(config)
                            }}
                          >
                            <Star className="h-3.5 w-3.5" />
                            设为默认
                          </Button>
                        ) : null}
                        <div className="mt-3 flex flex-wrap gap-2">
                          {config.consoleUrl ? (
                            <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" asChild onClick={(event) => event.stopPropagation()}>
                              <a href={config.consoleUrl} target="_blank" rel="noreferrer">控制台</a>
                            </Button>
                          ) : null}
                          {config.balanceUrl ? (
                            <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" asChild onClick={(event) => event.stopPropagation()}>
                              <a href={config.balanceUrl} target="_blank" rel="noreferrer">余额</a>
                            </Button>
                          ) : null}
                          {config.docsUrl ? (
                            <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" asChild onClick={(event) => event.stopPropagation()}>
                              <a href={config.docsUrl} target="_blank" rel="noreferrer">文档</a>
                            </Button>
                          ) : null}
                        </div>
                        <p className="mt-3 truncate text-xs text-muted-foreground">{config.baseUrl || "未配置 Base URL"}</p>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={dialogOpen} onOpenChange={handleDialogOpenChange}>
        <DialogContent className="max-h-[min(92vh,calc(100vh-2rem))] w-full overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{form.id ? "编辑模型 API" : "新增模型 API"}</DialogTitle>
            <DialogDescription>{meta.description}</DialogDescription>
          </DialogHeader>

          {error ? (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>操作失败</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}

          <div className="space-y-5">
            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>配置名称</Label>
                <Input value={form.displayName} placeholder="MiniMax M2.7 primary model" onChange={(event) => updateForm("displayName", event.target.value)} />
              </div>
              <div className="space-y-2">
                <Label>配置编码</Label>
                <Input value={form.configCode} placeholder="Optional, e.g. minimax_m27" onChange={(event) => updateForm("configCode", normalizeConfigCode(event.target.value))} />
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>供应商协议</Label>
                <Select value={form.provider} onValueChange={(value) => applyProvider(value)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {catalogResolved.map((item) => (
                      <SelectItem key={item.code} value={item.code}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>模型名称</Label>
                <div className="flex items-center gap-3">
                  <VendorIcon vendor={liveVendor} size="sm" />
                  <Input
                    className="flex-1"
                    value={form.modelName}
                    placeholder={meta.defaultModel}
                    onChange={(event) => updateForm("modelName", event.target.value)}
                  />
                </div>
                <p className="text-xs text-muted-foreground">识别供应商：{liveVendor.label}</p>
              </div>
            </div>

            <div className="space-y-3 rounded-lg border border-border bg-muted/20 p-4">
              <Label>Capabilities（须与工具 executionHandler 一致）</Label>
              <div className="grid gap-2 sm:grid-cols-2">
                {meta.capabilities.map((cap) => (
                  <label key={cap} className="flex cursor-pointer items-center gap-2 text-sm">
                    <Checkbox checked={form.capabilities.includes(cap)} onCheckedChange={() => toggleCapability(cap)} />
                    <span>{cap}</span>
                  </label>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">
                连通性测试：{meta.testStrategy} · Worker：{meta.workerReady ? "就绪" : "未就绪（仅保存凭证）"}
              </p>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>Base URL</Label>
                <Input value={form.baseUrl} placeholder={meta.defaultBaseUrl} onChange={(event) => updateForm("baseUrl", event.target.value)} />
              </div>
              <div className="space-y-2">
                <Label>超时时间（秒）</Label>
                <Input type="number" min={1} max={300} value={form.timeoutSeconds} onChange={(event) => updateForm("timeoutSeconds", event.target.value)} />
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>输入 Token 单价 / 1M</Label>
                <Input
                  type="number"
                  min={0}
                  step="0.000001"
                  value={form.inputTokenPricePer1m}
                  onChange={(event) => updateForm("inputTokenPricePer1m", event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label>输出 Token 单价 / 1M</Label>
                <Input
                  type="number"
                  min={0}
                  step="0.000001"
                  value={form.outputTokenPricePer1m}
                  onChange={(event) => updateForm("outputTokenPricePer1m", event.target.value)}
                />
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>计费单位</Label>
                <Select value={form.billingUnit} onValueChange={(value) => updateForm("billingUnit", value as ModelForm["billingUnit"])}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="TOKEN_PER_M">Token / 1M</SelectItem>
                    <SelectItem value="PER_CALL">按生成次数</SelectItem>
                    <SelectItem value="IMAGE_TOKEN">图片 Token</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>单次价格</Label>
                <Input
                  type="number"
                  min={0}
                  step="0.000001"
                  value={form.unitPrice}
                  onChange={(event) => updateForm("unitPrice", event.target.value)}
                  placeholder={form.billingUnit === "PER_CALL" ? "图片/视频等单次调用成本" : "Token 计费通常填 0"}
                />
              </div>
            </div>

            <div className="rounded-lg border border-border bg-secondary/30 p-4">
              <div className="mb-3">
                <p className="text-sm font-medium">供应商入口</p>
                <p className="text-xs text-muted-foreground">
                  用于管理员快速跳转查看控制台、账号余额和接口文档；中转站供应会在这里保留网关标识。
                </p>
              </div>
              <div className="grid gap-4 md:grid-cols-3">
                <div className="space-y-2">
                  <Label>控制台链接</Label>
                  <Input value={form.consoleUrl} placeholder="https://..." onChange={(event) => updateForm("consoleUrl", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>余额链接</Label>
                  <Input value={form.balanceUrl} placeholder="https://..." onChange={(event) => updateForm("balanceUrl", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>文档链接</Label>
                  <Input value={form.docsUrl} placeholder="https://..." onChange={(event) => updateForm("docsUrl", event.target.value)} />
                </div>
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>API Key</Label>
                <div className="relative">
                  <KeyRound className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    type="password"
                    className="pl-9"
                    value={form.apiKey}
                    placeholder={form.apiKeyMasked ? `已保存：${form.apiKeyMasked}` : "请输入 API Key"}
                    onChange={(event) => updateForm("apiKey", event.target.value)}
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label>运行信息</Label>
                <Input
                  value={`test=${meta.testStrategy} · workerReady=${meta.workerReady}${meta.vendorKind === "gateway" ? " · gateway" : ""}`}
                  readOnly
                  className="text-muted-foreground"
                />
              </div>
            </div>

            <div className="space-y-2">
              <Label>额外鉴权 JSON</Label>
              <Textarea
                className="min-h-24 font-mono text-xs"
                value={form.extraAuthJson}
                placeholder={
                  form.extraAuthJsonMasked
                    ? `Saved: ${form.extraAuthJsonMasked}`
                    : '{"accessKey":"...","secretKey":"..."}'
                }
                onChange={(event) => updateForm("extraAuthJson", event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                适合可灵这类 AK/SK 供应商。留空保存时会保留已保存的密钥；API Key 可继续用于固定 Bearer Token。
              </p>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-4 rounded-md bg-secondary p-4">
              <div>
                <p className="font-medium">启用</p>
                <p className="text-sm text-muted-foreground">停用后不会被默认模型调用使用。</p>
              </div>
              <Switch checked={form.enabled} onCheckedChange={(value) => updateForm("enabled", value)} />
            </div>

            <div className="flex flex-wrap items-center justify-between gap-4 rounded-md bg-secondary p-4">
              <div>
                <p className="font-medium">作为 Agent 模型</p>
                <p className="text-sm text-muted-foreground">勾选后会出现在用户端 Agent 页的模型选择中。</p>
              </div>
              <Switch
                checked={form.agentEnabled}
                disabled={!form.enabled}
                onCheckedChange={(value) => updateForm("agentEnabled", value)}
              />
            </div>

            <Textarea
              readOnly
              className="min-h-24 font-mono text-xs"
              value={`provider=${form.provider}\nprovider_protocol=${meta.providerProtocol || form.provider}\nvendor_kind=${meta.vendorKind || "direct"}\nupstream_vendor=${meta.upstreamVendor || ""}\nmodel=${form.modelName}\nbase_url=${form.baseUrl}\ntimeout=${form.timeoutSeconds}s\nagent_enabled=${form.agentEnabled}\nbilling_unit=${form.billingUnit}\ninput_price_per_1m=${form.inputTokenPricePer1m}\noutput_price_per_1m=${form.outputTokenPricePer1m}\nunit_price=${form.unitPrice}\nextra_auth=${form.extraAuthJson || form.extraAuthJsonMasked ? "configured" : "empty"}`}
            />

            {testResult ? (
              <Alert variant={testResult.success ? "default" : "destructive"}>
                {testResult.success ? <CheckCircle2 className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
                <AlertTitle>{testResult.success ? "连接正常" : "连接失败"}</AlertTitle>
                <AlertDescription>
                  {testResult.message}, latency {testResult.latencyMs} ms
                  {testResult.sample ? `, sample: ${testResult.sample}` : ""}
                </AlertDescription>
              </Alert>
            ) : null}
          </div>

          <DialogFooter className="flex-wrap justify-end gap-3">
            {form.id ? (
              <Button variant="destructive" className="gap-2" onClick={deleteConfig} disabled={saving || testing}>
                <Trash2 className="h-4 w-4" />
                删除
              </Button>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" className="gap-2" onClick={() => loadConfigs(form.id)} disabled={loading || saving || testing}>
                <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
                刷新
              </Button>
              <Button variant="outline" className="gap-2" onClick={testConfig} disabled={saving || testing}>
                <Zap className={testing ? "h-4 w-4 animate-pulse" : "h-4 w-4"} />
                {testing ? "测试中..." : "测试连接"}
              </Button>
              <Button className="min-w-32 gap-2" onClick={saveConfig} disabled={saving || testing}>
                {saved ? <CheckCircle2 className="h-4 w-4" /> : <Save className={saving ? "h-4 w-4 animate-spin" : "h-4 w-4"} />}
                {saved ? "已保存" : saving ? "保存中..." : form.id ? "保存配置" : "创建配置"}
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
