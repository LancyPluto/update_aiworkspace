"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import {
  createAgentModelConfig,
  deleteAgentModelConfig,
  testAgentModelConfigById,
  updateAgentModelConfig,
} from "@/lib/api/agent-model"
import { ApiError } from "@/lib/api/http"
import {
  createModelVendorAccount,
  deleteModelVendorAccount,
  refreshAllModelVendorAccountBalances,
  refreshModelVendorAccountBalance,
  testModelVendorAccount,
  updateModelVendorAccount,
} from "@/lib/api/model-vendor-account"
import { fetchModelProviders } from "@/lib/api/model-providers"
import { deleteModelVendor, upsertModelVendor } from "@/lib/api/model-vendors"
import { fetchUnifiedApiOverview } from "@/lib/api/unified-api"
import type {
  AgentModelConfigPayload,
  ModelVendorPayload,
  ModelProviderDescriptor,
  ModelVendorAccount,
  ModelVendorAccountPayload,
  UnifiedApiModelItem,
  UnifiedApiOverview,
  UnifiedApiVendorGroup,
} from "@/lib/api/types"
import {
  AlertCircle,
  Activity,
  CheckCircle2,
  ChevronDown,
  ExternalLink,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  ServerCog,
  Settings2,
  Star,
  Trash2,
  Wallet,
  Zap,
} from "lucide-react"
import { toast } from "sonner"

const adminBasePath = (process.env.NEXT_PUBLIC_ADMIN_BASE_PATH || "").replace(/\/$/, "")

function VendorIcon({ iconAsset, label }: { iconAsset: string; label: string }) {
  const [failed, setFailed] = useState(false)
  const src = `${adminBasePath}/assets/vendor-icons/${iconAsset || "api"}.svg`
  return (
    <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border bg-white p-1 text-xs font-semibold text-slate-600">
      {failed ? (
        <span>{label.slice(0, 1).toUpperCase()}</span>
      ) : (
        <img
          src={src}
          alt={label}
          className="h-full w-full object-contain"
          onError={() => setFailed(true)}
        />
      )}
    </span>
  )
}

function isNegativeBalance(account: ModelVendorAccount) {
  return account.balanceAmount != null && account.balanceAmount < 0
}

function balanceStatusBadge(account: ModelVendorAccount) {
  const status = account.balanceStatus
  if (isNegativeBalance(account)) {
    return <Badge variant="destructive">欠费</Badge>
  }
  if (account.balanceAmount != null) {
    return <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">正常</Badge>
  }
  if (status === "ERROR") {
    return null
  }
  if (account.balanceQueryMode === "NONE") {
    return <Badge variant="outline">仅外链</Badge>
  }
  if (account.balanceQueryMode === "MANUAL" && account.balanceAmount == null) {
    return <Badge variant="outline">待手填</Badge>
  }
  return <Badge variant="outline">未知</Badge>
}

function formatBalance(account: ModelVendorAccount) {
  if (account.balanceAmount != null) {
    const currency = account.balanceCurrency === "USD" ? "$" : "¥"
    return `余额 ${currency}${account.balanceAmount}`
  }
  if (account.balanceQueryMode === "NONE") {
    return "余额（控制台查看）"
  }
  if (account.balanceQueryMode === "MANUAL") {
    return "余额（手填）"
  }
  return "余额 --"
}

function diagnoseProviderIssue(
  message: string | null | undefined,
  stage: "balance" | "connectivity" | "model",
  account?: Pick<ModelVendorAccount, "vendorCode" | "balanceQueryMode"> | null,
) {
  const raw = (message || "").trim()
  const lower = raw.toLowerCase()
  const stageLabel =
    stage === "balance" ? "余额查询" : stage === "connectivity" ? "账户连通测试" : "模型连通测试"
  const vendor = account?.vendorCode ? `厂商 ${account.vendorCode}` : "当前厂商"
  if (!raw) {
    return `${stageLabel}未返回具体错误。请检查该账号的 API Key、Base URL、测试策略和后端日志。`
  }
  if (lower.includes("your request was blocked") || lower.includes("request was blocked")) {
    return `${stageLabel}被上游网关或风控拦截：${raw}。这通常是探测/余额接口被拦，不一定代表 API Key 不可用；${vendor} 若是 accept-only 策略，请使用“测试连通性”刷新账号状态，余额可改为手填或外链。`
  }
  if (lower.includes("unsupported model provider")) {
    return `${stageLabel}失败：后端没有识别该 provider。请检查 providerCode 是否已在供应商元数据/adapter manifest 中注册。原始错误：${raw}`
  }
  if (lower.includes("invalid token") || lower.includes("unauthorized") || lower.includes("401")) {
    return `${stageLabel}鉴权失败：请检查 API Key 是否正确、是否填在厂商账户而非前端、Base URL 是否属于该供应商。原始错误：${raw}`
  }
  if (lower.includes("404")) {
    return `${stageLabel}接口不存在：Base URL 或余额/模型探测路径可能不适配该供应商。原始错误：${raw}`
  }
  if (stage === "balance" && account?.balanceQueryMode === "REST_API") {
    return `未获取到余额：${raw}。如果该供应商没有稳定余额接口，请把余额查询方式改成“手填”或“仅外链”，不要让余额探测承担连通性判断。`
  }
  return `${stageLabel}失败：${raw}`
}

function formatBalanceUpdatedAt(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  return `更新于 ${date.toLocaleString("zh-CN", { hour12: false })}`
}

function pickPrimaryAccount(accounts: ModelVendorAccount[]): ModelVendorAccount | undefined {
  if (accounts.length === 0) return undefined
  return [...accounts].sort((a, b) => {
    const modelDiff = (b.modelCount ?? 0) - (a.modelCount ?? 0)
    if (modelDiff !== 0) return modelDiff
    const keyDiff = (hasAccountCredential(b) ? 1 : 0) - (hasAccountCredential(a) ? 1 : 0)
    if (keyDiff !== 0) return keyDiff
    return a.id - b.id
  })[0]
}

function hasAccountCredential(account?: Pick<ModelVendorAccount, "apiKeyMasked" | "extraAuthJsonMasked" | "apiKey" | "extraAuthJson"> | null) {
  return Boolean(
    account
      && ((account.apiKeyMasked && account.apiKeyMasked.trim())
        || (account.extraAuthJsonMasked && account.extraAuthJsonMasked.trim())
        || (account.apiKey && account.apiKey.trim())
        || (account.extraAuthJson && account.extraAuthJson.trim())),
  )
}

function accountCredentialLabel(account: Pick<ModelVendorAccount, "apiKeyMasked" | "extraAuthJsonMasked">) {
  const parts: string[] = []
  if (account.apiKeyMasked) parts.push(`API Key ${account.apiKeyMasked}`)
  if (account.extraAuthJsonMasked) parts.push(`额外鉴权 ${account.extraAuthJsonMasked}`)
  return parts.length ? parts.join(" · ") : "未配置凭据"
}

function isHealthyStatus(value?: string | null) {
  return (value || "").trim().toUpperCase() === "OK"
}

function modelAccountHealth(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  if (!model.vendorAccountId) return "UNKNOWN"
  const account = vendor.accounts.find((item) => item.id === model.vendorAccountId)
  if (!account) return "UNKNOWN"
  return account.healthStatus || "UNKNOWN"
}

function canEnableAgentForModel(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  if (!model.enabled) return false
  if (!model.vendorAccountId) return true
  return isHealthyStatus(modelAccountHealth(model, vendor))
}

function modelRowTone(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  const health = modelAccountHealth(model, vendor)
  const disabledTone = model.enabled ? "" : " opacity-75"
  if (isHealthyStatus(health)) return `bg-emerald-50/70 hover:bg-emerald-50${disabledTone}`
  if ((health || "").trim().toUpperCase() === "ERROR") return `bg-rose-50/75 hover:bg-rose-50${disabledTone}`
  return ""
}

function accountCardTone(account: ModelVendorAccount) {
  if (!account.enabled) return ""
  if (isHealthyStatus(account.healthStatus)) return "border-emerald-200 bg-emerald-50/60"
  if ((account.healthStatus || "").trim().toUpperCase() === "ERROR") return "border-rose-200 bg-rose-50/70"
  return "bg-muted/20"
}

function defaultBalanceModeForVendor(vendorCode: string) {
  switch (vendorCode) {
    case "deepseek":
    case "siliconflow":
    case "openai":
    case "openai_gateway":
      return "REST_API"
    case "volcengine":
    case "kling":
    case "minimax":
      return "NONE"
    default:
      return "MANUAL"
  }
}

function capabilityLabel(cap: string) {
  const map: Record<string, string> = {
    TEXT_GENERATION: "文本生成",
    IMAGE_GENERATION: "文生图",
    VIDEO_GENERATION: "视频生成",
    TEXT_TO_SPEECH: "文字转语音",
    SPEECH_TO_TEXT: "语音转文字",
    MUSIC_GENERATION: "文生音乐",
    DIGITAL_HUMAN: "数字人",
    MULTIMODAL: "多模态输入",
  }
  return map[cap] || cap
}

function providerForVendor(providers: ModelProviderDescriptor[], vendorCode: string, fallbackProvider?: string) {
  const normalizedVendor = (vendorCode || "").trim().toLowerCase()
  const normalizedFallback = (fallbackProvider || "").trim().toLowerCase()
  return (
    providers.find((provider) => provider.code.toLowerCase() === normalizedVendor) ||
    providers.find((provider) => provider.code.toLowerCase() === normalizedFallback) ||
    providers.find((provider) => provider.code.toLowerCase().includes(normalizedVendor)) ||
    providers[0]
  )
}

function modelCapabilitiesForProvider(
  capabilities: string[] | null | undefined,
  provider?: ModelProviderDescriptor,
) {
  const defaults = provider?.capabilities && provider.capabilities.length > 0 ? provider.capabilities : ["TEXT_GENERATION"]
  if (!capabilities || capabilities.length === 0) {
    return [...defaults]
  }
  if (!provider?.capabilities?.length) {
    return [...capabilities]
  }
  const allowed = new Set(provider.capabilities.map((capability) => capability.toUpperCase()))
  const compatible = capabilities.filter((capability) => allowed.has(capability.toUpperCase()))
  return compatible.length > 0 ? compatible : [...defaults]
}

