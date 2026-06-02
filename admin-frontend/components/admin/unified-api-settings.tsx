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
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import {
  createAgentModelConfig,
  deleteAgentModelConfig,
  setDefaultAgentModelConfig,
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
import { fetchUnifiedApiOverview } from "@/lib/api/unified-api"
import type {
  AgentModelConfigPayload,
  ModelProviderDescriptor,
  ModelVendorAccount,
  ModelVendorAccountPayload,
  UnifiedApiModelItem,
  UnifiedApiOverview,
  UnifiedApiVendorGroup,
} from "@/lib/api/types"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  AlertCircle,
  Activity,
  CheckCircle2,
  ChevronDown,
  ExternalLink,
  Layers,
  Loader2,
  MoreHorizontal,
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

function VendorIcon({ iconAsset, label }: { iconAsset: string; label: string }) {
  return (
    <img
      src={`/assets/vendor-icons/${iconAsset}.svg`}
      alt={label}
      className="h-8 w-8 rounded-md border bg-white object-contain p-1"
      onError={(event) => {
        event.currentTarget.style.display = "none"
      }}
    />
  )
}

function balanceStatusBadge(account: ModelVendorAccount) {
  const status = account.balanceStatus
  if (status === "OK") {
    return <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">正常</Badge>
  }
  if (status === "LOW") {
    return <Badge variant="destructive">低余额</Badge>
  }
  if (status === "SUSPECTED_INSUFFICIENT") {
    return <Badge variant="destructive">疑似欠费</Badge>
  }
  if (status === "ERROR") {
    return <Badge variant="destructive">查询失败</Badge>
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
    const keyDiff = (b.apiKeyMasked ? 1 : 0) - (a.apiKeyMasked ? 1 : 0)
    if (keyDiff !== 0) return keyDiff
    return a.id - b.id
  })[0]
}

function defaultBalanceModeForVendor(vendorCode: string) {
  switch (vendorCode) {
    case "deepseek":
    case "siliconflow":
    case "minimax":
    case "openai":
    case "openai_gateway":
      return "REST_API"
    case "volcengine":
    case "kling":
      return "NONE"
    default:
      return "MANUAL"
  }
}

