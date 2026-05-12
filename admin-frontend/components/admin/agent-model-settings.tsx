"use client"

import { useEffect, useMemo, useState } from "react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import { fetchAgentModelConfig, saveAgentModelConfig, testAgentModelConfig } from "@/lib/api/agent-model"
import type { AgentModelConfigPayload, AgentModelConfigTestResult, AgentModelProvider } from "@/lib/api/types"
import { AlertCircle, Bot, CheckCircle2, KeyRound, RefreshCw, Save, ServerCog, Zap } from "lucide-react"

interface ModelForm {
  provider: AgentModelProvider
  modelName: string
  baseUrl: string
  apiKey: string
  minimaxGroupId: string
  timeoutSeconds: string
  enabled: boolean
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
    label: "OpenAI 兼容接口",
    packageName: "langchain-openai",
    defaultModel: "gpt-4o-mini",
    defaultBaseUrl: "https://api.openai.com/v1",
    description: "通过 ChatOpenAI 接入 OpenAI 兼容聊天补全接口。",
  },
  {
    value: "anthropic_compatible",
    label: "Anthropic 兼容接口",
    packageName: "langchain-anthropic",
    defaultModel: "MiniMax-M2.7",
    defaultBaseUrl: "https://api.minimaxi.com/anthropic",
    description: "通过 ChatAnthropic 接入 Anthropic 兼容接口，可用于 MiniMax M2.7。",
  },
  {
    value: "minimax",
    label: "MiniMax 旧版接口",
    packageName: "langchain-community",
    defaultModel: "abab6.5s-chat",
    defaultBaseUrl: "https://api.minimaxi.com/v1/text/chatcompletion_v2",
    description: "通过旧版 LangChain MiniMaxChat 接入，需要填写 Group ID。",
  },
  {
    value: "mock",
    label: "本地模拟模型",
    packageName: "built-in",
    defaultModel: "mock",
    defaultBaseUrl: "",
    description: "使用本地确定性模拟模型，适合开发调试和兜底。",
  },
]

const defaults: ModelForm = {
  provider: "mock",
  modelName: "mock",
  baseUrl: "",
  apiKey: "",
  minimaxGroupId: "",
  timeoutSeconds: "60",
  enabled: true,
}

function providerMeta(provider: string) {
  return providerOptions.find((item) => item.value === provider) || providerOptions[0]
}

function toPayload(form: ModelForm): AgentModelConfigPayload {
  return {
    provider: form.provider,
    modelName: form.modelName.trim(),
    baseUrl: form.baseUrl.trim(),
    apiKey: form.apiKey.trim(),
    minimaxGroupId: form.minimaxGroupId.trim(),
    timeoutSeconds: Number(form.timeoutSeconds) || 60,
    enabled: form.enabled,
  }
}