function routeTasksForModel(provider: string | undefined, capabilities: string[] | null | undefined) {
  const tasks = executionTaskOptions[(provider || "").trim()]
  if (!tasks) return []
  const caps = new Set((capabilities || []).map((capability) => capability.toUpperCase()))
  if (caps.size === 0) return tasks
  return tasks.filter((task) => task.capabilities.some((capability) => caps.has(capability)))
}

function routeTaskLabel(provider: string | undefined, task: string | null | undefined) {
  const normalized = (task || "").trim()
  return executionTaskOptions[(provider || "").trim()]?.find((item) => item.value === normalized)?.label || normalized || "未配置"
}

function routePreviewForForm(form: AgentModelConfigPayload & { id?: number }) {
  const task = routeTasksForModel(form.provider, form.capabilities).find((item) => item.value === form.executionTask)
  if (task) return { createPath: task.createPath, resultPath: task.resultPath, source: "executionTask" }
  return null
}

function renderModelCost(model: UnifiedApiModelItem) {
  const billingUnit = (model.billingUnit || "").toString().trim().toUpperCase()
  if (!billingUnit) {
    return <span>—</span>
  }
  if (billingUnit === "PER_CALL") {
    const price = model.unitPrice
    return (
      <>
        <div>按次计费</div>
        <div className="font-medium text-foreground">{price != null ? `¥${price}` : "¥—"}/次</div>
      </>
    )
  }
  if (billingUnit === "PER_SECOND") {
    const price = model.unitPrice
    return (
      <>
        <div>按秒计费</div>
        <div className="font-medium text-foreground">{price != null ? `¥${price}` : "¥—"}/秒</div>
      </>
    )
  }
  if (billingUnit === "TOKEN_PER_M") {
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div className="font-medium text-foreground">输入 {input != null ? `¥${input}` : "¥—"}/百万</div>
        <div className="font-medium text-foreground">输出 {output != null ? `¥${output}` : "¥—"}/百万</div>
      </>
    )
  }
  if (billingUnit === "IMAGE_TOKEN") {
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div className="font-medium text-foreground">输入 {input != null ? `¥${input}` : "¥—"}/百万</div>
        <div className="font-medium text-foreground">输出 {output != null ? `¥${output}` : "¥—"}/百万</div>
      </>
    )
  }
  return (
    <>
      <div>{billingUnit}</div>
      <div className="font-medium text-foreground">—</div>
    </>
  )
}

type AccountFormState = ModelVendorAccountPayload & {
  id?: number
  apiKeyMasked?: string
  extraAuthJsonMasked?: string
  topUpEditBatch?: boolean
}

const emptyAccountForm = (): AccountFormState => ({
  vendorCode: "deepseek",
  accountName: "默认账户",
  baseUrl: "",
  balanceQueryMode: "MANUAL",
  balanceCurrency: "CNY",
  enabled: true,
})

const emptyModelForm = (): AgentModelConfigPayload & { id?: number } => ({
  vendorAccountId: undefined,
  displayName: "",
  configCode: "",
  provider: "deepseek",
  modelName: "",
  baseUrl: "",
  docsUrl: "",
  executionTask: "",
  executionOptionsJson: "",
  timeoutSeconds: 60,
  inputTokenPricePer1m: 0,
  outputTokenPricePer1m: 0,
  billingUnit: "TOKEN_PER_M",
  unitPrice: 0,
  enabled: true,
  agentEnabled: true,
  isDefault: false,
  capabilities: ["TEXT_GENERATION"],
})

const executionTaskOptions: Record<string, Array<{ value: string; label: string; capabilities: string[]; createPath: string; resultPath: string }>> = {
  kling_video: [
    { value: "text2video", label: "文生视频", capabilities: ["VIDEO_GENERATION"], createPath: "/v1/videos/text2video", resultPath: "/v1/videos/text2video/{task_id}" },
    { value: "image2video", label: "图生视频", capabilities: ["VIDEO_GENERATION"], createPath: "/v1/videos/image2video", resultPath: "/v1/videos/image2video/{task_id}" },
    { value: "multi_image2video", label: "多图参考生视频", capabilities: ["VIDEO_GENERATION"], createPath: "/v1/videos/multi-image2video", resultPath: "/v1/videos/multi-image2video/{task_id}" },
    { value: "motion_control", label: "动作控制", capabilities: ["VIDEO_GENERATION"], createPath: "/v1/videos/motion-control", resultPath: "/v1/videos/motion-control/{task_id}" },
    { value: "omni_video", label: "Omni 视频", capabilities: ["VIDEO_GENERATION"], createPath: "/v1/videos/omni-video", resultPath: "/v1/videos/omni-video/{task_id}" },
    { value: "image_generation", label: "图像生成", capabilities: ["IMAGE_GENERATION"], createPath: "/v1/images/generations", resultPath: "/v1/images/generations/{task_id}" },
    { value: "omni_image", label: "Omni 生图", capabilities: ["IMAGE_GENERATION"], createPath: "/v1/images/omni-image", resultPath: "/v1/images/omni-image/{task_id}" },
  ],
  seedance: [
    { value: "video_generation", label: "视频生成", capabilities: ["VIDEO_GENERATION", "DIGITAL_HUMAN"], createPath: "/contents/generations/tasks", resultPath: "/contents/generations/tasks/{task_id}" },
  ],
  volcengine_images: [
    { value: "image_generation", label: "图像生成", capabilities: ["IMAGE_GENERATION"], createPath: "/images/generations", resultPath: "/images/generations" },
  ],
  openai_compatible: [
    { value: "chat", label: "对话 Chat", capabilities: ["TEXT_GENERATION"], createPath: "/chat/completions", resultPath: "/chat/completions" },
  ],
}

const emptyVendorForm = (): ModelVendorPayload => ({
  vendorCode: "",
  vendorLabel: "",
  iconAsset: "api",
  sortOrder: 0,
  enabled: true,
})

interface UnifiedApiSettingsProps {
  refreshKey?: number
}

type VendorFilter = "ALL" | "ISSUES" | "LOW_BALANCE" | "UNHEALTHY" | "DISABLED"
type VendorSort = "ISSUE_FIRST" | "MODEL_COUNT" | "NAME"

const vendorFilterOptions: Array<{ value: VendorFilter; label: string }> = [
  { value: "ALL", label: "全部渠道" },
  { value: "ISSUES", label: "只看异常" },
  { value: "LOW_BALANCE", label: "低余额" },
  { value: "UNHEALTHY", label: "连通异常" },
  { value: "DISABLED", label: "停用账户" },
]

const vendorSortOptions: Array<{ value: VendorSort; label: string }> = [
  { value: "ISSUE_FIRST", label: "异常优先" },
  { value: "MODEL_COUNT", label: "模型数优先" },
  { value: "NAME", label: "名称排序" },
]

const billingUnitOptions: Array<{ value: NonNullable<AgentModelConfigPayload["billingUnit"]>; label: string; description: string }> = [
  { value: "TOKEN_PER_M", label: "按量计费", description: "按输入/输出 Token 百万单位填写成本" },
  { value: "PER_CALL", label: "按次计费", description: "每次调用固定成本，适合图片、语音等任务" },
  { value: "PER_SECOND", label: "按秒计费", description: "视频等任务按生成秒数填写成本" },
  { value: "IMAGE_TOKEN", label: "图片 Token", description: "同时记录图片基础价和 Token 成本" },
]

function numberOrZero(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function normalizeOptionalUrl(value?: string | null) {
  return (value || "").trim().replace(/\/+$/, "")
}

function sameBaseUrl(left?: string | null, right?: string | null) {
  return normalizeOptionalUrl(left) === normalizeOptionalUrl(right)
}

function parseExtraAuthObject(extraAuthJson?: string | null): Record<string, unknown> {
  const raw = (extraAuthJson || "").trim()
  if (!raw) return {}
  const parsed = JSON.parse(raw)
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("额外鉴权 JSON 必须是对象")
  }
  return parsed as Record<string, unknown>
}

function hasTopUpEditBatch(extraAuthJson?: string | null) {
  try {
    return parseExtraAuthObject(extraAuthJson).topUpEditBatch === true
  } catch {
    return false
  }
}

function buildExtraAuthJsonWithTopUp(extraAuthJson: string | undefined, topUpEditBatch: boolean | undefined) {
  if (topUpEditBatch === undefined) return extraAuthJson
  const config = parseExtraAuthObject(extraAuthJson)
  if (topUpEditBatch) {
    config.topUpEditBatch = true
  } else {
    delete config.topUpEditBatch
  }
  const keys = Object.keys(config)
  return keys.length > 0 ? JSON.stringify(config, null, 2) : ""
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
      className={[
        "relative inline-flex h-7 w-[62px] shrink-0 items-center overflow-hidden rounded-full border px-1 text-[10px] font-black tracking-wide shadow-sm transition-all duration-300 ease-out",
        checked
          ? "border-blue-500 bg-blue-500 text-slate-950 shadow-blue-200"
          : "border-slate-200 bg-slate-100 text-slate-400 shadow-slate-100",
        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:shadow-md",
      ].join(" ")}
    >
      <span
        className={[
          "absolute left-0.5 top-0.5 h-6 w-6 rounded-full bg-white shadow-[0_2px_6px_rgba(15,23,42,0.22)] ring-1 ring-slate-200 transition-transform duration-300 ease-out",
          checked ? "translate-x-[34px]" : "translate-x-0",
        ].join(" ")}
      />
      <span
        className={[
          "z-10 w-full text-center transition-all duration-200",
          checked ? "pr-7" : "pl-7",
        ].join(" ")}
      >
        {checked ? "ON" : "OFF"}
      </span>
    </button>
  )
}

