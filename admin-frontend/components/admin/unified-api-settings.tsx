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
import { upsertModelVendor } from "@/lib/api/model-vendors"
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

function balanceStatusBadge(account: ModelVendorAccount) {
  const status = account.balanceStatus
  if (status === "OK") {
    return <Badge className="bg-emerald-100 text-emerald-800 hover:bg-emerald-100">姝ｅ父</Badge>
  }
  if (status === "LOW") {
    return <Badge variant="destructive">浣庝綑棰?/Badge>
  }
  if (status === "SUSPECTED_INSUFFICIENT") {
    return <Badge variant="destructive">鐤戜技娆犺垂</Badge>
  }
  if (status === "ERROR") {
    return <Badge variant="destructive">鏌ヨ澶辫触</Badge>
  }
  if (account.balanceQueryMode === "NONE") {
    return <Badge variant="outline">浠呭閾?/Badge>
  }
  if (account.balanceQueryMode === "MANUAL" && account.balanceAmount == null) {
    return <Badge variant="outline">寰呮墜濉?/Badge>
  }
  return <Badge variant="outline">鏈煡</Badge>
}

function formatBalance(account: ModelVendorAccount) {
  if (account.balanceAmount != null) {
    const currency = account.balanceCurrency === "USD" ? "$" : "楼"
    return `浣欓 ${currency}${account.balanceAmount}`
  }
  if (account.balanceQueryMode === "NONE") {
    return "浣欓锛堟帶鍒跺彴鏌ョ湅锛?
  }
  if (account.balanceQueryMode === "MANUAL") {
    return "浣欓锛堟墜濉級"
  }
  return "浣欓 --"
}

function formatBalanceUpdatedAt(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  return `鏇存柊浜?${date.toLocaleString("zh-CN", { hour12: false })}`
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
  if (!model.enabled) return ""
  const health = modelAccountHealth(model, vendor)
  if (isHealthyStatus(health)) return "bg-emerald-50/70 hover:bg-emerald-50"
  if (health === "ERROR") return "bg-rose-50/75 hover:bg-rose-50"
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
    TEXT_GENERATION: "鏂囨鐢熸垚",
    IMAGE_GENERATION: "鏂囩敓鍥?,
    VIDEO_GENERATION: "鏂囩敓瑙嗛",
    TEXT_TO_SPEECH: "鏂囩敓闊抽",
    SPEECH_TO_TEXT: "璇煶杞枃瀛?,
    MUSIC_GENERATION: "鏂囩敓闊充箰",
    DIGITAL_HUMAN: "鏁板瓧浜鸿棰?,
    MULTIMODAL: "澶氭ā鎬佽緭鍏?,
  }
  return map[cap] || cap
}

function renderModelCost(model: UnifiedApiModelItem) {
  const billingUnit = (model.billingUnit || "").toString().trim().toUpperCase()
  if (!billingUnit) {
    return <span>鈥?/span>
  }
  if (billingUnit === "PER_CALL") {
    const price = model.unitPrice
    return (
      <>
        <div>鎸夋璁¤垂</div>
        <div className="font-medium text-foreground">{price != null ? `楼${price}` : "楼鈥?}/娆?/div>
      </>
    )
  }
  if (billingUnit === "TOKEN_PER_M") {
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div>鎸?Token 璁¤垂</div>
        <div className="font-medium text-foreground">
          杈撳叆 {input != null ? `楼${input}` : "楼鈥?}/鐧句竾 路 杈撳嚭 {output != null ? `楼${output}` : "楼鈥?}/鐧句竾
        </div>
      </>
    )
  }
  if (billingUnit === "IMAGE_TOKEN") {
    const input = model.inputTokenPricePer1m
    const output = model.outputTokenPricePer1m
    return (
      <>
        <div>鍥剧墖 Token</div>
        <div className="font-medium text-foreground">
          杈撳叆 {input != null ? `楼${input}` : "楼鈥?}/鐧句竾锛岃緭鍑?{output != null ? `楼${output}` : "楼鈥?}/鐧句竾
        </div>
      </>
    )
  }
  return (
    <>
      <div>{billingUnit}</div>
      <div className="font-medium text-foreground">鈥?/div>
    </>
  )
}