export function AgentModelSettings() {
  const [form, setForm] = useState<ModelForm>(defaults)
  const [apiKeyMasked, setApiKeyMasked] = useState("")
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<AgentModelConfigTestResult | null>(null)

  const meta = useMemo(() => providerMeta(form.provider), [form.provider])
  const langchainIdentifier = `${form.provider}:${form.modelName || meta.defaultModel}`

  async function loadConfig() {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchAgentModelConfig()
      setForm({
        provider: (data.provider || "mock") as AgentModelProvider,
        modelName: data.modelName || "mock",
        baseUrl: data.baseUrl || "",
        apiKey: "",
        minimaxGroupId: data.minimaxGroupId || "",
        timeoutSeconds: String(data.timeoutSeconds || 60),
        enabled: data.enabled !== false,
      })
      setApiKeyMasked(data.apiKeyMasked || "")
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载大模型配置失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConfig()
  }, [])

  function updateForm<K extends keyof ModelForm>(key: K, value: ModelForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
    setSaved(false)
    setTestResult(null)
  }

  function applyProvider(value: AgentModelProvider) {
    const next = providerMeta(value)
    setForm((current) => ({
      ...current,
      provider: value,
      modelName:
        current.modelName && current.modelName !== providerMeta(current.provider).defaultModel
          ? current.modelName
          : next.defaultModel,
      baseUrl: next.defaultBaseUrl,
      minimaxGroupId: value === "minimax" ? current.minimaxGroupId : "",
    }))
    setSaved(false)
    setTestResult(null)
  }

  function validateForm(): string | null {
    if (!form.modelName.trim()) return "请填写模型名称。"
    const timeout = Number(form.timeoutSeconds)
    if (!Number.isFinite(timeout) || timeout < 1 || timeout > 300) return "超时时间必须在 1 到 300 秒之间。"
    if ((form.provider === "openai_compatible" || form.provider === "anthropic_compatible") && !form.baseUrl.trim()) {
      return "兼容接口必须填写 Base URL。"
    }
    if (form.provider === "minimax" && !form.minimaxGroupId.trim()) return "MiniMax 旧版接口必须填写 Group ID。"
    if (form.provider !== "mock" && !form.apiKey.trim() && !apiKeyMasked) return "使用真实模型前请先填写 API Key。"
    return null
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
      setTestResult(await testAgentModelConfig(toPayload(form)))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "连接测试失败")
    } finally {
      setTesting(false)
    }
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
      const savedConfig = await saveAgentModelConfig(toPayload(form))
      setApiKeyMasked(savedConfig.apiKeyMasked || "")
      setForm((current) => ({ ...current, apiKey: "" }))
      setSaved(true)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存大模型配置失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <Alert>
        <Bot className="h-4 w-4" />
        <AlertTitle>运行时模型</AlertTitle>
        <AlertDescription>
          agent-service 当前通过 LangChain 调用聊天模型，当前目标为{" "}
          <code className="rounded bg-secondary px-1.5 py-0.5 font-mono text-xs">{langchainIdentifier}</code>
        </AlertDescription>
      </Alert>

      {error && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div className="grid gap-6 xl:grid-cols-[1fr_360px]">
        <Card className="rounded-lg">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <ServerCog className="h-5 w-5" />
              大模型接入
            </CardTitle>
            <CardDescription>配置后端预检和 agent-service 运行时使用的模型供应商信息。</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>供应商</Label>
                <Select value={form.provider} onValueChange={(value) => applyProvider(value as AgentModelProvider)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {providerOptions.map((item) => (
                      <SelectItem key={item.value} value={item.value}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label>模型名称</Label>
                <Input
                  value={form.modelName}
                  placeholder={meta.defaultModel}
                  onChange={(event) => updateForm("modelName", event.target.value)}
                />
              </div>
            </div>

            <div className="grid gap-5 md:grid-cols-2">
              <div className="space-y-2">
                <Label>Base URL</Label>
                <Input
                  value={form.baseUrl}
                  placeholder={meta.defaultBaseUrl || "使用供应商默认地址"}
                  disabled={form.provider === "mock"}
                  onChange={(event) => updateForm("baseUrl", event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label>超时时间（秒）</Label>
                <Input
                  type="number"
                  min={1}
                  max={300}
                  value={form.timeoutSeconds}
                  onChange={(event) => updateForm("timeoutSeconds", event.target.value)}
                />
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
                    placeholder={apiKeyMasked ? `已保存：${apiKeyMasked}` : "请输入供应商 API Key"}
                    disabled={form.provider === "mock"}
                    onChange={(event) => updateForm("apiKey", event.target.value)}
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label>MiniMax Group ID</Label>
                <Input
                  value={form.minimaxGroupId}
                  disabled={form.provider !== "minimax"}
                  placeholder="仅 MiniMax 旧版接口必填"
                  onChange={(event) => updateForm("minimaxGroupId", event.target.value)}
                />
              </div>
            </div>

            <div className="flex items-center justify-between rounded-md bg-secondary p-4">
              <div>
                <p className="font-medium">启用于 Agent 运行</p>
                <p className="text-sm text-muted-foreground">关闭后会回退到本地模拟模型。</p>
              </div>
              <Switch checked={form.enabled} onCheckedChange={(value) => updateForm("enabled", value)} />
            </div>

            <div className="flex flex-wrap items-center justify-end gap-3">
              <Button variant="outline" className="gap-2" onClick={loadConfig} disabled={loading || saving || testing}>
                <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
                刷新
              </Button>
              <Button variant="outline" className="gap-2" onClick={testConfig} disabled={saving || testing}>
                <Zap className={testing ? "h-4 w-4 animate-pulse" : "h-4 w-4"} />
                {testing ? "测试中..." : "测试连接"}
              </Button>
              <Button className="min-w-32 gap-2" onClick={saveConfig} disabled={saving || testing}>
                {saved ? <CheckCircle2 className="h-4 w-4" /> : <Save className={saving ? "h-4 w-4 animate-spin" : "h-4 w-4"} />}
                {saved ? "已保存" : saving ? "保存中..." : "保存配置"}
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-6">
          <Card className="rounded-lg">
            <CardHeader>
              <CardTitle>供应商说明</CardTitle>
              <CardDescription>{meta.description}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">LangChain 包</span>
                <Badge variant="secondary">{meta.packageName}</Badge>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">运行时供应商</span>
                <Badge>{form.provider}</Badge>
              </div>
              <div className="rounded-md bg-secondary p-3 font-mono text-xs">init_chat_model(&quot;{langchainIdentifier}&quot;)</div>
              <Textarea
                readOnly
                className="min-h-28 font-mono text-xs"
                value={`provider=${form.provider}\nmodel=${form.modelName || meta.defaultModel}\nbase_url=${form.baseUrl || "(provider default)"}\ntimeout=${form.timeoutSeconds}s`}
              />
            </CardContent>
          </Card>

          <Card className="rounded-lg">
            <CardHeader>
              <CardTitle>连接测试</CardTitle>
              <CardDescription>通过 agent-service 和 LangChain 发起一次简短连通性检测。</CardDescription>
            </CardHeader>
            <CardContent>
              {testResult ? (
                <div className="space-y-3 text-sm">
                  <div className="flex items-center gap-2">
                    {testResult.success ? (
                      <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                    ) : (
                      <AlertCircle className="h-4 w-4 text-destructive" />
                    )}
                    <span className="font-medium">{testResult.success ? "连接正常" : "连接失败"}</span>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-md bg-secondary p-3">
                      <p className="text-muted-foreground">延迟</p>
                      <p className="font-medium">{testResult.latencyMs} ms</p>
                    </div>
                    <div className="rounded-md bg-secondary p-3">
                      <p className="text-muted-foreground">返回信息</p>
                      <p className="break-words font-medium">{testResult.message}</p>
                    </div>
                  </div>
                  <Textarea readOnly className="min-h-24" value={testResult.sample || "未返回示例内容。"} />
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">暂无连接测试结果。</p>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