function capabilityLabel(cap: string) {
  const map: Record<string, string> = {
    TEXT_GENERATION: "文本",
    IMAGE_GENERATION: "图片",
    VIDEO_GENERATION: "视频",
    TEXT_TO_SPEECH: "语音",
    SPEECH_TO_TEXT: "语音识别",
    MUSIC_GENERATION: "音乐",
    DIGITAL_HUMAN: "数字人",
    MULTIMODAL: "多模态",
  }
  return map[cap] || cap
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
  if (billingUnit === "TOKEN_PER_M") {
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div>按 Token 计费</div>
        <div className="font-medium text-foreground">
          输入 {input != null ? `¥${input}` : "¥—"}/百万 · 输出 {output != null ? `¥${output}` : "¥—"}/百万
        </div>
      </>
    )
  }
  if (billingUnit === "IMAGE_TOKEN") {
    const unit = model.unitPrice
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div>图片 Token</div>
        <div className="font-medium text-foreground">
          {unit != null ? `¥${unit}` : "¥—"} · 输入 {input ?? "—"}/百万 · 输出 {output ?? "—"}/百万
        </div>
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

const emptyAccountForm = (): ModelVendorAccountPayload & { id?: number; apiKeyMasked?: string } => ({
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

  const [openVendors, setOpenVendors] = useState<Record<string, boolean>>({})
  const [togglingModelId, setTogglingModelId] = useState<number | null>(null)
  const [testingModelId, setTestingModelId] = useState<number | null>(null)
  const [testingAccountId, setTestingAccountId] = useState<number | null>(null)
  const [vendorFilter, setVendorFilter] = useState<VendorFilter>("ALL")
  const [vendorSort, setVendorSort] = useState<VendorSort>("ISSUE_FIRST")
  const [modelKeyword, setModelKeyword] = useState("")

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
        const hasIssue = vendor.accounts.some(
          (a) => a.balanceStatus === "LOW" || a.healthStatus === "ERROR",
        )
        initialOpen[vendor.vendorCode] = hasIssue || vendor.models.length > 0
      })
      setOpenVendors((prev) => ({ ...initialOpen, ...prev }))
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
            description: updated.balanceErrorMessage,
          })
        } else {
          toast.info(`${vendorLabel}：暂无余额数据`, {
            id: toastId,
            description: "可在账户设置中手填余额金额",
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "刷新失败"
        toast.error(`${vendorLabel}：刷新失败`, { id: toastId, description: message })
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
            description: result.message || "连接失败",
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "测试失败"
        toast.error(`${vendorLabel}：测试失败`, { id: toastId, description: message })
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
        toast.success(`${label}：连接成功${latencyText}`, {
          id: toastId,
          description: `${result.message || "测试通过"}${sampleText}`,
        })
      } else {
        toast.error(`${label}：连接失败`, {
          id: toastId,
          description: result.message || "测试未通过",
        })
      }
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "测试失败"
      toast.error(`${vendorLabel}：${label} 测试失败`, { id: toastId, description: message })
      setError(message)
    } finally {
      setTestingModelId(null)
    }
  }, [])

  const toggleModelEnabled = useCallback(
    async (model: UnifiedApiModelItem, enabled: boolean) => {
      const previous = model.enabled
      patchModelEnabled(model.id, enabled)
      setTogglingModelId(model.id)
      setError(null)
      try {
        await updateAgentModelConfig(model.id, {
          vendorAccountId: model.vendorAccountId ?? undefined,
          displayName: model.displayName || "",
          configCode: model.configCode || "",
          provider: model.provider,
          modelName: model.modelName,
          enabled,
          agentEnabled: model.agentEnabled ?? true,
          isDefault: model.isDefault ?? false,
          capabilities: model.capabilities ? [...model.capabilities] : [],
          timeoutSeconds: 60,
          inputTokenPricePer1m: 0,
          outputTokenPricePer1m: 0,
          billingUnit: "TOKEN_PER_M",
          unitPrice: 0,
        })
      } catch (err) {
        patchModelEnabled(model.id, previous)
        setError(err instanceof ApiError ? err.message : "更新失败")
      } finally {
        setTogglingModelId(null)
      }
    },
    [patchModelEnabled],
  )

  useEffect(() => {
    load()
  }, [load, refreshKey])

  const providerOptions = useMemo(() => {
    if (!modelVendorCode) return providers
    return providers
  }, [providers, modelVendorCode])

  const filteredVendors = useMemo(() => {
    if (!overview) return []
    const keyword = modelKeyword.trim().toLowerCase()
    const issueScore = (vendor: UnifiedApiVendorGroup) => {
      const lowBalance = vendor.accounts.filter((a) => a.balanceStatus === "LOW" || a.balanceStatus === "SUSPECTED_INSUFFICIENT").length
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
          return vendor.accounts.some((a) => a.balanceStatus === "LOW" || a.balanceStatus === "SUSPECTED_INSUFFICIENT")
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
    const meta = providers.find((p) => p.code.includes(vendorCode)) || providers[0]
    setAccountForm({
      ...emptyAccountForm(),
      vendorCode,
      accountName: `${label} 账户`,
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
      apiKeyMasked: account.apiKeyMasked || "",
      extraAuthJson: "",
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
      const payload: ModelVendorAccountPayload = {
        vendorCode: accountForm.vendorCode,
        accountName: accountForm.accountName,
        baseUrl: accountForm.baseUrl,
        apiKey: accountForm.apiKey,
        clearApiKey: accountForm.clearApiKey,
        extraAuthJson: accountForm.extraAuthJson,
        clearExtraAuthJson: accountForm.clearExtraAuthJson,
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
      setError(err instanceof ApiError ? err.message : "保存账户失败")
    } finally {
      setAccountSaving(false)
    }
  }

  function openCreateModel(vendor: UnifiedApiVendorGroup, accountId: number) {
    const account = vendor.accounts.find((a) => a.id === accountId) || vendor.accounts[0]
    const defaultProvider = vendor.models[0]?.provider || providers[0]?.code || "openai_compatible"
    const meta = providers.find((p) => p.code === defaultProvider) || providers[0]
    setModelVendorCode(vendor.vendorCode)
    setModelForm({
      ...emptyModelForm(),
      vendorAccountId: account?.id,
      provider: defaultProvider,
      modelName: meta?.defaultModel || "",
      baseUrl: "",
      capabilities: meta ? [...meta.capabilities] : ["TEXT_GENERATION"],
      billingUnit: (meta?.billingDefault as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
    })
    setModelDialogOpen(true)
  }

  function openEditModel(model: UnifiedApiModelItem, vendorCode: string) {
    setModelVendorCode(vendorCode)
    setModelForm({
      id: model.id,
      vendorAccountId: model.vendorAccountId ?? undefined,
      displayName: model.displayName || "",
      configCode: model.configCode || "",
      provider: model.provider,
      modelName: model.modelName,
      enabled: model.enabled,
      agentEnabled: model.agentEnabled ?? true,
      isDefault: model.isDefault ?? false,
      capabilities: model.capabilities ? [...model.capabilities] : [],
      timeoutSeconds: 60,
      inputTokenPricePer1m: 0,
      outputTokenPricePer1m: 0,
      billingUnit: "TOKEN_PER_M",
      unitPrice: 0,
    })
    setModelDialogOpen(true)
  }

  async function saveModel() {
    if (!modelForm.vendorAccountId) {
      setError("请选择厂商账户")
      return
    }
    setModelSaving(true)
    setError(null)
    try {
      const payload: AgentModelConfigPayload = {
        ...modelForm,
        apiKey: "",
        extraAuthJson: "",
      }
      if (modelForm.id) {
        await updateAgentModelConfig(modelForm.id, payload)
      } else {
        await createAgentModelConfig(payload)
      }
      setModelDialogOpen(false)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存模型失败")
    } finally {
      setModelSaving(false)
    }
  }

  function renderVendorAccountMenu(account: ModelVendorAccount, vendorLabel: string) {
    const testing = testingAccountId === account.id
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label="厂商账户操作">
            <MoreHorizontal className="h-4 w-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-44">
          <DropdownMenuItem onClick={() => openEditAccount(account)}>
            <Settings2 className="mr-2 h-4 w-4" />
            API 与密钥
          </DropdownMenuItem>
          <DropdownMenuItem disabled={testing} onClick={() => runConnectivityTest(account, vendorLabel)}>
            {testing ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Zap className="mr-2 h-4 w-4" />}
            {testing ? "测试中…" : "测试连通"}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => runRefreshBalance(account, vendorLabel)}
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新余额
          </DropdownMenuItem>
          {account.consoleUrl ? (
            <DropdownMenuItem asChild>
              <a href={account.consoleUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="mr-2 h-4 w-4" />
                打开控制台
              </a>
            </DropdownMenuItem>
          ) : null}
          {account.balanceUrl ? (
            <DropdownMenuItem asChild>
              <a href={account.balanceUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="mr-2 h-4 w-4" />
                打开余额页
              </a>
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
    )
  }

  function renderVendorSection(vendor: UnifiedApiVendorGroup) {
    const primaryAccount = pickPrimaryAccount(vendor.accounts)
    const isOpen = openVendors[vendor.vendorCode] ?? true
    const accountIdForNewModel = primaryAccount?.id
    const lowBalanceCount = vendor.accounts.filter((account) => account.balanceStatus === "LOW" || account.balanceStatus === "SUSPECTED_INSUFFICIENT").length
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
                {primaryAccount.balanceErrorMessage ? (
                  <span className="max-w-[200px] truncate text-xs text-destructive" title={primaryAccount.balanceErrorMessage}>
                    {primaryAccount.balanceErrorMessage}
                  </span>
                ) : null}
                {renderVendorAccountMenu(primaryAccount, vendor.label)}
              </div>
            ) : (
              <Button type="button" variant="outline" size="sm" onClick={() => openCreateAccount(vendor.vendorCode, vendor.label)}>
                <Plus className="mr-1 h-3 w-3" />
                接入 API
              </Button>
            )}
          </div>
        </div>
        <CollapsibleContent className="px-4 py-3">
          {vendor.accounts.length > 0 ? (
            <div className="mb-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {vendor.accounts.map((account) => (
                <div key={account.id} className="rounded-lg border bg-muted/20 p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">{account.accountName}</p>
                      <p className="truncate text-xs text-muted-foreground">{account.baseUrl || "未配置 Base URL"}</p>
                    </div>
                    {renderVendorAccountMenu(account, vendor.label)}
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    {balanceStatusBadge(account)}
                    <Badge variant={account.enabled ? "outline" : "destructive"}>{account.enabled ? "已启用" : "已停用"}</Badge>
                    <span className="text-xs text-muted-foreground">{account.modelCount} 个模型</span>
                  </div>
                  <p className="mt-2 text-xs font-medium tabular-nums">{formatBalance(account)}</p>
                  {account.apiKeyMasked ? <p className="mt-1 text-xs text-muted-foreground">Key {account.apiKeyMasked}</p> : null}
                </div>
              ))}
            </div>
          ) : null}
          {vendor.accounts.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">请先接入 API 密钥，再添加模型。</p>
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
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>模型名称</TableHead>
                    <TableHead>能力</TableHead>
                    <TableHead>成本</TableHead>
                    <TableHead className="w-[72px]">启用</TableHead>
                    <TableHead className="w-[200px] text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {vendor.models.map((model) => (
                    <TableRow key={model.id}>
                      <TableCell>
                        <div className="font-medium">
                          {model.displayName || model.modelName}
                          {model.isDefault ? <Star className="ml-1 inline h-3 w-3 text-amber-500" /> : null}
                        </div>
                        <p className="font-mono text-xs text-muted-foreground">{model.modelName}</p>
                        {!model.vendorAccountId ? (
                          <Badge variant="outline" className="mt-1 text-xs text-amber-700">
                            未绑定账户
                          </Badge>
                        ) : null}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {(model.capabilities || []).map((cap) => (
                            <Badge key={cap} variant="secondary" className="text-xs">
                              {capabilityLabel(cap)}
                            </Badge>
                          ))}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="space-y-1 text-xs text-muted-foreground">
                          {renderModelCost(model)}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Switch
                          checked={model.enabled}
                          disabled={togglingModelId === model.id}
                          onCheckedChange={(enabled) => toggleModelEnabled(model, enabled)}
                        />
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="inline-flex items-center justify-end gap-1">
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
                        {!model.isDefault ? (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="h-8"
                            onClick={async () => {
                              try {
                                await setDefaultAgentModelConfig(model.id)
                                await refreshOverviewSilently()
                              } catch (err) {
                                setError(err instanceof ApiError ? err.message : "设置默认失败")
                              }
                            }}
                          >
                            默认
                          </Button>
                        ) : null}
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8"
                          className="text-destructive"
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
                : "加载概览中..."}
            </CardDescription>
          </div>
          <div className="flex gap-2">
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
            <p className="text-sm text-muted-foreground">加载中...</p>
          ) : overview && overview.vendors.length === 0 && overview.unconfiguredVendors.length === 0 ? (
            <p className="text-sm text-muted-foreground">暂无配置，请先接入厂商账户。</p>
          ) : filteredVendors.length === 0 ? (
            <p className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">没有匹配的模型渠道</p>
          ) : (
            <>
              {filteredVendors.map(renderVendorSection)}
              {overview && overview.unconfiguredVendors.length > 0 ? (
                <div className="rounded-xl border border-dashed p-4">
                  <p className="mb-3 font-medium">可接入厂商</p>
                  <div className="flex flex-wrap gap-2">
                    {overview.unconfiguredVendors.map((vendor) => (
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

      <Dialog open={accountDialogOpen} onOpenChange={setAccountDialogOpen}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{accountForm.id ? "编辑厂商账户" : "接入厂商账户"}</DialogTitle>
            <DialogDescription>API Key 与 Base URL 在此维护，下属模型将自动继承。</DialogDescription>
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
                placeholder={accountForm.apiKeyMasked ? "留空则不修改" : "必填"}
                onChange={(e) => setAccountForm((f) => ({ ...f, apiKey: e.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label>额外鉴权 JSON（可灵 AK/SK 等）</Label>
              <Textarea
                rows={3}
                placeholder="留空则不修改"
                onChange={(e) => setAccountForm((f) => ({ ...f, extraAuthJson: e.target.value }))}
              />
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
            <div className="flex items-center gap-2">
              <Switch checked={accountForm.enabled !== false} onCheckedChange={(v) => setAccountForm((f) => ({ ...f, enabled: v }))} />
              <Label>启用账户</Label>
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
            <div className="space-y-2">
              <Label>显示名称</Label>
              <Input value={modelForm.displayName || ""} onChange={(e) => setModelForm((f) => ({ ...f, displayName: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>configCode</Label>
              <Input value={modelForm.configCode || ""} onChange={(e) => setModelForm((f) => ({ ...f, configCode: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>协议 Provider</Label>
              <Select value={modelForm.provider} onValueChange={(v) => {
                const meta = providers.find((p) => p.code === v)
                setModelForm((f) => ({
                  ...f,
                  provider: v,
                  modelName: meta?.defaultModel || f.modelName,
                  capabilities: meta ? [...meta.capabilities] : f.capabilities,
                  billingUnit: (meta?.billingDefault as AgentModelConfigPayload["billingUnit"]) || f.billingUnit,
                }))
              }}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {providerOptions.map((p) => (
                    <SelectItem key={p.code} value={p.code}>
                      {p.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Upstream 模型名</Label>
              <Input value={modelForm.modelName} onChange={(e) => setModelForm((f) => ({ ...f, modelName: e.target.value }))} />
            </div>
            <div className="flex flex-wrap gap-4">
              <div className="flex items-center gap-2">
                <Switch checked={modelForm.enabled !== false} onCheckedChange={(v) => setModelForm((f) => ({ ...f, enabled: v }))} />
                <Label>启用</Label>
              </div>
              <div className="flex items-center gap-2">
                <Switch checked={modelForm.agentEnabled !== false} onCheckedChange={(v) => setModelForm((f) => ({ ...f, agentEnabled: v }))} />
                <Label>Agent 可选</Label>
              </div>
              <div className="flex items-center gap-2">
                <Switch checked={Boolean(modelForm.isDefault)} onCheckedChange={(v) => setModelForm((f) => ({ ...f, isDefault: v }))} />
                <Label>默认模型</Label>
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