const emptyAccountForm = (): ModelVendorAccountPayload & { id?: number; apiKeyMasked?: string } => ({
  vendorCode: "deepseek",
  accountName: "榛樿璐︽埛",
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
  { value: "ALL", label: "鍏ㄩ儴娓犻亾" },
  { value: "ISSUES", label: "鍙湅寮傚父" },
  { value: "LOW_BALANCE", label: "浣庝綑棰? },
  { value: "UNHEALTHY", label: "杩為€氬紓甯? },
  { value: "DISABLED", label: "鍋滅敤璐︽埛" },
]

const vendorSortOptions: Array<{ value: VendorSort; label: string }> = [
  { value: "ISSUE_FIRST", label: "寮傚父浼樺厛" },
  { value: "MODEL_COUNT", label: "妯″瀷鏁颁紭鍏? },
  { value: "NAME", label: "鍚嶇О鎺掑簭" },
]

const billingUnitOptions: Array<{ value: NonNullable<AgentModelConfigPayload["billingUnit"]>; label: string; description: string }> = [
  { value: "TOKEN_PER_M", label: "鎸夐噺璁¤垂", description: "鎸夎緭鍏?杈撳嚭 Token 鐧句竾鍗曚綅濉啓鎴愭湰" },
  { value: "PER_CALL", label: "鎸夋璁¤垂", description: "姣忔璋冪敤鍥哄畾鎴愭湰锛岄€傚悎鍥剧墖銆佽闊崇瓑浠诲姟" },
  { value: "IMAGE_TOKEN", label: "鍥剧墖 Token", description: "鍚屾椂璁板綍鍥剧墖鍩虹浠峰拰 Token 鎴愭湰" },
]

function numberOrZero(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
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
          "缁熶竴 API 鎺ュ彛鏈壘鍒帮紝璇烽噸鏂扮紪璇戝苟閲嶅惎鍚庣锛堟湰鍦帮細鍦?backend 鐩綍鎵ц mvn spring-boot:run锛汥ocker锛歞ocker compose build backend && docker compose up -d backend锛夈€?
            + (err.traceId ? ` traceId=${err.traceId}` : ""),
        )
      } else {
        setError(err instanceof ApiError ? err.message : "鍔犺浇缁熶竴 API 姒傝澶辫触")
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
    if (!name || name === "榛樿璐︽埛") {
      return `璐︽埛${typeof index === "number" && index >= 0 ? index + 1 : account.id}`
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
      toast.success("鍘傚晢宸叉坊鍔?, { description: label })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "娣诲姞鍘傚晢澶辫触")
    } finally {
      setVendorSaving(false)
    }
  }

  const runRefreshBalance = useCallback(
    async (account: ModelVendorAccount, vendorLabel: string) => {
      setError(null)
      const toastId = toast.loading(`${vendorLabel}锛氭鍦ㄥ埛鏂颁綑棰濃€)
      try {
        const updated = await refreshModelVendorAccountBalance(account.id)
        patchVendorAccount(updated)
        if (updated.balanceAmount != null) {
          toast.success(`${vendorLabel}锛氫綑棰濆凡鏇存柊`, {
            id: toastId,
            description: formatBalance(updated),
          })
        } else if (updated.balanceQueryMode === "NONE") {
          toast.info(`${vendorLabel}锛氫笉鏀寔鑷姩鏌ヤ綑棰漙, {
            id: toastId,
            description: updated.balanceErrorMessage || "璇锋墦寮€鎺у埗鍙版垨鎵嬪～浣欓",
          })
        } else if (updated.balanceErrorMessage) {
          toast.warning(`${vendorLabel}锛氭湭鑳借幏鍙栦綑棰漙, {
            id: toastId,
            description: updated.balanceErrorMessage,
          })
        } else {
          toast.info(`${vendorLabel}锛氭殏鏃犱綑棰濇暟鎹甡, {
            id: toastId,
            description: "鍙湪璐︽埛璁剧疆涓墜濉綑棰濋噾棰?,
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "鍒锋柊澶辫触"
        toast.error(`${vendorLabel}锛氬埛鏂板け璐, { id: toastId, description: message })
        setError(message)
      }
    },
    [patchVendorAccount],
  )

  const runConnectivityTest = useCallback(
    async (account: ModelVendorAccount, vendorLabel: string) => {
      setTestingAccountId(account.id)
      setError(null)
      const toastId = toast.loading(`${vendorLabel}锛氭鍦ㄦ祴璇曡繛閫氭€р€)
      try {
        const result = await testModelVendorAccount(account.id)
        patchVendorAccount(result.account ?? account)
        const latencyText =
          result.latencyMs != null && result.latencyMs >= 0 ? `锛?{result.latencyMs} ms锛塦 : ""
        const modelHint =
          result.provider && result.modelName ? ` 路 ${result.provider} / ${result.modelName}` : ""
        if (result.success) {
          toast.success(`${vendorLabel}锛氳繛閫氭甯?{latencyText}`, {
            id: toastId,
            description: `${result.message || "杩炴帴鎴愬姛"}${modelHint}`,
          })
        } else {
          toast.error(`${vendorLabel}锛氳繛閫氬け璐, {
            id: toastId,
            description: result.message || "杩炴帴澶辫触",
          })
        }
      } catch (err) {
        const message = err instanceof ApiError ? err.message : "娴嬭瘯澶辫触"
        toast.error(`${vendorLabel}锛氭祴璇曞け璐, { id: toastId, description: message })
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
    const toastId = toast.loading(`${label}锛氭鍦ㄦ祴璇曡繛鎺モ€)
    try {
      const result = await testAgentModelConfigById(model.id)
      const latencyText =
        result.latencyMs != null && result.latencyMs >= 0 ? `锛?{result.latencyMs} ms锛塦 : ""
      const sampleText = result.sample?.trim() ? ` 路 鍝嶅簲锛?{result.sample.trim().slice(0, 80)}` : ""
      if (result.success) {
        await refreshOverviewSilently()
        toast.success(`${label}锛氳繛鎺ユ垚鍔?{latencyText}`, {
          id: toastId,
          description: `${result.message || "娴嬭瘯閫氳繃"}${sampleText}`,
        })
      } else {
        await refreshOverviewSilently()
        toast.error(`${label}锛氳繛鎺ュけ璐, {
          id: toastId,
          description: result.message || "娴嬭瘯鏈€氳繃",
        })
      }
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "娴嬭瘯澶辫触"
      toast.error(`${vendorLabel}锛?{label} 娴嬭瘯澶辫触`, { id: toastId, description: message })
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
        setError(err instanceof ApiError ? err.message : "鏇存柊澶辫触")
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
        setError(err instanceof ApiError ? err.message : "Agent 鍙€夋洿鏂板け璐?)
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
        setError(err instanceof ApiError ? err.message : "璐︽埛鍚敤鐘舵€佹洿鏂板け璐?)
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
      setError(err instanceof ApiError ? err.message : "鍒锋柊浣欓澶辫触")
    } finally {
      setRefreshingBalance(false)
    }
  }

  function openCreateAccount(vendorCode: string, label: string) {
    const meta = providers.find((p) => p.code.includes(vendorCode)) || providers[0]
    const existingCount = overview?.vendors.find((vendor) => vendor.vendorCode === vendorCode)?.accounts.length ?? 0
    setAccountForm({
      ...emptyAccountForm(),
      vendorCode,
      accountName: `璐︽埛${existingCount + 1}`,
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
      setError(err instanceof ApiError ? err.message : "淇濆瓨璐︽埛澶辫触")
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
      inputTokenPricePer1m: model.inputTokenPricePer1m ?? 0,
      outputTokenPricePer1m: model.outputTokenPricePer1m ?? 0,
      billingUnit: (model.billingUnit as AgentModelConfigPayload["billingUnit"]) || "TOKEN_PER_M",
      unitPrice: model.unitPrice ?? 0,
    })
    setModelDialogOpen(true)
  }

  async function saveModel() {
    if (!modelForm.vendorAccountId) {
      setError("璇烽€夋嫨鍘傚晢璐︽埛")
      return
    }
    setModelSaving(true)
    setError(null)
    try {
      const scrollY = typeof window === "undefined" ? 0 : window.scrollY
      const payload: AgentModelConfigPayload = {
        ...modelForm,
        apiKey: "",
        extraAuthJson: "",
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
      setError(err instanceof ApiError ? err.message : "淇濆瓨妯″瀷澶辫触")
    } finally {
      setModelSaving(false)
    }
  }

  function renderVendorAccountMenu(account: ModelVendorAccount, vendorLabel: string) {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label="鍘傚晢璐︽埛鎿嶄綔">
            <MoreHorizontal className="h-4 w-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-44">
          <DropdownMenuItem onClick={() => openEditAccount(account)}>
            <Settings2 className="mr-2 h-4 w-4" />
            API 涓庡瘑閽?          </DropdownMenuItem>
          {account.consoleUrl ? (
            <DropdownMenuItem asChild>
              <a href={account.consoleUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="mr-2 h-4 w-4" />
                鎵撳紑鎺у埗鍙?              </a>
            </DropdownMenuItem>
          ) : null}
          {account.balanceUrl ? (
            <DropdownMenuItem asChild>
              <a href={account.balanceUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="mr-2 h-4 w-4" />
                鎵撳紑浣欓椤?              </a>
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
    )
  }

  function renderVendorSection(vendor: UnifiedApiVendorGroup) {
    const primaryAccount = pickPrimaryAccount(vendor.accounts)
    const isOpen = openVendors[vendor.vendorCode] ?? false
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
                {lowBalanceCount > 0 ? <Badge variant="destructive" className="text-xs">浣庝綑棰?{lowBalanceCount}</Badge> : null}
                {unhealthyCount > 0 ? <Badge variant="destructive" className="text-xs">寮傚父 {unhealthyCount}</Badge> : null}
              </div>
              <p className="text-xs text-muted-foreground">{vendor.accounts.length} 涓处鎴?路 {vendor.models.length} 涓ā鍨?/p>
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
                    杩為€氭甯?                  </Badge>
                ) : primaryAccount.healthStatus === "ERROR" ? (
                  <Badge variant="destructive" className="text-xs">
                    杩為€氬紓甯?                  </Badge>
                ) : null}
                {formatBalanceUpdatedAt(primaryAccount.balanceUpdatedAt) ? (
                  <span className="text-xs text-muted-foreground">{formatBalanceUpdatedAt(primaryAccount.balanceUpdatedAt)}</span>
                ) : null}
                {primaryAccount.balanceErrorMessage ? (
                  <span className="max-w-[200px] truncate text-xs text-destructive" title={primaryAccount.balanceErrorMessage}>
                    {primaryAccount.balanceErrorMessage}
                  </span>
                ) : null}
                <EmbeddedOnOffSwitch
                  checked={primaryAccount.enabled}
                  disabled={togglingAccountId === primaryAccount.id}
                  label={`鍚敤璐︽埛 ${primaryAccount.accountName}`}
                  onCheckedChange={(enabled) => toggleAccountEnabled(primaryAccount, enabled)}
                />
                <Button type="button" variant="outline" size="icon" className="h-8 w-8" title="鍒锋柊璇ュ巶鍟嗕綑棰? onClick={() => runRefreshBalance(primaryAccount, vendor.label)}>
                  <RefreshCw className="h-4 w-4" />
                </Button>
                <Button type="button" variant="outline" size="icon" className="h-8 w-8" title={"娣诲姞 API 璐︽埛"} onClick={() => openCreateAccount(vendor.vendorCode, vendor.label)}>
                  <Plus className="h-4 w-4" />
                </Button>
              </div>
            ) : (
              <Button type="button" variant="outline" size="sm" onClick={() => openCreateAccount(vendor.vendorCode, vendor.label)}>
                <Plus className="mr-1 h-3 w-3" />
                鎺ュ叆 API
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
                          label={`鍚敤璐︽埛 ${displayAccountName(account, accountIndex)}`}
                          onCheckedChange={(enabled) => toggleAccountEnabled(account, enabled)}
                        />
                      </div>
                      <p className="truncate text-xs text-muted-foreground">{account.baseUrl || "鏈厤缃?Base URL"}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Button
                        type="button"
                        variant={testingAccountId === account.id ? "secondary" : "ghost"}
                        size="icon"
                        className="h-8 w-8"
                        disabled={testingAccountId === account.id}
                        title="娴嬭瘯杩炴帴"
                        onClick={() => runConnectivityTest(account, vendor.label)}
                      >
                        {testingAccountId === account.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap className="h-4 w-4" />}
                      </Button>
                      {renderVendorAccountMenu(account, vendor.label)}
                    </div>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    {balanceStatusBadge(account)}
                    <Badge variant={account.enabled ? "outline" : "destructive"}>{account.enabled ? "宸插惎鐢? : "宸插仠鐢?}</Badge>
                    <span className="text-xs text-muted-foreground">{account.modelCount} 涓ā鍨?/span>
                  </div>
                  <p className="mt-2 text-xs font-medium tabular-nums">{formatBalance(account)}</p>
                  {account.apiKeyMasked ? <p className="mt-1 text-xs text-muted-foreground">Key {account.apiKeyMasked}</p> : null}
                </div>
              ))}
            </div>
          ) : null}
          {vendor.accounts.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">璇峰厛鎺ュ叆 API 瀵嗛挜锛屽啀娣诲姞妯″瀷銆?/p>
          ) : vendor.models.length === 0 ? (
            <div className="space-y-3 py-2">
              <p className="text-center text-sm text-muted-foreground">鏆傛棤妯″瀷</p>
              <div className="flex justify-center">
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  disabled={!accountIdForNewModel}
                  onClick={() => accountIdForNewModel && openCreateModel(vendor, accountIdForNewModel)}
                >
                  <Plus className="mr-1 h-3 w-3" />
                  娣诲姞妯″瀷
                </Button>
              </div>
            </div>
          ) : (
            <>
              <Table className="table-fixed text-center">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[220px] text-center">妯″瀷鍚嶇О</TableHead>
                    <TableHead className="w-[150px] text-center">{"API \u8d26\u6237"}</TableHead>
                    <TableHead className="w-[180px] text-center">鑳藉姏</TableHead>
                    <TableHead className="w-[150px] text-center">鎴愭湰</TableHead>
                    <TableHead className="w-[112px] text-center">Agent 鍙€?/TableHead>
                    <TableHead className="w-[112px] text-center">鍚敤</TableHead>
                    <TableHead className="w-[180px] text-center">鎿嶄綔</TableHead>
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
                            鏈粦瀹氳处鎴?                          </Badge>
                        ) : null}
                      </TableCell>
                      <TableCell className="align-middle text-center">
                        {model.vendorAccountId ? (
                          <Badge variant="outline" className="max-w-[140px] truncate text-xs">
                            {displayAccountName(
                              vendor.accounts.find((account) => account.id === model.vendorAccountId) || {
                                id: model.vendorAccountId,
                                accountName: model.vendorAccountName || "",
                              } as ModelVendorAccount,
                              vendor.accounts.findIndex((account) => account.id === model.vendorAccountId),
                            )}
                          </Badge>
                        ) : vendor.accounts.length > 0 ? (
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
                      <TableCell className="align-middle">
                        <div className="space-y-1 text-center text-xs text-muted-foreground">
                          {renderModelCost(model)}
                        </div>
                      </TableCell>
                      <TableCell className="align-middle">
                        <EmbeddedOnOffSwitch
                          checked={model.agentEnabled !== false}
                          disabled={togglingAgentModelId === model.id || !canEnableAgentForModel(model, vendor)}
                          label={`Agent 鍙€?${model.displayName || model.modelName}`}
                          onCheckedChange={(agentEnabled) => toggleModelAgentEnabled(model, agentEnabled)}
                        />
                      </TableCell>
                      <TableCell className="align-middle">
                        <EmbeddedOnOffSwitch
                          checked={model.enabled}
                          disabled={togglingModelId === model.id}
                          label={`鍚敤 ${model.displayName || model.modelName}`}
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
                            title={!model.vendorAccountId ? "璇峰厛缁戝畾鍘傚晢璐︽埛" : "娴嬭瘯杩炴帴"}
                            onClick={() => runModelTest(model, vendor.label)}
                          >
                            {testingModelId === model.id ? (
                              <Loader2 className="h-4 w-4 animate-spin" />
                            ) : (
                              <Zap className="h-4 w-4" />
                            )}
                          </Button>
                          <Button type="button" variant="ghost" size="sm" className="h-8" onClick={() => openEditModel(model, vendor.vendorCode)}>
                            缂栬緫
                          </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8 text-destructive"
                          onClick={async () => {
                            if (!window.confirm("纭鍒犻櫎璇ユā鍨嬮厤缃紵")) return
                            try {
                              await deleteAgentModelConfig(model.id)
                              await refreshOverviewSilently()
                            } catch (err) {
                              setError(err instanceof ApiError ? err.message : "鍒犻櫎澶辫触")
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
                  娣诲姞妯″瀷
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
            <CardDescription className="flex items-center gap-2"><Layers className="h-4 w-4" />娓犻亾璐︽埛</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.accountCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">{overview?.summary.vendorCount ?? "--"} 涓巶鍟嗗凡鎺ュ叆</CardContent>
        </Card>
        <Card>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><ServerCog className="h-4 w-4" />妯″瀷姹?/CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.modelCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">鍚敤 {overview?.summary.enabledModelCount ?? "--"} 涓紝鍚敤鐜?{gatewayHealth.enabledRate}%</CardContent>
        </Card>
        <Card>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><Wallet className="h-4 w-4" />浣欓棰勮</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.lowBalanceCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">鏀寔鑷姩浣欓鎺㈡祴涓庢墜鍔ㄩ搴︾櫥璁?/CardContent>
        </Card>
        <Card className={gatewayHealth.issueCount > 0 ? "border-destructive/25 bg-destructive/5" : ""}>
          <CardHeader className="space-y-0 pb-2">
            <CardDescription className="flex items-center gap-2"><Activity className="h-4 w-4" />杩愯鍋ュ悍</CardDescription>
            <CardTitle className="text-2xl">{overview?.summary.unhealthyAccountCount ?? "--"}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">寮傚父璐︽埛浼氫紭鍏堝睍绀猴紝渚夸簬蹇€熷鐞?/CardContent>
        </Card>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>鎿嶄綔澶辫触</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-3">
          <div>
            <CardTitle>妯″瀷 API 涓績</CardTitle>
            <CardDescription>
              {overview
                ? `${overview.summary.vendorCount} 涓巶鍟?路 ${overview.summary.modelCount} 涓ā鍨?路 ${overview.summary.lowBalanceCount} 涓綆浣欓 路 ${overview.summary.unhealthyAccountCount} 涓处鎴峰紓甯竊
                : "鍔犺浇姒傝涓?.."}
            </CardDescription>
          </div>
          <div className="flex gap-2">
            <Button type="button" variant="outline" size="sm" disabled={loading} onClick={openCreateVendor}>
              <Plus className="mr-1 h-4 w-4" />
              娣诲姞鍘傚晢
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={refreshingBalance || loading} onClick={handleRefreshAllBalances}>
              <RefreshCw className={`mr-1 h-4 w-4 ${refreshingBalance ? "animate-spin" : ""}`} />
              鍒锋柊浣欓
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={loading} onClick={load}>
              閲嶆柊鍔犺浇
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
                placeholder="鎼滅储妯″瀷銆乧onfigCode銆佽兘鍔涙垨璐︽埛"
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
            <p className="text-sm text-muted-foreground">鍔犺浇涓?..</p>
          ) : overview && overview.vendors.length === 0 && overview.unconfiguredVendors.length === 0 ? (
            <p className="text-sm text-muted-foreground">鏆傛棤閰嶇疆锛岃鍏堟帴鍏ュ巶鍟嗚处鎴枫€?/p>
          ) : filteredVendors.length === 0 ? (
            <p className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">娌℃湁鍖归厤鐨勬ā鍨嬫笭閬?/p>
          ) : (
            <>
              {filteredVendors.map(renderVendorSection)}
              {unconfiguredVendors.length > 0 ? (
                <div className="rounded-xl border border-dashed p-4">
                  <p className="mb-3 font-medium">鍙帴鍏ュ巶鍟?/p>
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
            <DialogTitle>娣诲姞鍘傚晢/娓犻亾</DialogTitle>
            <DialogDescription>鍘傚晢 code 鐢ㄤ簬鍒嗙粍鍜屽浘鏍囪鍙栵紝鍥炬爣璧勪骇鍚嶅搴?vendor-icons 鐩綍涓殑 SVG 鏂囦欢鍚嶃€?/DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2">
              <Label>鍘傚晢 code</Label>
              <Input
                value={vendorForm.vendorCode}
                placeholder="渚嬪 openai銆乿olcengine"
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
              <Label>鏄剧ず鍚嶇О</Label>
              <Input
                value={vendorForm.vendorLabel}
                placeholder="渚嬪 OpenAI銆佺伀灞卞紩鎿?/ 璞嗗寘"
                onChange={(event) => setVendorForm((form) => ({ ...form, vendorLabel: event.target.value }))}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label>鍥炬爣璧勪骇鍚?/Label>
                <Input
                  value={vendorForm.iconAsset}
                  placeholder="openai"
                  onChange={(event) => setVendorForm((form) => ({ ...form, iconAsset: event.target.value }))}
                />
              </div>
              <div className="space-y-2">
                <Label>鎺掑簭</Label>
                <Input
                  type="number"
                  value={vendorForm.sortOrder ?? 0}
                  onChange={(event) => setVendorForm((form) => ({ ...form, sortOrder: numberOrZero(event.target.value) }))}
                />
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg border px-3 py-2">
              <Label>鍚敤鍘傚晢</Label>
              <EmbeddedOnOffSwitch
                checked={vendorForm.enabled !== false}
                label="鍚敤鍘傚晢"
                onCheckedChange={(enabled) => setVendorForm((form) => ({ ...form, enabled }))}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setVendorDialogOpen(false)}>鍙栨秷</Button>
            <Button
              type="button"
              disabled={vendorSaving || !vendorForm.vendorCode.trim() || !vendorForm.vendorLabel.trim()}
              onClick={saveVendor}
            >
              {vendorSaving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
              淇濆瓨
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={accountDialogOpen} onOpenChange={setAccountDialogOpen}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{accountForm.id ? "缂栬緫鍘傚晢璐︽埛" : "鎺ュ叆鍘傚晢璐︽埛"}</DialogTitle>
            <DialogDescription>API Key 涓?Base URL 鍦ㄦ缁存姢锛屼笅灞炴ā鍨嬪皢鑷姩缁ф壙銆?/DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2">
              <Label>璐︽埛鍚嶇О</Label>
              <Input value={accountForm.accountName} onChange={(e) => setAccountForm((f) => ({ ...f, accountName: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>Base URL</Label>
              <Input value={accountForm.baseUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, baseUrl: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>API Key {accountForm.apiKeyMasked ? `(宸查厤缃?${accountForm.apiKeyMasked})` : ""}</Label>
              <Input
                type="password"
                value={accountForm.apiKey || ""}
                placeholder={accountForm.apiKeyMasked ? "鐣欑┖鍒欎笉淇敼" : "蹇呭～"}
                onChange={(e) => setAccountForm((f) => ({ ...f, apiKey: e.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label>棰濆閴存潈 JSON锛堝彲鐏?AK/SK 绛夛級</Label>
              <Textarea
                rows={3}
                value={accountForm.extraAuthJson || ""}
                placeholder="鐣欑┖鍒欎笉淇敼"
                onChange={(e) => setAccountForm((f) => ({ ...f, extraAuthJson: e.target.value }))}
              />
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label>浣欓鏌ヨ妯″紡</Label>
                <Select
                  value={accountForm.balanceQueryMode || "MANUAL"}
                  onValueChange={(v) => setAccountForm((f) => ({ ...f, balanceQueryMode: v }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="REST_API">API 鑷姩鏌ヨ锛圖eepSeek / SiliconFlow锛?/SelectItem>
                    <SelectItem value="MANUAL">鎵嬪～浣欓</SelectItem>
                    <SelectItem value="NONE">浠呭閾?/SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>浣欓閲戦锛堟墜濉級</Label>
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
              <Label>浣庝綑棰濋槇鍊?/Label>
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
              <Label>鎺у埗鍙伴摼鎺?/Label>
              <Input value={accountForm.consoleUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, consoleUrl: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>浣欓椤甸摼鎺?/Label>
              <Input value={accountForm.balanceUrl || ""} onChange={(e) => setAccountForm((f) => ({ ...f, balanceUrl: e.target.value }))} />
            </div>
          </div>
          <DialogFooter className="gap-2 sm:justify-between">
            {accountForm.id ? (
              <Button
                type="button"
                variant="destructive"
                onClick={async () => {
                  if (!accountForm.id || !window.confirm("纭鍒犻櫎璇ヨ处鎴凤紵")) return
                  try {
                    await deleteModelVendorAccount(accountForm.id)
                    setAccountDialogOpen(false)
                    await load()
                  } catch (err) {
                    setError(err instanceof ApiError ? err.message : "鍒犻櫎澶辫触")
                  }
                }}
              >
                鍒犻櫎璐︽埛
              </Button>
            ) : (
              <span />
            )}
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={() => setAccountDialogOpen(false)}>
                鍙栨秷
              </Button>
              <Button type="button" disabled={accountSaving} onClick={saveAccount}>
                淇濆瓨
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={modelDialogOpen} onOpenChange={setModelDialogOpen}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{modelForm.id ? "缂栬緫妯″瀷" : "娣诲姞妯″瀷"}</DialogTitle>
            <DialogDescription>浣跨敤鎵€灞炶处鎴风殑 API 瀵嗛挜锛屾棤闇€鍦ㄦ閲嶅濉啓 Key銆?/DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="space-y-2">
              <Label>鏄剧ず鍚嶇О</Label>
              <Input value={modelForm.displayName || ""} onChange={(e) => setModelForm((f) => ({ ...f, displayName: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>configCode</Label>
              <Input value={modelForm.configCode || ""} onChange={(e) => setModelForm((f) => ({ ...f, configCode: e.target.value }))} />
            </div>
            <div className="space-y-2">
              <Label>Upstream 妯″瀷鍚?/Label>
              <Input value={modelForm.modelName} onChange={(e) => setModelForm((f) => ({ ...f, modelName: e.target.value }))} />
            </div>
            <div className="grid gap-3 rounded-md border p-3 md:grid-cols-[180px_minmax(0,1fr)]">
              <div className="space-y-2">
                <Label>璁¤垂瑙勫垯</Label>
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
                  {billingUnitOptions.find((option) => option.value === modelForm.billingUnit)?.description || "缁存姢璇ユā鍨嬬殑鎴愭湰鍙ｅ緞"}
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {modelForm.billingUnit === "PER_CALL" ? (
                  <div className="space-y-2 sm:col-span-2">
                    <Label>鍗曟璋冪敤鎴愭湰</Label>
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
                      <Label>杈撳叆鎴愭湰 / 鐧句竾 Token</Label>
                      <Input
                        type="number"
                        min="0"
                        step="0.000001"
                        value={modelForm.inputTokenPricePer1m ?? 0}
                        onChange={(e) => setModelForm((f) => ({ ...f, inputTokenPricePer1m: numberOrZero(e.target.value) }))}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>杈撳嚭鎴愭湰 / 鐧句竾 Token</Label>
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
                        <Label>鍥剧墖鍩虹鎴愭湰</Label>
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
              娴嬭瘯杩炴帴
            </Button>
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={() => setModelDialogOpen(false)}>
                鍙栨秷
              </Button>
              <Button type="button" disabled={modelSaving} onClick={saveModel}>
                淇濆瓨
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

