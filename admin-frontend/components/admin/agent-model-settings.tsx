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
import type { AgentModelConfig, AgentModelConfigPayload, AgentModelConfigTestResult, AgentModelProvider } from "@/lib/api/types"
import { AlertCircle, CheckCircle2, KeyRound, Plus, RefreshCw, Save, ServerCog, Star, Trash2, Zap } from "lucide-react"

interface ModelForm {
  id: number | null
  displayName: string
  configCode: string
  provider: AgentModelProvider
  modelName: string
  baseUrl: string
  apiKey: string
  apiKeyMasked: string
  timeoutSeconds: string
  enabled: boolean
  isDefault: boolean
}

interface VendorMeta {
  label: string
  shortName: string
  mark: string
  /** 对应 `public/assets/vendor-icons/{iconAsset}.svg` 白底素材 */
  iconAsset: string
}

type ModelConfigWithTest = AgentModelConfig & {
  lastTestAt?: string | null
  lastTestSuccess?: boolean | null
}

const providerOptions: Array<{
  value: AgentModelProvider
  label: string
  packageName: string
  defaultModel: string
  defaultBaseUrl: string
  description: string
}> = [
  {
    value: "openai_compatible",
    label: "OpenAI 兼容协议",
    packageName: "langchain-openai",
    defaultModel: "gpt-4o-mini",
    defaultBaseUrl: "https://api.openai.com/v1",
    description: "使用 OpenAI 兼容的 Chat Completions 接口。",
  },
  {
    value: "anthropic_compatible",
    label: "Anthropic 兼容协议",
    packageName: "langchain-anthropic",
    defaultModel: "claude-3-5-sonnet-latest",
    defaultBaseUrl: "https://api.anthropic.com",
    description: "使用 Anthropic 兼容的 Messages 接口。",
  },
]

const vendorFallback: VendorMeta = {
  label: "模型 API",
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
  timeoutSeconds: "60",
  enabled: true,
  isDefault: false,
}

function providerMeta(provider: string) {
  return providerOptions.find((item) => item.value === provider) || providerOptions[0]
}

function toForm(config: AgentModelConfig): ModelForm {
  const provider = config.provider === "anthropic_compatible" ? "anthropic_compatible" : "openai_compatible"
  const meta = providerMeta(provider)
  return {
    id: config.id,
    displayName: config.displayName || config.modelName || "",
    configCode: config.configCode || "",
    provider,
    modelName: config.modelName || meta.defaultModel,
    baseUrl: config.baseUrl || meta.defaultBaseUrl,
    apiKey: "",
    apiKeyMasked: config.apiKeyMasked || "",
    timeoutSeconds: String(config.timeoutSeconds || 60),
    enabled: config.enabled !== false,
    isDefault: Boolean(config.isDefault),
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
    timeoutSeconds: Number(form.timeoutSeconds) || 60,
    enabled: form.enabled,
    isDefault: form.isDefault,
  }
}

function normalizeConfigCode(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "")
}