export function UnifiedApiSettings({ refreshKey = 0 }: UnifiedApiSettingsProps) {
  const [overview, setOverview] = useState<UnifiedApiOverview | null>(null)
  const [providers, setProviders] = useState<ModelProviderDescriptor[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshingBalance, setRefreshingBalance] = useState(false)

  const [accountDialogOpen, setAccountDialogOpen] = useState(false)
  const [accountForm, setAccountForm] = useState(emptyAccountForm())
  const [accountSaving, setAccountSaving] = useState(false)

  const [modelDialogOpen, setModelDialogOpen] = useState(false)
  const [modelForm, setModelForm] = useState(emptyModelForm())
  const [modelSaving, setModelSaving] = useState(false)
  const [modelVendorCode, setModelVendorCode] = useState("")

  const [vendorDialogOpen, setVendorDialogOpen] = useState(false)
  const [vendorForm, setVendorForm] = useState(emptyVendorForm())
  const [vendorSaving, setVendorSaving] = useState(false)

  const [openVendors, setOpenVendors] = useState<Record<string, boolean>>({})
  const [togglingModelId, setTogglingModelId] = useState<number | null>(null)
  const [togglingAgentModelId, setTogglingAgentModelId] = useState<number | null>(null)
  const [togglingAccountId, setTogglingAccountId] = useState<number | null>(null)
  const [testingModelId, setTestingModelId] = useState<number | null>(null)
  const [testingAccountId, setTestingAccountId] = useState<number | null>(null)
  const [vendorFilter, setVendorFilter] = useState<VendorFilter>("ALL")
  const [vendorSort, setVendorSort] = useState<VendorSort>("ISSUE_FIRST")
  const [modelKeyword, setModelKeyword] = useState("")

  const currentModelProviderMeta = useMemo(
    () => providers.find((provider) => provider.code === modelForm.provider) || null,
    [providers, modelForm.provider],
  )

  const allVendorAccounts = useMemo(
    () => overview?.vendors.flatMap((vendor) => vendor.accounts) ?? [],
    [overview],
  )

  const accountById = useMemo(
    () => new Map(allVendorAccounts.map((account) => [account.id, account])),
    [allVendorAccounts],
  )

  const modelAccountOptions = useMemo(() => {
    const vendorAccounts = overview?.vendors.find((vendor) => vendor.vendorCode === modelVendorCode)?.accounts ?? []
    const selected = modelForm.vendorAccountId ? accountById.get(modelForm.vendorAccountId) : undefined
    if (selected && !vendorAccounts.some((account) => account.id === selected.id)) {
      return [selected, ...vendorAccounts]
    }
    return vendorAccounts
  }, [accountById, modelForm.vendorAccountId, modelVendorCode, overview])

  const fetchOverviewData = useCallback(async () => {
    const [data, catalog] = await Promise.all([
      fetchUnifiedApiOverview(),
      fetchModelProviders().catch(() => [] as ModelProviderDescriptor[]),
    ])
    return { data, catalog }
  }, [])

  const refreshOverviewSilently = useCallback(async () => {
    const { data, catalog } = await fetchOverviewData()
    setOverview(data)
    setProviders(catalog)
  }, [fetchOverviewData])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const { data, catalog } = await fetchOverviewData()
      setOverview(data)
      setProviders(catalog)
      const initialOpen: Record<string, boolean> = {}
      data.vendors.forEach((vendor) => {
        initialOpen[vendor.vendorCode] = false
      })
      setOpenVendors(initialOpen)
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setError(
          "统一 API 接口未找到，请重新编译并重启后端（本地：在 backend 目录执行 mvn spring-boot:run；Docker：docker compose build backend && docker compose up -d backend）。"
            + (err.traceId ? ` traceId=${err.traceId}` : ""),
        )
      } else {
        setError(err instanceof ApiError ? err.message : "加载统一 API 概览失败")
      }
    } finally {
      setLoading(false)
    }
  }, [fetchOverviewData])

  const patchModelEnabled = useCallback((modelId: number, enabled: boolean) => {
    setOverview((prev) => {
      if (!prev) return prev
      let enabledDelta = 0
      const vendors = prev.vendors.map((vendor) => ({
        ...vendor,
        models: vendor.models.map((item) => {
          if (item.id !== modelId) return item
          if (item.enabled !== enabled) {
            enabledDelta = enabled ? 1 : -1
          }
          return { ...item, enabled }
        }),
      }))
      return {
        ...prev,
        summary: {
          ...prev.summary,
          enabledModelCount: Math.max(0, prev.summary.enabledModelCount + enabledDelta),
        },
        vendors,
      }
    })
  }, [])

  const patchModelAgentEnabled = useCallback((modelId: number, agentEnabled: boolean) => {
    setOverview((prev) => {
      if (!prev) return prev
      return {
        ...prev,
        vendors: prev.vendors.map((vendor) => ({
          ...vendor,
          models: vendor.models.map((item) => (item.id === modelId ? { ...item, agentEnabled } : item)),
        })),
      }
    })
  }, [])

  const patchVendorAccount = useCallback((updated?: ModelVendorAccount | null) => {
    if (!updated?.id) return
    setOverview((prev) => {
      if (!prev) return prev
      return {
        ...prev,
        vendors: prev.vendors.map((vendor) => ({
          ...vendor,
          accounts: vendor.accounts.map((account) =>
            account?.id === updated.id ? updated : account,
          ),
        })),
      }
    })
  }, [])

  const patchAccountEnabled = useCallback((accountId: number, enabled: boolean) => {
    setOverview((prev) => {
      if (!prev) return prev
      return {
        ...prev,
        vendors: prev.vendors.map((vendor) => ({
          ...vendor,
          accounts: vendor.accounts.map((account) => (account.id === accountId ? { ...account, enabled } : account)),
        })),
      }
    })
  }, [])

  function displayAccountName(account: ModelVendorAccount, index?: number) {
    const name = (account.accountName || "").trim()
    if (!name || name === "默认账户") {
      return `账户${typeof index === "number" && index >= 0 ? index + 1 : account.id}`
    }
    return name
  }

  function openCreateVendor() {
    setVendorForm(emptyVendorForm())
    setVendorDialogOpen(true)
  }

  async function saveVendor() {
    setVendorSaving(true)
    setError(null)
    try {
      const code = vendorForm.vendorCode.trim().toLowerCase()
      const label = vendorForm.vendorLabel.trim()
      await upsertModelVendor({
        vendorCode: code,
        vendorLabel: label,
        iconAsset: (vendorForm.iconAsset || code || "api").trim(),
        sortOrder: Number(vendorForm.sortOrder ?? 0),
        enabled: vendorForm.enabled !== false,
      })
      setVendorDialogOpen(false)
      await refreshOverviewSilently()
      toast.success("厂商已添加", { description: label })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "添加厂商失败")
    } finally {
      setVendorSaving(false)
    }
  }

  async function deleteVendor(vendor: UnifiedApiVendorGroup) {
    const confirmed = window.confirm(
      `是否确认删除 ${vendor.label}？该操作会一键删除该厂商绑定的工具和该厂商的所有模型。`,
    )
    if (!confirmed) return
    setError(null)
    const toastId = toast.loading(`${vendor.label}：正在删除厂商…`)
    try {
      await deleteModelVendor(vendor.vendorCode)
      await refreshOverviewSilently()
      toast.success(`${vendor.label}：厂商已删除`, { id: toastId })
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "删除厂商失败"
      setError(message)
      toast.error(`${vendor.label}：删除失败`, { id: toastId, description: message })
    }
  }

  const runRefreshBalance = useCallback(
    async (account: ModelVendorAccount, vendorLabel: string) => {
      setError(null)
      const toastId = toast.loading(`${vendorLabel}：正在刷新余额…`)
      try {
        const updated = await refreshModelVendorAccountBalance(account.id)
        patchVendorAccount(updated)
        if (updated.balanceAmount != null) {
          toast.success(`${vendorLabel}：余额已更新`, {
            id: toastId,
            description: formatBalance(updated),
          })
        } else if (updated.balanceQueryMode === "NONE") {
          toast.info(`${vendorLabel}：不支持自动查余额`, {
            id: toastId,
            description: updated.balanceErrorMessage || "请打开控制台或手填余额",
          })
        } else if (updated.balanceErrorMessage) {
          toast.warning(`${vendorLabel}：未能获取余额`, {
            id: toastId,
            description: diagnoseProviderIssue(updated.balanceErrorMessage, "balance", updated),
          })
        } else {
          toast.info(`${vendorLabel}：暂无余额数据`, {
            id: toastId,
            description: "可在账户设置中手动填写余额金额",
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "刷新失败"
        toast.error(`${vendorLabel}：刷新失败`, { id: toastId, description: diagnoseProviderIssue(message, "balance", account) })
        setError(message)
      }
    },
    [patchVendorAccount],
  )

  const runConnectivityTest = useCallback(
    async (account: ModelVendorAccount, vendorLabel: string) => {
      setTestingAccountId(account.id)
      setError(null)
      const toastId = toast.loading(`${vendorLabel}：正在测试连通性…`)
      try {
        const result = await testModelVendorAccount(account.id)
        patchVendorAccount(result.account ?? account)
        const latencyText =
          result.latencyMs != null && result.latencyMs >= 0 ? `（${result.latencyMs} ms）` : ""
        const modelHint =
          result.provider && result.modelName ? ` · ${result.provider} / ${result.modelName}` : ""
        if (result.success) {
          toast.success(`${vendorLabel}：连通正常${latencyText}`, {
            id: toastId,
            description: `${result.message || "连接成功"}${modelHint}`,
          })
        } else {
          toast.error(`${vendorLabel}：连通失败`, {
            id: toastId,
            description: diagnoseProviderIssue(result.message || "连接失败", "connectivity", result.account ?? account),
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "测试失败"
        toast.error(`${vendorLabel}：测试失败`, { id: toastId, description: diagnoseProviderIssue(message, "connectivity", account) })
        setError(message)
      } finally {
        setTestingAccountId(null)
      }
    },
    [patchVendorAccount],
  )

  const runModelTest = useCallback(async (model: UnifiedApiModelItem, vendorLabel: string) => {
    setTestingModelId(model.id)
    setError(null)
    const label = model.displayName || model.modelName
    const toastId = toast.loading(`${label}：正在测试连接…`)
    try {
      const result = await testAgentModelConfigById(model.id)
      const latencyText =
        result.latencyMs != null && result.latencyMs >= 0 ? `（${result.latencyMs} ms）` : ""
      const sampleText = result.sample?.trim() ? ` · 响应：${result.sample.trim().slice(0, 80)}` : ""
      if (result.success) {
        await refreshOverviewSilently()
        toast.success(`${label}：连接成功${latencyText}`, {
          id: toastId,
          description: `${result.message || "测试通过"}${sampleText}`,
        })
      } else {
        await refreshOverviewSilently()
        toast.error(`${label}：连接失败`, {
          id: toastId,
          description: diagnoseProviderIssue(result.message || "测试未通过", "model"),
        })
      }
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "测试失败"
      toast.error(`${vendorLabel}：${label} 测试失败`, { id: toastId, description: diagnoseProviderIssue(message, "model") })
      setError(message)
      await refreshOverviewSilently()
    } finally {
      setTestingModelId(null)
    }
  }, [refreshOverviewSilently])

  const toggleModelEnabled = useCallback(
    async (model: UnifiedApiModelItem, enabled: boolean) => {
      const previous = model.enabled
      const previousAgentEnabled = model.agentEnabled ?? true
      patchModelEnabled(model.id, enabled)
      if (!enabled) {
        patchModelAgentEnabled(model.id, false)
      }
      setTogglingModelId(model.id)
      setError(null)
      try {
        await updateAgentModelConfig(model.id, {
          vendorAccountId: model.vendorAccountId ?? undefined,
          displayName: model.displayName || "",
          configCode: model.configCode || "",
          provider: model.provider,
          modelName: model.modelName,
          baseUrl: model.baseUrl || undefined,
          minimaxGroupId: model.minimaxGroupId || undefined,
          consoleUrl: model.consoleUrl || undefined,
          balanceUrl: model.balanceUrl || undefined,
          docsUrl: model.docsUrl || undefined,
          enabled,
          agentEnabled: enabled ? model.agentEnabled ?? true : false,
          isDefault: model.isDefault ?? false,
          capabilities: model.capabilities ? [...model.capabilities] : [],
          timeoutSeconds: model.timeoutSeconds ?? 60,
          connectTimeoutSeconds: model.connectTimeoutSeconds ?? undefined,
          readTimeoutSeconds: model.readTimeoutSeconds ?? undefined,
          inputTokenPricePer1m: model.inputTokenPricePer1m ?? 0,
          outputTokenPricePer1m: model.outputTokenPricePer1m ?? 0,
          billingUnit: (model.billingUnit as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
          unitPrice: model.unitPrice ?? 0,
        })
      } catch (err) {
        patchModelEnabled(model.id, previous)
        patchModelAgentEnabled(model.id, previousAgentEnabled)
        setError(err instanceof ApiError ? err.message : "更新失败")
      } finally {
        setTogglingModelId(null)
      }
    },
    [patchModelEnabled, patchModelAgentEnabled],
  )

  const toggleModelAgentEnabled = useCallback(
    async (model: UnifiedApiModelItem, agentEnabled: boolean) => {
      if (agentEnabled && !model.enabled) {
        setError("请先启用模型，再开启 Agent 可选")
        return
      }
      const previous = model.agentEnabled ?? true
      patchModelAgentEnabled(model.id, agentEnabled)
      setTogglingAgentModelId(model.id)
      setError(null)
      try {
        await updateAgentModelConfig(model.id, {
          vendorAccountId: model.vendorAccountId ?? undefined,
          displayName: model.displayName || "",
          configCode: model.configCode || "",
          provider: model.provider,
          modelName: model.modelName,
          baseUrl: model.baseUrl || undefined,
          minimaxGroupId: model.minimaxGroupId || undefined,
          consoleUrl: model.consoleUrl || undefined,
          balanceUrl: model.balanceUrl || undefined,
          docsUrl: model.docsUrl || undefined,
          enabled: model.enabled,
          agentEnabled,
          isDefault: model.isDefault ?? false,
          capabilities: model.capabilities ? [...model.capabilities] : [],
          timeoutSeconds: model.timeoutSeconds ?? 60,
          connectTimeoutSeconds: model.connectTimeoutSeconds ?? undefined,
          readTimeoutSeconds: model.readTimeoutSeconds ?? undefined,
          inputTokenPricePer1m: model.inputTokenPricePer1m ?? 0,
          outputTokenPricePer1m: model.outputTokenPricePer1m ?? 0,
          billingUnit: (model.billingUnit as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
          unitPrice: model.unitPrice ?? 0,
        })
      } catch (err) {
        patchModelAgentEnabled(model.id, previous)
        setError(err instanceof ApiError ? err.message : "Agent 可选更新失败")
      } finally {
        setTogglingAgentModelId(null)
      }
    },
    [patchModelAgentEnabled],
  )

  const toggleAccountEnabled = useCallback(
    async (account: ModelVendorAccount, enabled: boolean) => {
      const previous = account.enabled
      patchAccountEnabled(account.id, enabled)
      setTogglingAccountId(account.id)
      setError(null)
      try {
        const updated = await updateModelVendorAccount(account.id, {
          vendorCode: account.vendorCode,
          accountName: account.accountName,
          baseUrl: account.baseUrl || "",
          apiKey: account.apiKey || "",
          extraAuthJson: account.extraAuthJson || "",
          consoleUrl: account.consoleUrl || "",
          balanceUrl: account.balanceUrl || "",
          balanceQueryMode: account.balanceQueryMode,
          balanceAmount: account.balanceAmount ?? undefined,
          balanceCurrency: account.balanceCurrency || "CNY",
          balanceLowThreshold: account.balanceLowThreshold ?? undefined,
          enabled,
        })
        patchVendorAccount(updated)
      } catch (err) {
        patchAccountEnabled(account.id, previous)
        setError(err instanceof ApiError ? err.message : "账户启用状态更新失败")
      } finally {
        setTogglingAccountId(null)
      }
    },
    [patchAccountEnabled, patchVendorAccount],
  )

  useEffect(() => {
    load()
  }, [load, refreshKey])

  const filteredVendors = useMemo(() => {
    if (!overview) return []
    const keyword = modelKeyword.trim().toLowerCase()
    const issueScore = (vendor: UnifiedApiVendorGroup) => {
      const lowBalance = vendor.accounts.filter(isNegativeBalance).length
      const unhealthy = vendor.accounts.filter((a) => a.healthStatus === "ERROR").length
      const disabled = vendor.accounts.filter((a) => !a.enabled).length
      const unbound = vendor.models.filter((m) => !m.vendorAccountId).length
      return lowBalance * 4 + unhealthy * 5 + disabled * 2 + unbound
    }
    return overview.vendors
      .map((vendor) => {
        const models = keyword
          ? vendor.models.filter((model) =>
              [
                model.displayName,
                model.modelName,
                model.configCode,
                model.provider,
                model.vendorAccountName,
                ...(model.capabilities || []),
              ]
                .filter(Boolean)
                .join(" ")
                .toLowerCase()
                .includes(keyword),
            )
          : vendor.models
        return { ...vendor, models }
      })
      .filter((vendor) => {
        if (keyword && vendor.models.length === 0 && !vendor.label.toLowerCase().includes(keyword)) return false
        if (vendorFilter === "LOW_BALANCE") {
          return vendor.accounts.some(isNegativeBalance)
        }
        if (vendorFilter === "UNHEALTHY") return vendor.accounts.some((a) => a.healthStatus === "ERROR")
        if (vendorFilter === "DISABLED") return vendor.accounts.some((a) => !a.enabled)
        if (vendorFilter === "ISSUES") return issueScore(vendor) > 0
        return true
      })
      .sort((a, b) => {
        if (vendorSort === "MODEL_COUNT") return b.models.length - a.models.length || a.label.localeCompare(b.label)
        if (vendorSort === "NAME") return a.label.localeCompare(b.label)
        return issueScore(b) - issueScore(a) || b.models.length - a.models.length || a.label.localeCompare(b.label)
      })
  }, [modelKeyword, overview, vendorFilter, vendorSort])

  const gatewayHealth = useMemo(() => {
    if (!overview) return { enabledRate: 0, issueCount: 0 }
    const total = Math.max(1, overview.summary.modelCount)
    return {
      enabledRate: Math.round((overview.summary.enabledModelCount / total) * 100),
      issueCount: overview.summary.lowBalanceCount + overview.summary.unhealthyAccountCount,
    }
  }, [overview])

  async function handleRefreshAllBalances() {
    setRefreshingBalance(true)
    setError(null)
    try {
      await refreshAllModelVendorAccountBalances()
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "刷新余额失败")
    } finally {
      setRefreshingBalance(false)
    }
  }

  function openCreateAccount(vendorCode: string, label: string) {
    const meta = providerForVendor(providers, vendorCode)
    const existingCount = overview?.vendors.find((vendor) => vendor.vendorCode === vendorCode)?.accounts.length ?? 0
    setAccountForm({
      ...emptyAccountForm(),
      vendorCode,
      accountName: `账户${existingCount + 1}`,
      baseUrl: meta?.defaultBaseUrl || "",
      balanceQueryMode: defaultBalanceModeForVendor(vendorCode),
    })
    setAccountDialogOpen(true)
  }

  function openEditAccount(account: ModelVendorAccount) {
    setAccountForm({
      id: account.id,
      vendorCode: account.vendorCode,
      accountName: account.accountName,
      baseUrl: account.baseUrl || "",
      apiKey: account.apiKey || "",
      apiKeyMasked: account.apiKeyMasked || "",
      extraAuthJson: account.extraAuthJson || "",
      extraAuthJsonMasked: account.extraAuthJsonMasked || "",
      topUpEditBatch: hasTopUpEditBatch(account.extraAuthJson),
      consoleUrl: account.consoleUrl || "",
      balanceUrl: account.balanceUrl || "",
      balanceQueryMode: account.balanceQueryMode || "MANUAL",
      balanceAmount: account.balanceAmount ?? undefined,
      balanceCurrency: account.balanceCurrency || "CNY",
      balanceLowThreshold: account.balanceLowThreshold ?? undefined,
      enabled: account.enabled,
    })
    setAccountDialogOpen(true)
  }

  async function saveAccount() {
    setAccountSaving(true)
    setError(null)
    try {
      const extraAuthJson = buildExtraAuthJsonWithTopUp(accountForm.extraAuthJson, accountForm.topUpEditBatch)
      const payload: ModelVendorAccountPayload = {
        vendorCode: accountForm.vendorCode,
        accountName: accountForm.accountName,
        baseUrl: accountForm.baseUrl,
        apiKey: accountForm.apiKey,
        clearApiKey: accountForm.clearApiKey,
        extraAuthJson,
        clearExtraAuthJson: accountForm.clearExtraAuthJson || (accountForm.topUpEditBatch !== undefined && !extraAuthJson),
        consoleUrl: accountForm.consoleUrl,
        balanceUrl: accountForm.balanceUrl,
        balanceQueryMode: accountForm.balanceQueryMode,
        balanceAmount: accountForm.balanceAmount,
        balanceCurrency: accountForm.balanceCurrency,
        balanceLowThreshold: accountForm.balanceLowThreshold,
        enabled: accountForm.enabled,
      }
      if (accountForm.id) {
        await updateModelVendorAccount(accountForm.id, payload)
      } else {
        await createModelVendorAccount(payload)
      }
      setAccountDialogOpen(false)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : "保存账户失败")
    } finally {
      setAccountSaving(false)
    }
  }

  function openCreateModel(vendor: UnifiedApiVendorGroup, accountId: number) {
    const account = vendor.accounts.find((a) => a.id === accountId) || vendor.accounts[0]
    const meta = providerForVendor(providers, vendor.vendorCode, vendor.models[0]?.provider)
    const defaultProvider = meta?.code || vendor.models[0]?.provider || "openai_compatible"
    setModelVendorCode(vendor.vendorCode)
    setModelForm({
      ...emptyModelForm(),
      vendorAccountId: account?.id,
      provider: defaultProvider,
      modelName: meta?.defaultModel || "",
      baseUrl: "",
      docsUrl: "",
      capabilities: modelCapabilitiesForProvider(undefined, meta),
      executionTask: routeTasksForModel(defaultProvider, modelCapabilitiesForProvider(undefined, meta))[0]?.value || "",
      executionOptionsJson: "",
      billingUnit: (meta?.billingDefault as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
    })
    setModelDialogOpen(true)
  }

  function openEditModel(model: UnifiedApiModelItem, vendorCode: string) {
    const meta = providerForVendor(providers, vendorCode, model.provider)
    setModelVendorCode(vendorCode)
    setModelForm({
      id: model.id,
      vendorAccountId: model.vendorAccountId ?? undefined,
      displayName: model.displayName || "",
      configCode: model.configCode || "",
      provider: model.provider,
      modelName: model.modelName,
      baseUrl: model.baseUrl || "",
      docsUrl: model.docsUrl || "",
      executionTask: model.executionTask || "",
      executionOptionsJson: "",
      enabled: model.enabled,
      agentEnabled: model.agentEnabled ?? true,
      isDefault: model.isDefault ?? false,
      capabilities: modelCapabilitiesForProvider(model.capabilities, meta),
      timeoutSeconds: 60,
      inputTokenPricePer1m: model.inputTokenPricePer1m ?? 0,
      outputTokenPricePer1m: model.outputTokenPricePer1m ?? 0,
      billingUnit: (model.billingUnit as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
      unitPrice: model.unitPrice ?? 0,
    })
    setModelDialogOpen(true)
  }

  function toggleModelCapability(capability: string) {
    setModelForm((current) => {
      const allowed = currentModelProviderMeta?.capabilities?.length
        ? new Set(currentModelProviderMeta.capabilities.map((item) => item.toUpperCase()))
        : null
      const base = allowed
        ? (current.capabilities || []).filter((item) => allowed.has(item.toUpperCase()))
        : current.capabilities || []
      const exists = base.some((item) => item.toUpperCase() === capability.toUpperCase())
      const next = exists
        ? base.filter((item) => item.toUpperCase() !== capability.toUpperCase())
        : [...base, capability]
      const compatibleTasks = routeTasksForModel(current.provider, next)
      const executionTask = compatibleTasks.some((task) => task.value === current.executionTask)
        ? current.executionTask
        : compatibleTasks[0]?.value || ""
      return { ...current, capabilities: next, executionTask }
    })
  }

  async function saveModel() {
    if (!modelForm.vendorAccountId) {
      setError("请选择厂商账户")
      return
    }
    if (!modelForm.capabilities || modelForm.capabilities.length === 0) {
      setError("请至少选择一种模型能力")
      return
    }
    if (currentModelProviderMeta) {
      const allowed = new Set(currentModelProviderMeta.capabilities.map((capability) => capability.toUpperCase()))
      const invalid = modelForm.capabilities.find((capability) => !allowed.has(capability.toUpperCase()))
      if (invalid) {
        setError(`能力 ${capabilityLabel(invalid)} 不适用于当前供应商 ${currentModelProviderMeta.label}`)
        return
      }
    }
    setModelSaving(true)
    setError(null)
    try {
      const scrollY = typeof window === "undefined" ? 0 : window.scrollY
      const payload: AgentModelConfigPayload = {
        ...modelForm,
        apiKey: "",
        inputTokenPricePer1m: numberOrZero(modelForm.inputTokenPricePer1m),
        outputTokenPricePer1m: numberOrZero(modelForm.outputTokenPricePer1m),
        unitPrice: numberOrZero(modelForm.unitPrice),
      }
      if (modelForm.id) {
        await updateAgentModelConfig(modelForm.id, payload)
      } else {
        await createAgentModelConfig(payload)
      }
      setModelDialogOpen(false)
      await refreshOverviewSilently()
      if (typeof window !== "undefined") {
        window.requestAnimationFrame(() => window.scrollTo({ top: scrollY, behavior: "auto" }))
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存模型失败")
    } finally {
      setModelSaving(false)
    }
  }

  function renderVendorAccountMenu(account: ModelVendorAccount, vendorLabel: string) {
    return (
      <div className="inline-flex items-center gap-1">
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0"
          aria-label={`${vendorLabel} API 与密钥`}
          title="API 与密钥"
          onClick={() => openEditAccount(account)}
        >
          <Settings2 className="h-4 w-4" />
        </Button>
        {account.consoleUrl ? (
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8" asChild title="打开控制台">
            <a href={account.consoleUrl} target="_blank" rel="noreferrer" aria-label={`${vendorLabel} 控制台`}>
              <ExternalLink className="h-4 w-4" />
            </a>
          </Button>
        ) : null}
        {account.balanceUrl ? (
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8" asChild title="打开余额页">
            <a href={account.balanceUrl} target="_blank" rel="noreferrer" aria-label={`${vendorLabel} 余额页`}>
              <Wallet className="h-4 w-4" />
            </a>
          </Button>
        ) : null}
      </div>
    )
  }

  function renderVendorSection(vendor: UnifiedApiVendorGroup) {
    const primaryAccount = pickPrimaryAccount(vendor.accounts)
    const isOpen = openVendors[vendor.vendorCode] ?? false
    const accountIdForNewModel = primaryAccount?.id
    const lowBalanceCount = vendor.accounts.filter(isNegativeBalance).length
    const unhealthyCount = vendor.accounts.filter((account) => account.healthStatus === "ERROR").length

    return (
      <Collapsible
        key={vendor.vendorCode}
        open={isOpen}
        onOpenChange={(open) => setOpenVendors((prev) => ({ ...prev, [vendor.vendorCode]: open }))}
        className="rounded-xl border bg-card"
      >
        <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
          <CollapsibleTrigger className="flex min-w-0 flex-1 items-center gap-3 text-left">
            <ChevronDown className={`h-4 w-4 shrink-0 transition-transform ${isOpen ? "" : "-rotate-90"}`} />
            <VendorIcon iconAsset={vendor.iconAsset} label={vendor.label} />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="font-semibold">{vendor.label}</p>
                {lowBalanceCount > 0 ? <Badge variant="destructive" className="text-xs">低余额 {lowBalanceCount}</Badge> : null}
                {unhealthyCount > 0 ? <Badge variant="destructive" className="text-xs">异常 {unhealthyCount}</Badge> : null}
              </div>
              <p className="text-xs text-muted-foreground">{vendor.accounts.length} 个账户 · {vendor.models.length} 个模型</p>
            </div>
          </CollapsibleTrigger>
          <div className="flex flex-wrap items-center gap-2">
            {primaryAccount ? (
              <div className="flex flex-col items-end gap-0.5 sm:flex-row sm:items-center sm:gap-2">
                <span className="text-sm font-semibold tabular-nums">{formatBalance(primaryAccount)}</span>
                {balanceStatusBadge(primaryAccount)}
                {primaryAccount.healthStatus === "OK" ? (
                  <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-xs text-emerald-700">
                    <CheckCircle2 className="mr-1 h-3 w-3" />
                    连通正常
                  </Badge>
                ) : primaryAccount.healthStatus === "ERROR" ? (
                  <Badge variant="destructive" className="text-xs">
                    连通异常
                  </Badge>
                ) : null}
                {formatBalanceUpdatedAt(primaryAccount.balanceUpdatedAt) ? (
                  <span className="text-xs text-muted-foreground">{formatBalanceUpdatedAt(primaryAccount.balanceUpdatedAt)}</span>
                ) : null}
                <EmbeddedOnOffSwitch
                  checked={primaryAccount.enabled}
                  disabled={togglingAccountId === primaryAccount.id}
                  label={`启用账户 ${primaryAccount.accountName}`}
                  onCheckedChange={(enabled) => toggleAccountEnabled(primaryAccount, enabled)}
                />
                <Button type="button" variant="outline" size="icon" className="h-8 w-8" title="刷新该厂商余额" onClick={() => runRefreshBalance(primaryAccount, vendor.label)}>
                  <RefreshCw className="h-4 w-4" />
                </Button>
                <Button type="button" variant="outline" size="icon" className="h-8 w-8 text-destructive" title="删除厂商" onClick={() => deleteVendor(vendor)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ) : (
              <Button type="button" variant="outline" size="icon" className="h-8 w-8 text-destructive" title="删除厂商" onClick={() => deleteVendor(vendor)}>
                <Trash2 className="h-4 w-4" />
              </Button>
            )}
          </div>
        </div>
        <CollapsibleContent className="px-4 py-3">
          {vendor.accounts.length > 0 ? (
            <div className="mb-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {vendor.accounts.map((account, accountIndex) => (
                <div key={account.id} className={`rounded-lg border p-3 ${accountCardTone(account)}`}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="flex min-w-0 items-center gap-2">
                        <p className="truncate text-sm font-medium">{displayAccountName(account, accountIndex)}</p>
                        <EmbeddedOnOffSwitch
                          checked={account.enabled}
                          disabled={togglingAccountId === account.id}
                          label={`启用账户 ${displayAccountName(account, accountIndex)}`}
                          onCheckedChange={(enabled) => toggleAccountEnabled(account, enabled)}
                        />
                      </div>
                      <p className="truncate text-xs text-muted-foreground">{account.baseUrl || "未配置 Base URL"}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Button
                        type="button"
                        variant={testingAccountId === account.id ? "secondary" : "ghost"}
                        size="icon"
                        className="h-8 w-8"
                        disabled={testingAccountId === account.id}
                        title="测试连接"
                        onClick={() => runConnectivityTest(account, vendor.label)}
                      >
                        {testingAccountId === account.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap className="h-4 w-4" />}
                      </Button>
                      {renderVendorAccountMenu(account, vendor.label)}
                    </div>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    {balanceStatusBadge(account)}
                    <Badge variant={account.enabled ? "outline" : "destructive"}>{account.enabled ? "已启用" : "已停用"}</Badge>
                    <span className="text-xs text-muted-foreground">{account.modelCount} 个模型</span>
                  </div>
                  <p className="mt-2 text-xs font-medium tabular-nums">{formatBalance(account)}</p>
                  <p className={`mt-1 text-xs ${hasAccountCredential(account) ? "text-muted-foreground" : "text-amber-700"}`}>
                    {accountCredentialLabel(account)}
                  </p>
                </div>
              ))}
            </div>
          ) : null}
          {vendor.accounts.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">请先接入厂商账户并配置凭据，再添加模型。</p>
          ) : vendor.models.length === 0 ? (
            <div className="space-y-3 py-2">
              <p className="text-center text-sm text-muted-foreground">暂无模型</p>
              <div className="flex justify-center">
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  disabled={!accountIdForNewModel}
                  onClick={() => accountIdForNewModel && openCreateModel(vendor, accountIdForNewModel)}
                >
                  <Plus className="mr-1 h-3 w-3" />
                  添加模型
                </Button>
              </div>
            </div>
          ) : (
            <>
              <Table className="table-fixed text-center">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[220px] text-center">模型名称</TableHead>
                    <TableHead className="w-[150px] text-center">{"API \u8d26\u6237"}</TableHead>
                    <TableHead className="w-[180px] text-center">能力</TableHead>
                    <TableHead className="w-[120px] text-center">文档</TableHead>
                    <TableHead className="w-[150px] text-center">成本</TableHead>
                    <TableHead className="w-[112px] text-center">Agent 可选</TableHead>
                    <TableHead className="w-[112px] text-center">启用</TableHead>
                    <TableHead className="w-[180px] text-center">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {vendor.models.map((model) => (
                    <TableRow key={model.id} className={modelRowTone(model, vendor)}>
                      <TableCell className="align-middle">
                        <div className="mx-auto flex max-w-[240px] min-w-0 items-center justify-center gap-2 text-left">
                          <VendorIcon iconAsset={vendor.iconAsset} label={vendor.label} />
                          <div className="min-w-0">
                            <div className="truncate font-medium">
                              {model.displayName || model.modelName}
                              {model.isDefault ? <Star className="ml-1 inline h-3 w-3 text-amber-500" /> : null}
                            </div>
                            <p className="truncate font-mono text-xs text-muted-foreground">{model.modelName}</p>
                          </div>
                        </div>
                        {!model.vendorAccountId ? (
                          <Badge variant="outline" className="mt-1 text-xs text-amber-700">
                            未绑定账户                          </Badge>
                        ) : null}
                      </TableCell>
                      <TableCell className="align-middle text-center">
                        {model.vendorAccountId ? (() => {
                          const boundAccount = accountById.get(model.vendorAccountId) || vendor.accounts.find((account) => account.id === model.vendorAccountId)
                          const accountIndex = vendor.accounts.findIndex((account) => account.id === model.vendorAccountId)
                          const fallbackAccount = {
                            id: model.vendorAccountId,
                            accountName: model.vendorAccountName || "",
                            apiKeyMasked: null,
                            extraAuthJsonMasked: null,
                          } as ModelVendorAccount
                          const account = boundAccount || fallbackAccount
                          return (
                            <div className="mx-auto grid max-w-[170px] gap-1 text-left">
                              <Badge
                                variant="outline"
                                className="w-fit max-w-[170px] truncate text-xs"
                                title={`模型 ${model.displayName || model.modelName} 使用账号 #${model.vendorAccountId}：${displayAccountName(account, accountIndex)}`}
                              >
                                #{model.vendorAccountId} {displayAccountName(account, accountIndex)}
                              </Badge>
                              <span className={`truncate text-[11px] ${hasAccountCredential(account) ? "text-muted-foreground" : "text-amber-700"}`}>
                                {boundAccount ? accountCredentialLabel(boundAccount) : "账号详情未加载"}
                              </span>
                              {boundAccount && boundAccount.vendorCode !== vendor.vendorCode ? (
                                <span className="truncate text-[11px] text-amber-700">
                                  当前绑定账号属于 {boundAccount.vendorLabel || boundAccount.vendorCode}
                                </span>
                              ) : null}
                            </div>
                          )
                        })() : vendor.accounts.length > 0 ? (
                          <Badge
                            variant="outline"
                            className="max-w-[150px] truncate text-xs text-amber-700"
                            title={vendor.accounts.map((account, index) => displayAccountName(account, index)).join("\u3001")}
                          >{"\u53ef\u5339\u914d\uff1a"}{vendor.accounts.map((account, index) => displayAccountName(account, index)).slice(0, 3).join("\u3001")}</Badge>
                        ) : (
                          <Badge variant="outline" className="text-xs text-amber-700">{"\u672a\u7ed1\u5b9a\u8d26\u6237"}</Badge>
                        )}
                      </TableCell>
                      <TableCell className="align-middle">
                        <div className="flex flex-wrap justify-center gap-1">
                          {(model.capabilities || []).map((cap) => (
                            <Badge key={cap} variant="secondary" className="text-xs">
                              {capabilityLabel(cap)}
                            </Badge>
                          ))}
                        </div>
                      </TableCell>
                      <TableCell className="align-middle text-center">
                        {model.docsUrl ? (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="h-8 px-2 text-xs"
                            asChild
                          >
                            <a href={model.docsUrl} target="_blank" rel="noreferrer" title={model.docsUrl}>
                              <ExternalLink className="mr-1 h-3 w-3" />
                              API 文档
                            </a>
                          </Button>
                        ) : (
                          <span className="text-xs text-muted-foreground">未配置</span>
                        )}
                      </TableCell>
                      <TableCell className="align-middle">
                        <div className="space-y-1 text-center text-xs text-muted-foreground">
                          {renderModelCost(model)}
                        </div>
                      </TableCell>
                      <TableCell className="align-middle">
                        <EmbeddedOnOffSwitch
                          checked={model.agentEnabled !== false}
                          disabled={
                            togglingAgentModelId === model.id
                            || (model.agentEnabled === false && !canEnableAgentForModel(model, vendor))
                          }
                          label={`Agent 可选 ${model.displayName || model.modelName}`}
                          onCheckedChange={(agentEnabled) => toggleModelAgentEnabled(model, agentEnabled)}
                        />
                      </TableCell>
                      <TableCell className="align-middle">
                        <EmbeddedOnOffSwitch
                          checked={model.enabled}
                          disabled={togglingModelId === model.id}
                          label={`启用 ${model.displayName || model.modelName}`}
                          onCheckedChange={(enabled) => toggleModelEnabled(model, enabled)}
                        />
                      </TableCell>
                      <TableCell className="align-middle text-center">
                        <div className="inline-flex items-center justify-center gap-1">
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="h-8 w-8"
                            disabled={testingModelId === model.id || !model.vendorAccountId}
                            title={!model.vendorAccountId ? "请先绑定厂商账户" : "测试连接"}
                            onClick={() => runModelTest(model, vendor.label)}
                          >
                            {testingModelId === model.id ? (
                              <Loader2 className="h-4 w-4 animate-spin" />
                            ) : (
                              <Zap className="h-4 w-4" />
                            )}
                          </Button>
                          <Button type="button" variant="ghost" size="sm" className="h-8" onClick={() => openEditModel(model, vendor.vendorCode)}>
                            编辑
                          </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8 text-destructive"
                          onClick={async () => {
                            if (!window.confirm("确认删除该模型配置？")) return
                            try {
                              await deleteAgentModelConfig(model.id)
                              await refreshOverviewSilently()
                            } catch (err) {
                              setError(err instanceof ApiError ? err.message : "删除失败")
                            }
                          }}
                        >
                          <Trash2 className="h-3 w-3" />
                        </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="mt-3 flex justify-end">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={!accountIdForNewModel}
                  onClick={() => accountIdForNewModel && openCreateModel(vendor, accountIdForNewModel)}
                >
                  <Plus className="mr-1 h-3 w-3" />
                  添加模型
                </Button>
              </div>
            </>
          )}
        </CollapsibleContent>
      </Collapsible>
    )
  }

  const unconfiguredVendors = overview?.unconfiguredVendors.filter((vendor) => {
    const code = vendor.vendorCode.toLowerCase()
    return code !== "infinite_talk" && code !== "infinitetalk"
  }) ?? []

  return (
    <div className="space-y-6">
      <div className="grid gap-3 md:grid-cols-4">
        <Card className="border-primary/20 bg-primary/5">
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><Layers className="h-4 w-4" />渠道账户</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.accountCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">{overview?.summary.vendorCount ?? "--"} 个厂商已接入</CardContent>
        </Card>
        <Card>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><ServerCog className="h-4 w-4" />模型池</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.modelCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">启用 {overview?.summary.enabledModelCount ?? "--"} 个，启用率 {gatewayHealth.enabledRate}%</CardContent>
        </Card>
        <Card>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><Wallet className="h-4 w-4" />余额预警</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.lowBalanceCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">支持自动余额探测与手动额度登记</CardContent>
        </Card>
        <Card className={gatewayHealth.issueCount > 0 ? "border-destructive/25 bg-destructive/5" : ""}>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><Activity className="h-4 w-4" />运行健康</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.unhealthyAccountCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">异常账户会优先展示，便于快速处理</CardContent>
        </Card>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-3">
          <div>
            <CardTitle>模型 API 中心</CardTitle>
            <CardDescription>
              {overview
                ? `${overview.summary.vendorCount} 个厂商 · ${overview.summary.modelCount} 个模型 · ${overview.summary.lowBalanceCount} 个低余额 · ${overview.summary.unhealthyAccountCount} 个账户异常`
                : "加载概览中…"}
            </CardDescription>
          </div>
          <div className="flex gap-2">
            <Button type="button" variant="outline" size="sm" disabled={loading} onClick={openCreateVendor}>
              <Plus className="mr-1 h-4 w-4" />
              添加厂商
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={refreshingBalance || loading} onClick={handleRefreshAllBalances}>
              <RefreshCw className={`mr-1 h-4 w-4 ${refreshingBalance ? "animate-spin" : ""}`} />
              刷新余额
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={loading} onClick={load}>
              重新加载
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3 rounded-xl border bg-muted/20 p-3 lg:flex-row lg:items-center lg:justify-between">
            <div className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={modelKeyword}
                onChange={(event) => setModelKeyword(event.target.value)}
                placeholder="搜索模型、configCode、能力或账户"
                className="pl-9"
              />
            </div>
            <div className="flex flex-wrap gap-2">
              <Select value={vendorFilter} onValueChange={(value) => setVendorFilter(value as VendorFilter)}>
                <SelectTrigger className="w-[132px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {vendorFilterOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Select value={vendorSort} onValueChange={(value) => setVendorSort(value as VendorSort)}>
                <SelectTrigger className="w-[132px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {vendorSortOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          {loading ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : overview && overview.vendors.length === 0 && overview.unconfiguredVendors.length === 0 ? (
            <p className="text-sm text-muted-foreground">暂无配置，请先接入厂商账户。</p>
          ) : filteredVendors.length === 0 ? (
            <p className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">没有匹配的模型渠道</p>
          ) : (
            <>
              {filteredVendors.map(renderVendorSection)}
              {unconfiguredVendors.length > 0 ? (
                <div className="rounded-xl border border-dashed p-4">
                  <p className="mb-3 font-medium">可接入厂商</p>
                  <div className="flex flex-wrap gap-2">
                    {unconfiguredVendors.map((vendor) => (
                      <Button
                        key={vendor.vendorCode}
                        type="button"
                        variant="outline"
                        size="sm"
                        className="gap-2"
                        onClick={() => openCreateAccount(vendor.vendorCode, vendor.label)}
                      >
                        <VendorIcon iconAsset={vendor.iconAsset} label={vendor.label} />
                        {vendor.label}
                      </Button>
                    ))}
                  </div>
                </div>
              ) : null}
            </>
          )}
        </CardContent>
      </Card>

      <Dialog open={vendorDialogOpen} onOpenChange={setVendorDialogOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>添加厂商/渠道</DialogTitle>
            <DialogDescription>厂商 code 用于分组和图标读取，图标资产名对应 vendor-icons 目录中的 SVG 文件名。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2">
              <Label>厂商 code</Label>
              <Input
                value={vendorForm.vendorCode}
                placeholder="例如 openai、volcengine"
                onChange={(event) => {
                  const vendorCode = event.target.value
                  setVendorForm((form) => ({
                    ...form,
                    vendorCode,
                    iconAsset: form.iconAsset === "api" || !form.iconAsset ? vendorCode.trim().toLowerCase() : form.iconAsset,
                  }))
                }}
              />
            </div>
            <div className="space-y-2">
              <Label>显示名称</Label>
              <Input
                value={vendorForm.vendorLabel}
                placeholder="例如 OpenAI、火山引擎 / 豆包"
                onChange={(event) => setVendorForm((form) => ({ ...form, vendorLabel: event.target.value }))}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label>图标资产名</Label>
                <Input
                  value={vendorForm.iconAsset}
                  placeholder="openai"
                  onChange={(event) => setVendorForm((form) => ({ ...form, iconAsset: event.target.value }))}
                />
              </div>
              <div className="space-y-2">
                <Label>排序</Label>
                <Input
                  type="number"
                  value={vendorForm.sortOrder ?? 0}
                  onChange={(event) => setVendorForm((form) => ({ ...form, sortOrder: numberOrZero(event.target.value) }))}
                />
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg border px-3 py-2">
              <Label>启用厂商</Label>
              <EmbeddedOnOffSwitch
                checked={vendorForm.enabled !== false}
                label="启用厂商"
                onCheckedChange={(enabled) => setVendorForm((form) => ({ ...form, enabled }))}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setVendorDialogOpen(false)}>取消</Button>
            <Button
              type="button"
              disabled={vendorSaving || !vendorForm.vendorCode.trim() || !vendorForm.vendorLabel.trim()}
              onClick={saveVendor}
            >
              {vendorSaving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={accountDialogOpen} onOpenChange={setAccountDialogOpen}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{accountForm.id ? "编辑厂商账户" : "接入厂商账户"}</DialogTitle>
            <DialogDescription>凭据只在厂商账户维护，下属模型通过绑定账号自动继承。API Key 与额外鉴权 JSON 二选一即可。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2">
              <Label>账户名称</Label>
              <Input value={accountForm.accountName} onChange={(e) => setAccountForm((f) => ({ ...f, accountName: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>Base URL</Label>
              <Input value={accountForm.baseUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, baseUrl: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>API Key {accountForm.apiKeyMasked ? `(已配置 ${accountForm.apiKeyMasked})` : ""}</Label>
              <Input
                type="password"
                value={accountForm.apiKey || ""}
                placeholder={accountForm.apiKeyMasked ? "留空则不修改" : "可选；Bearer Key / Token 类厂商填写"}
                onChange={(e) => setAccountForm((f) => ({ ...f, apiKey: e.target.value }))}
              />
              <p className="text-xs text-muted-foreground">可灵等 AK/SK 厂商可留空，改填下方额外鉴权 JSON。</p>
            </div>
            <div className="space-y-2">
              <Label>额外鉴权 JSON {accountForm.extraAuthJsonMasked ? `(已配置 ${accountForm.extraAuthJsonMasked})` : ""}</Label>
              <Textarea
                rows={3}
                value={accountForm.extraAuthJson || ""}
                placeholder={accountForm.extraAuthJsonMasked ? "留空则不修改" : '{"accessKey":"...","secretKey":"..."}'}
                onChange={(e) => setAccountForm((f) => ({ ...f, extraAuthJson: e.target.value }))}
              />
              <p className="text-xs text-muted-foreground">用于可灵 Access Key / Secret Key、代理、超时等账号级扩展配置。</p>
            </div>
            <div className="rounded-xl border bg-muted/30 p-3">
              <div className="flex items-start justify-between gap-4">
                <div className="space-y-1">
                  <Label>图片编辑批量补全</Label>
                  <p className="text-xs leading-relaxed text-muted-foreground">
                    写入 <code>topUpEditBatch=true</code>。当 openai_images 参考图编辑请求返回图片数少于生成数量时，worker 会追加单张 edit 请求补齐。
                  </p>
                </div>
                <EmbeddedOnOffSwitch
                  checked={accountForm.topUpEditBatch === true}
                  label="图片编辑批量补全"
                  onCheckedChange={(topUpEditBatch) => setAccountForm((f) => ({ ...f, topUpEditBatch }))}
                />
              </div>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label>余额查询模式</Label>
                <Select
                  value={accountForm.balanceQueryMode || "MANUAL"}
                  onValueChange={(v) => setAccountForm((f) => ({ ...f, balanceQueryMode: v }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="REST_API">API 自动查询（DeepSeek / SiliconFlow）</SelectItem>
                    <SelectItem value="MANUAL">手填余额</SelectItem>
                    <SelectItem value="NONE">仅外链</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>余额金额（手填）</Label>
                <Input
                  type="number"
                  value={accountForm.balanceAmount ?? ""}
                  onChange={(e) =>
                    setAccountForm((f) => ({
                      ...f,
                      balanceAmount: e.target.value === "" ? undefined : Number(e.target.value),
                    }))
                  }
                />
              </div>
            </div>
            <div className="space-y-2">
              <Label>低余额阈值</Label>
              <Input
                type="number"
                value={accountForm.balanceLowThreshold ?? ""}
                onChange={(e) =>
                  setAccountForm((f) => ({
                    ...f,
                    balanceLowThreshold: e.target.value === "" ? undefined : Number(e.target.value),
                  }))
                }
              />
            </div>
            <div className="space-y-2">
              <Label>控制台链接</Label>
              <Input value={accountForm.consoleUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, consoleUrl: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>余额页链接</Label>
              <Input value={accountForm.balanceUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, balanceUrl: e.target.value }))} />
            </div>
          </div>
          <DialogFooter className="gap-2 sm:justify-between">
            {accountForm.id ? (
              <Button
                type="button"
                variant="destructive"
                onClick={async () => {
                  if (!accountForm.id || !window.confirm("确认删除该账户？")) return
                  try {
                    await deleteModelVendorAccount(accountForm.id)
                    setAccountDialogOpen(false)
                    await load()
                  } catch (err) {
                    setError(err instanceof ApiError ? err.message : "删除失败")
                  }
                }}
              >
                删除账户
              </Button>
            ) : (
              <span />
            )}
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={() => setAccountDialogOpen(false)}>
                取消
              </Button>
              <Button type="button" disabled={accountSaving} onClick={saveAccount}>
                保存
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={modelDialogOpen} onOpenChange={setModelDialogOpen}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{modelForm.id ? "编辑模型" : "添加模型"}</DialogTitle>
            <DialogDescription>使用所属账户的 API 密钥，无需在此重复填写 Key。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2 rounded-md border p-3">
              <div className="flex items-center justify-between gap-3">
                <Label>绑定 API 账户</Label>
                <span className="text-xs text-muted-foreground">模型调用时继承该账户凭据</span>
              </div>
              <Select
                value={modelForm.vendorAccountId ? String(modelForm.vendorAccountId) : undefined}
                onValueChange={(value) => {
                  const accountId = Number(value)
                  setModelForm((form) => ({
                    ...form,
                    vendorAccountId: Number.isFinite(accountId) ? accountId : undefined,
                  }))
                }}
              >
                <SelectTrigger>
                  <SelectValue placeholder={modelAccountOptions.length > 0 ? "选择该模型使用的 API 账户" : "请先接入厂商账户"} />
                </SelectTrigger>
                <SelectContent>
                  {modelAccountOptions.map((account, index) => (
                    <SelectItem key={account.id} value={String(account.id)}>
                      #{account.id} {displayAccountName(account, index)}
                      {account.enabled ? "" : "（已停用）"}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {modelForm.vendorAccountId ? (() => {
                const account = accountById.get(modelForm.vendorAccountId)
                const modelOverrideBaseUrl = normalizeOptionalUrl(modelForm.baseUrl)
                const accountBaseUrl = normalizeOptionalUrl(account?.baseUrl)
                const inherited = !modelOverrideBaseUrl
                const sameAsAccount = !!modelOverrideBaseUrl && !!accountBaseUrl && sameBaseUrl(modelOverrideBaseUrl, accountBaseUrl)
                return (
                  <div className="space-y-2">
                    <p className={`text-xs ${account && hasAccountCredential(account) ? "text-muted-foreground" : "text-amber-700"}`}>
                      {account
                        ? `${account.vendorLabel || account.vendorCode} · ${accountCredentialLabel(account)} · ${account.healthStatus || "UNKNOWN"}`
                        : `账号 #${modelForm.vendorAccountId} 详情未加载，请重新选择一个可用账户`}
                    </p>
                    <div className="rounded-md border bg-muted/30 p-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant={inherited ? "outline" : "secondary"} className="text-xs">
                          {inherited ? "继承账号地址" : "模型覆盖地址"}
                        </Badge>
                        {!inherited && sameAsAccount ? (
                          <Badge variant="outline" className="text-xs text-amber-700">
                            覆盖值与账号当前地址一致
                          </Badge>
                        ) : null}
                        {!inherited ? (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="h-6 px-2 text-xs"
                            onClick={() => setModelForm((form) => ({ ...form, baseUrl: "" }))}
                          >
                            改回继承账号地址
                          </Button>
                        ) : null}
                      </div>
                      <p className="mt-2 text-xs text-muted-foreground">
                        生效地址：{inherited ? (accountBaseUrl || "未配置") : modelOverrideBaseUrl}
                      </p>
                      {!inherited ? (
                        <p className="mt-1 text-xs text-amber-700">
                          当前模型保存了独立 baseUrl；切换账号后它不会自动跟随账号地址变化。
                        </p>
                      ) : null}
                    </div>
                  </div>
                )
              })() : (
                <p className="text-xs text-amber-700">必须绑定一个厂商账户；API Key/AK/SK 只在账户里维护。</p>
              )}
            </div>
            <div className="space-y-2">
              <Label>显示名称</Label>
              <Input value={modelForm.displayName || ""} onChange={(e) => setModelForm((f) => ({ ...f, displayName: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>configCode</Label>
              <Input value={modelForm.configCode || ""} onChange={(e) => setModelForm((f) => ({ ...f, configCode: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>Upstream 模型名</Label>
              <Input value={modelForm.modelName} onChange={(e) => setModelForm((f) => ({ ...f, modelName: e.target.value }))} />
            </div>
            <div className="space-y-3 rounded-md border p-3">
              <div className="flex items-center justify-between gap-3">
                <Label>模型能力</Label>
                <span className="text-xs text-muted-foreground">
                  {currentModelProviderMeta?.label || modelForm.provider}
                </span>
              </div>
              <div className="grid gap-2 sm:grid-cols-2">
                {(currentModelProviderMeta?.capabilities?.length
                  ? currentModelProviderMeta.capabilities
                  : modelForm.capabilities || ["TEXT_GENERATION"]
                ).map((capability) => {
                  const checked = (modelForm.capabilities || []).some((item) => item.toUpperCase() === capability.toUpperCase())
                  return (
                    <button
                      key={capability}
                      type="button"
                      onClick={() => toggleModelCapability(capability)}
                      className={[
                        "flex items-center justify-between rounded-md border px-3 py-2 text-left text-sm transition",
                        checked
                          ? "border-blue-500 bg-blue-500/10 text-blue-100"
                          : "border-border bg-muted/20 text-muted-foreground hover:border-blue-400/60 hover:text-foreground",
                      ].join(" ")}
                    >
                      <span>{capabilityLabel(capability)}</span>
                      <span className="text-[11px] font-mono opacity-70">{capability}</span>
                    </button>
                  )
                })}
              </div>
              <p className="text-xs text-muted-foreground">
                能力决定工具页可绑定范围和 Worker 执行路由；音乐模型请选择“文生音乐”。
              </p>
            </div>
            <div className="space-y-3 rounded-md border p-3">
              <div className="flex items-center justify-between gap-3">
                <Label>执行任务类型</Label>
                <span className="text-xs text-muted-foreground">模型级路由，不填写 Key</span>
              </div>
              {routeTasksForModel(modelForm.provider, modelForm.capabilities).length > 0 ? (
                <Select
                  value={modelForm.executionTask || undefined}
                  onValueChange={(value) => setModelForm((f) => ({ ...f, executionTask: value }))}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择 API 任务类型" />
                  </SelectTrigger>
                  <SelectContent>
                    {routeTasksForModel(modelForm.provider, modelForm.capabilities).map((task) => (
                      <SelectItem key={task.value} value={task.value}>
                        {task.label} · {task.value}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <div className="rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground">
                  当前供应商暂未定义结构化路由任务，将使用 Worker 默认路由。
                </div>
              )}
              {(() => {
                const preview = routePreviewForForm(modelForm)
                return preview ? (
                  <div className="space-y-1 rounded-md bg-muted/30 p-3 text-xs text-muted-foreground">
                    <div>POST <span className="font-mono text-foreground">{preview.createPath}</span></div>
                    <div>GET <span className="font-mono text-foreground">{preview.resultPath}</span></div>
                  </div>
                ) : null
              })()}
              <details className="rounded-md border border-dashed p-3">
                <summary className="cursor-pointer text-sm text-muted-foreground">高级执行选项 JSON</summary>
                <Textarea
                  className="mt-3 min-h-24 font-mono text-xs"
                  value={modelForm.executionOptionsJson || ""}
                  placeholder={'{"createPath":"/v1/custom","resultPath":"/v1/custom/{task_id}"}'}
                  onChange={(e) => setModelForm((f) => ({ ...f, executionOptionsJson: e.target.value }))}
                />
                <p className="mt-2 text-xs text-muted-foreground">
                  仅用于 endpoint 覆盖或执行参数扩展，不要填写 API Key、AK/SK、Token。
                </p>
              </details>
            </div>
            <div className="space-y-2">
              <Label>API 文档页</Label>
              <div className="flex gap-2">
                <Input
                  value={modelForm.docsUrl || ""}
                  placeholder="https://docs.example.com/model-api"
                  onChange={(e) => setModelForm((f) => ({ ...f, docsUrl: e.target.value }))}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={!modelForm.docsUrl}
                  asChild={Boolean(modelForm.docsUrl)}
                >
                  {modelForm.docsUrl ? (
                    <a href={modelForm.docsUrl} target="_blank" rel="noreferrer">
                      <ExternalLink className="mr-2 h-4 w-4" />
                      打开
                    </a>
                  ) : (
                    <span>
                      <ExternalLink className="mr-2 h-4 w-4" />
                      打开
                    </span>
                  )}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                用于记录该模型的官方 API 文档，方便按文档调整参数表单、计费和限制。
              </p>
            </div>
            <div className="grid gap-3 rounded-md border p-3 md:grid-cols-[180px_minmax(0,1fr)]">
              <div className="space-y-2">
                <Label>计费规则</Label>
                <Select
                  value={modelForm.billingUnit || "TOKEN_PER_M"}
                  onValueChange={(v) => setModelForm((f) => ({ ...f, billingUnit: v as AgentModelConfigPayload["billingUnit"] }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {billingUnitOptions.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  {billingUnitOptions.find((option) => option.value === modelForm.billingUnit)?.description || "维护该模型的成本口径"}
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {modelForm.billingUnit === "PER_CALL" || modelForm.billingUnit === "PER_SECOND" ? (
                  <div className="space-y-2 sm:col-span-2">
                    <Label>{modelForm.billingUnit === "PER_SECOND" ? "每秒成本" : "单次调用成本"}</Label>
                    <Input
                      type="number"
                      min="0"
                      step="0.000001"
                      value={modelForm.unitPrice ?? 0}
                      onChange={(e) => setModelForm((f) => ({ ...f, unitPrice: numberOrZero(e.target.value) }))}
                    />
                  </div>
                ) : (
                  <>
                    <div className="space-y-2">
                      <Label>输入成本 / 百万 Token</Label>
                      <Input
                        type="number"
                        min="0"
                        step="0.000001"
                        value={modelForm.inputTokenPricePer1m ?? 0}
                        onChange={(e) => setModelForm((f) => ({ ...f, inputTokenPricePer1m: numberOrZero(e.target.value) }))}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>输出成本 / 百万 Token</Label>
                      <Input
                        type="number"
                        min="0"
                        step="0.000001"
                        value={modelForm.outputTokenPricePer1m ?? 0}
                        onChange={(e) => setModelForm((f) => ({ ...f, outputTokenPricePer1m: numberOrZero(e.target.value) }))}
                      />
                    </div>
                    {modelForm.billingUnit === "IMAGE_TOKEN" ? (
                      <div className="space-y-2 sm:col-span-2">
                        <Label>图片基础成本</Label>
                        <Input
                          type="number"
                          min="0"
                          step="0.000001"
                          value={modelForm.unitPrice ?? 0}
                          onChange={(e) => setModelForm((f) => ({ ...f, unitPrice: numberOrZero(e.target.value) }))}
                        />
                      </div>
                    ) : null}
                  </>
                )}
              </div>
            </div>
          </div>
          <DialogFooter className="gap-2 sm:justify-between">
            <Button
              type="button"
              variant="outline"
              disabled={!modelForm.id || modelSaving || testingModelId === modelForm.id}
              onClick={async () => {
                if (!modelForm.id) return
                await runModelTest(
                  {
                    id: modelForm.id,
                    vendorAccountId: modelForm.vendorAccountId,
                    displayName: modelForm.displayName,
                    configCode: modelForm.configCode,
                    provider: modelForm.provider,
                    modelName: modelForm.modelName,
                    capabilities: modelForm.capabilities,
                    enabled: modelForm.enabled !== false,
                    agentEnabled: modelForm.agentEnabled,
                    healthStatus: "OK",
                  },
                  modelVendorCode,
                )
              }}
            >
              {testingModelId === modelForm.id ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <Zap className="mr-2 h-4 w-4" />
              )}
              测试连接
            </Button>
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={() => setModelDialogOpen(false)}>
                取消
              </Button>
              <Button type="button" disabled={modelSaving} onClick={saveModel}>
                保存
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