function uniqueConfigCode(baseValue: string, configs: AgentModelConfig[]) {
  const base = normalizeConfigCode(baseValue) || "model_config"
  const used = new Set(configs.map((item) => normalizeConfigCode(item.configCode || "")).filter(Boolean))
  if (!used.has(base)) return base
  for (let index = 2; index < 1000; index += 1) {
    const candidate = `${base}_${index}`
    if (!used.has(candidate)) return candidate
  }
  return `${base}_${Date.now()}`
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

export function AgentModelSettings() {
  const [configs, setConfigs] = useState<ModelConfigWithTest[]>([])
  const [form, setForm] = useState<ModelForm>(emptyForm)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [testResult, setTestResult] = useState<AgentModelConfigTestResult | null>(null)

  const meta = useMemo(() => providerMeta(form.provider), [form.provider])
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

  async function loadConfigs(nextSelectedId?: number | null) {
    setLoading(true)
    setError(null)
    try {
      const list = await fetchAgentModelConfigs()
      setConfigs(list)
      const selected =
        list.find((item) => item.id === nextSelectedId) ||
        list.find((item) => item.id === selectedId) ||
        list.find((item) => item.isDefault) ||
        list[0]
      setForm(selected ? toForm(selected) : emptyForm)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载模型配置失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConfigs()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function updateForm<K extends keyof ModelForm>(key: K, value: ModelForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
    setSaved(false)
    setTestResult(null)
  }

  function editConfig(config: AgentModelConfig) {
    setForm(toForm(config))
    setSaved(false)
    setTestResult(null)
    setError(null)
    setDialogOpen(true)
  }

  function createConfig() {
    setForm({
      ...emptyForm,
      configCode: uniqueConfigCode(emptyForm.modelName, configs),
      displayName: "",
      isDefault: configs.length === 0,
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

  function applyProvider(value: AgentModelProvider) {
    const next = providerMeta(value)
    setForm((current) => ({
      ...current,
      provider: value,
      modelName: current.modelName && current.modelName !== providerMeta(current.provider).defaultModel ? current.modelName : next.defaultModel,
      baseUrl: next.defaultBaseUrl,
    }))
    setSaved(false)
    setTestResult(null)
  }

  function validateForm(): string | null {
    if (!form.displayName.trim()) return "请填写配置名称。"
    if (!form.modelName.trim()) return "请填写模型名称。"
    if (!form.baseUrl.trim()) return "请填写 Base URL。"
    const timeout = Number(form.timeoutSeconds)
    if (!Number.isFinite(timeout) || timeout < 1 || timeout > 300) return "超时时间需在 1 到 300 秒之间。"
    if (!form.apiKey.trim() && !form.apiKeyMasked) return "请填写 API Key。"
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
      setError(err instanceof ApiError ? err.message : "保存模型配置失败")
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
      setError(err instanceof ApiError ? err.message : "设置默认模型失败")
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
      setError(err instanceof ApiError ? err.message : "删除模型配置失败")
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
      setTestResult(form.id && !form.apiKey.trim()
        ? await testSavedAgentModelConfig()
        : await testAgentModelConfig(toPayload(form)))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "连接测试失败")
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="space-y-6">
      <Alert>
        <ServerCog className="h-4 w-4" />
        <AlertTitle>大模型 API</AlertTitle>
        <AlertDescription>
          维护 AI 工具和 Agent 运行时使用的全局模型 API。
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
            <CardDescription>共 {configs.length} 个模型 API 配置，点击卡片可编辑。</CardDescription>
          </div>
          <Button className="gap-2" onClick={createConfig}>
            <Plus className="h-4 w-4" />
            新增模型
          </Button>
        </CardHeader>
        <CardContent>
          {loading ? (
            <p className="text-sm text-muted-foreground">加载中...</p>
          ) : configs.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center rounded-xl border border-dashed bg-muted/20 p-8 text-center">
              <ServerCog className="mb-3 h-10 w-10 text-muted-foreground" />
              <p className="font-medium">暂无模型配置</p>
              <p className="mt-1 text-sm text-muted-foreground">保存第一个配置前，后端会使用内置测试配置。</p>
            </div>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
              {configs.map((config) => {
                const vendor = detectModelVendor(config)
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
                          <Badge variant="secondary">{providerMeta(config.provider).label}</Badge>
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
                <Input value={form.displayName} placeholder="例如：MiniMax M2.7 主力模型" onChange={(event) => updateForm("displayName", event.target.value)} />
              </div>
              <div className="space-y-2">
                <Label>配置编码</Label>
                <Input value={form.configCode} placeholder="可选，例如：minimax_m27" onChange={(event) => updateForm("configCode", normalizeConfigCode(event.target.value))} />
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>供应商协议</Label>
                <Select value={form.provider} onValueChange={(value) => applyProvider(value as AgentModelProvider)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {providerOptions.map((item) => (
                      <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
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
                <p className="text-xs text-muted-foreground">识别到的供应商：{liveVendor.label}</p>
              </div>
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
                <Label>LangChain 包</Label>
                <Input value={meta.packageName} readOnly />
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-4 rounded-md bg-secondary p-4">
              <div>
                <p className="font-medium">启用</p>
                <p className="text-sm text-muted-foreground">停用后的配置不会被默认模型调用使用。</p>
              </div>
              <Switch checked={form.enabled} onCheckedChange={(value) => updateForm("enabled", value)} />
            </div>

            <Textarea
              readOnly
              className="min-h-24 font-mono text-xs"
              value={`provider=${form.provider}\nmodel=${form.modelName}\nbase_url=${form.baseUrl}\ntimeout=${form.timeoutSeconds}s`}
            />

            {testResult ? (
              <Alert variant={testResult.success ? "default" : "destructive"}>
                {testResult.success ? <CheckCircle2 className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
                <AlertTitle>{testResult.success ? "连接正常" : "连接失败"}</AlertTitle>
                <AlertDescription>
                  {testResult.message}，延迟 {testResult.latencyMs} ms
                  {testResult.sample ? `，样例：${testResult.sample}` : ""}
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
