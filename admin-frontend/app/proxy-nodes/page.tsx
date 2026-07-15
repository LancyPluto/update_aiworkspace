"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { AdminHeader } from "@/components/admin/header"
import { AdminLayout } from "@/components/admin/admin-layout"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import {
  applyMihomoConfig,
  fetchMihomoRuntime,
  fetchProxyConfig,
  testProxyConnection,
  updateProxyConfig,
  type ManualProxyProtocol,
  type MihomoRuntimeStatus,
  type ProxySourceType,
  type ProxyTestResult,
} from "@/lib/api/proxy-config"
import {
  defaultProxyFormState,
  proxyConfigToFormState,
  proxyFormToUpdate,
  validateProxyForm,
  type ProxyConfigFormState,
  type ProxyFormErrors,
} from "@/lib/proxy-config-form"
import {
  Activity,
  CheckCircle2,
  Cloud,
  ExternalLink,
  Gauge,
  KeyRound,
  Loader2,
  Network,
  RefreshCw,
  RotateCw,
  Save,
  Server,
  ShieldCheck,
  TriangleAlert,
  Wifi,
  XCircle,
} from "lucide-react"

const sourceLabels: Record<ProxySourceType, string> = {
  SUBSCRIPTION: "机场订阅",
  MANUAL: "云服务器",
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return <p className="text-xs text-destructive">{message}</p>
}

function formatError(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

export default function ProxyNodesPage() {
  const [form, setForm] = useState<ProxyConfigFormState>(defaultProxyFormState)
  const [errors, setErrors] = useState<ProxyFormErrors>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [runtimeLoading, setRuntimeLoading] = useState(true)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<ProxyTestResult | null>(null)
  const [runtime, setRuntime] = useState<MihomoRuntimeStatus | null>(null)

  const loadConfig = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const config = await fetchProxyConfig()
      setForm(proxyConfigToFormState(config))
      setErrors({})
    } catch (loadError) {
      setError(formatError(loadError, "代理配置加载失败"))
    } finally {
      setLoading(false)
    }
  }, [])

  const loadRuntime = useCallback(async () => {
    setRuntimeLoading(true)
    try {
      setRuntime(await fetchMihomoRuntime())
    } catch {
      setRuntime(null)
    } finally {
      setRuntimeLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadConfig()
    void loadRuntime()
  }, [loadConfig, loadRuntime])

  const noProxyCount = useMemo(
    () => form.noProxyHosts.split(/[,\n]/).map((item) => item.trim()).filter(Boolean).length,
    [form.noProxyHosts],
  )

  function updateForm<K extends keyof ProxyConfigFormState>(key: K, value: ProxyConfigFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }))
    setErrors((current) => {
      if (!current[key]) return current
      const next = { ...current }
      delete next[key]
      return next
    })
    setNotice(null)
  }

  function changeSource(sourceType: ProxySourceType) {
    setForm((current) => ({ ...current, sourceType }))
    setErrors({})
    setNotice(null)
  }

  async function saveConfig() {
    const nextErrors = validateProxyForm(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setError("请先修正表单中的配置项")
      return
    }

    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      const config = await updateProxyConfig(proxyFormToUpdate(form))
      setForm(proxyConfigToFormState(config))
      setTestResult(null)
      setNotice(runtime?.managed ? "代理配置已保存，尚未应用到 Mihomo" : "代理配置已保存")
    } catch (saveError) {
      setError(formatError(saveError, "代理配置保存失败"))
    } finally {
      setSaving(false)
    }
  }

  async function applyRuntimeConfig() {
    setApplying(true)
    setError(null)
    setNotice(null)
    try {
      const result = await applyMihomoConfig()
      setRuntime(result)
      if (result.available) {
        setNotice("配置已应用到 Mihomo")
      } else {
        setError(result.message || "Mihomo 未能加载配置")
      }
    } catch (applyError) {
      setError(formatError(applyError, "应用 Mihomo 配置失败"))
    } finally {
      setApplying(false)
    }
  }

  async function runConnectionTest() {
    setTesting(true)
    setError(null)
    setNotice(null)
    try {
      const result = await testProxyConnection()
      setTestResult(result)
    } catch (testError) {
      setTestResult(null)
      setError(formatError(testError, "连接测试失败"))
    } finally {
      setTesting(false)
    }
  }

  return (
    <AdminLayout>
      <AdminHeader
        title="代理节点"
        description="维护 Mihomo 订阅来源与平台默认出站代理"
      />

      <div className="space-y-6 p-6">
        {(error || notice) && (
          <Alert variant={error ? "destructive" : "default"}>
            {error ? <TriangleAlert className="h-4 w-4" /> : <CheckCircle2 className="h-4 w-4" />}
            <AlertTitle>{error ? "操作未完成" : "配置已更新"}</AlertTitle>
            <AlertDescription>{error || notice}</AlertDescription>
          </Alert>
        )}

        <section className="overflow-hidden rounded-lg border bg-card">
          <div className="flex flex-col gap-5 border-b px-5 py-5 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex min-w-0 items-start gap-3">
              <div className={`mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${form.enabled ? "bg-emerald-500/12 text-emerald-600 dark:text-emerald-400" : "bg-muted text-muted-foreground"}`}>
                <Network className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-semibold">{form.displayName || "默认代理"}</h2>
                  <Badge variant={form.enabled ? "default" : "secondary"}>
                    {form.enabled ? "已启用" : "未启用"}
                  </Badge>
                  <Badge variant="outline">{sourceLabels[form.sourceType]}</Badge>
                </div>
                <p className="mt-1 truncate font-mono text-xs text-muted-foreground">
                  {form.proxyUrlMasked || "尚未生成代理出口地址"}
                </p>
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              <Button variant="outline" onClick={() => { void loadConfig(); void loadRuntime() }} disabled={loading || saving || testing || applying}>
                <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                重新加载
              </Button>
              <Button onClick={() => void runConnectionTest()} disabled={loading || saving || testing || applying || !form.proxyUrlMasked}>
                {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4" />}
                {testing ? "测试中" : "测试已保存配置"}
              </Button>
            </div>
          </div>

          <div className="grid divide-y sm:grid-cols-2 sm:divide-x sm:divide-y-0 xl:grid-cols-4">
            <StatusMetric icon={form.enabled ? ShieldCheck : XCircle} label="策略状态" value={form.enabled ? "默认继承" : "已停用"} />
            <StatusMetric icon={form.sourceType === "SUBSCRIPTION" ? Wifi : Cloud} label="节点来源" value={sourceLabels[form.sourceType]} />
            <StatusMetric icon={Server} label="直连清单" value={`${noProxyCount} 个主机`} />
            <StatusMetric
              icon={testResult?.success ? CheckCircle2 : Gauge}
              label="最近测试"
              value={testResult ? `${testResult.latencyMs} ms` : "尚未测试"}
              tone={testResult?.success ? "success" : "default"}
            />
          </div>
        </section>

        {testResult && (
          <Alert variant={testResult.success ? "default" : "destructive"}>
            {testResult.success ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
            <AlertTitle>{testResult.success ? "连接正常" : "连接异常"}</AlertTitle>
            <AlertDescription>
              {testResult.message} · {testResult.checkedTarget} · {testResult.latencyMs} ms
            </AlertDescription>
          </Alert>
        )}

        <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
          <section className="min-w-0 overflow-hidden rounded-lg border bg-card">
            <div className="border-b px-5 py-4">
              <h2 className="text-base font-semibold">节点来源</h2>
              <p className="mt-1 text-sm text-muted-foreground">选择一种来源并保存为平台默认出站代理。</p>
            </div>

            {loading ? (
              <div className="flex min-h-96 items-center justify-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                正在加载代理配置
              </div>
            ) : (
              <Tabs value={form.sourceType} onValueChange={(value) => changeSource(value as ProxySourceType)}>
                <div className="px-5 pt-5">
                  <TabsList className="grid h-10 w-full max-w-md grid-cols-2">
                    <TabsTrigger value="SUBSCRIPTION" className="gap-2">
                      <Wifi className="h-4 w-4" />
                      机场订阅
                    </TabsTrigger>
                    <TabsTrigger value="MANUAL" className="gap-2">
                      <Cloud className="h-4 w-4" />
                      云服务器
                    </TabsTrigger>
                  </TabsList>
                </div>

                <div className="space-y-5 p-5">
                  <div className="space-y-2">
                    <Label htmlFor="display-name">配置名称</Label>
                    <Input
                      id="display-name"
                      value={form.displayName}
                      onChange={(event) => updateForm("displayName", event.target.value)}
                      placeholder="例如：海外主线路"
                      aria-invalid={Boolean(errors.displayName)}
                    />
                    <FieldError message={errors.displayName} />
                  </div>

                  <TabsContent value="SUBSCRIPTION" className="mt-0 space-y-5">
                    <div className="space-y-2">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <Label htmlFor="subscription-url">机场订阅链接</Label>
                        {form.subscriptionConfigured && (
                          <Badge variant="secondary" className="gap-1">
                            <KeyRound className="h-3 w-3" />
                            已安全保存
                          </Badge>
                        )}
                      </div>
                      <Input
                        id="subscription-url"
                        type="password"
                        autoComplete="off"
                        value={form.subscriptionUrl}
                        onChange={(event) => updateForm("subscriptionUrl", event.target.value)}
                        placeholder={form.subscriptionConfigured ? form.subscriptionUrlMasked : "https://example.com/subscribe?token=..."}
                        aria-invalid={Boolean(errors.subscriptionUrl)}
                      />
                      <FieldError message={errors.subscriptionUrl} />
                    </div>

                    <div className="grid gap-5 md:grid-cols-2">
                      <div className="space-y-2">
                        <Label htmlFor="subscription-interval">自动更新间隔</Label>
                        <div className="relative">
                          <Input
                            id="subscription-interval"
                            type="number"
                            min={15}
                            max={10080}
                            value={form.subscriptionUpdateIntervalMinutes}
                            onChange={(event) => updateForm("subscriptionUpdateIntervalMinutes", event.target.value)}
                            className="pr-14"
                            aria-invalid={Boolean(errors.subscriptionUpdateIntervalMinutes)}
                          />
                          <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">分钟</span>
                        </div>
                        <FieldError message={errors.subscriptionUpdateIntervalMinutes} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="mihomo-endpoint">Mihomo 出口地址</Label>
                        <Input
                          id="mihomo-endpoint"
                          className="font-mono text-sm"
                          value={form.mihomoEndpoint}
                          onChange={(event) => updateForm("mihomoEndpoint", event.target.value)}
                          placeholder="http://host.docker.internal:7890"
                          aria-invalid={Boolean(errors.mihomoEndpoint)}
                        />
                        <FieldError message={errors.mihomoEndpoint} />
                      </div>
                    </div>
                  </TabsContent>

                  <TabsContent value="MANUAL" className="mt-0 space-y-5">
                    <div className="grid gap-5 md:grid-cols-[160px_minmax(0,1fr)_160px]">
                      <div className="space-y-2">
                        <Label>代理协议</Label>
                        <Select value={form.manualProtocol} onValueChange={(value) => updateForm("manualProtocol", value as ManualProxyProtocol)}>
                          <SelectTrigger><SelectValue /></SelectTrigger>
                          <SelectContent>
                            <SelectItem value="HTTP">HTTP</SelectItem>
                            <SelectItem value="HTTPS">HTTPS</SelectItem>
                            <SelectItem value="SOCKS5">SOCKS5</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="manual-host">云服务器公网 IP</Label>
                        <Input
                          id="manual-host"
                          inputMode="decimal"
                          className="font-mono text-sm"
                          value={form.manualHost}
                          onChange={(event) => updateForm("manualHost", event.target.value)}
                          placeholder="203.0.113.10"
                          aria-invalid={Boolean(errors.manualHost)}
                        />
                        <FieldError message={errors.manualHost} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="manual-port">端口</Label>
                        <Input
                          id="manual-port"
                          type="number"
                          min={1}
                          max={65535}
                          value={form.manualPort}
                          onChange={(event) => updateForm("manualPort", event.target.value)}
                          aria-invalid={Boolean(errors.manualPort)}
                        />
                        <FieldError message={errors.manualPort} />
                      </div>
                    </div>

                    <div className="grid gap-5 md:grid-cols-2">
                      <div className="space-y-2">
                        <Label htmlFor="manual-username">用户名（可选）</Label>
                        <Input
                          id="manual-username"
                          autoComplete="off"
                          value={form.manualUsername}
                          onChange={(event) => updateForm("manualUsername", event.target.value)}
                          placeholder={form.manualUsernameMasked || "无认证可留空"}
                        />
                      </div>
                      <div className="space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <Label htmlFor="manual-password">密码（可选）</Label>
                          {form.manualPasswordConfigured && <Badge variant="secondary">已保存</Badge>}
                        </div>
                        <Input
                          id="manual-password"
                          type="password"
                          autoComplete="new-password"
                          value={form.manualPassword}
                          onChange={(event) => updateForm("manualPassword", event.target.value)}
                          placeholder={form.manualPasswordConfigured ? "留空则保留现有密码" : "无认证可留空"}
                        />
                      </div>
                    </div>
                  </TabsContent>
                </div>
              </Tabs>
            )}

            <div className="flex flex-col gap-3 border-t bg-muted/25 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-xs text-muted-foreground">保存后更新平台默认出站策略；模型级覆盖配置保持优先。</p>
              <Button onClick={() => void saveConfig()} disabled={loading || saving || testing || applying} className="sm:min-w-32">
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                {saving ? "保存中" : "保存配置"}
              </Button>
            </div>
          </section>

          <aside className="min-w-0 space-y-6">
            <section className="rounded-lg border bg-card p-5">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-sm font-semibold">全局出站策略</h2>
                  <p className="mt-1 text-xs text-muted-foreground">控制继承默认策略的模型请求。</p>
                </div>
                <Switch checked={form.enabled} onCheckedChange={(checked) => updateForm("enabled", checked)} aria-label="启用全局出站代理" />
              </div>

              <Separator className="my-5" />

              <div className="space-y-2">
                <Label htmlFor="no-proxy-hosts">直连主机</Label>
                <Textarea
                  id="no-proxy-hosts"
                  className="min-h-40 resize-y font-mono text-xs leading-5"
                  value={form.noProxyHosts}
                  onChange={(event) => updateForm("noProxyHosts", event.target.value)}
                  placeholder="localhost,backend,.cn"
                  aria-invalid={Boolean(errors.noProxyHosts)}
                />
                <FieldError message={errors.noProxyHosts} />
                <p className="text-xs text-muted-foreground">逗号或换行分隔 · 当前 {noProxyCount} 项</p>
              </div>
            </section>

            <section className="rounded-lg border bg-card p-5">
              <div className="flex items-center gap-2">
                <RotateCw className="h-4 w-4 text-muted-foreground" />
                <h2 className="text-sm font-semibold">运行状态边界</h2>
              </div>
              <div className="mt-4 space-y-3 text-sm">
                <RuntimeRow label="配置持久化" value={form.proxyUrlMasked ? "已完成" : "未配置"} ok={Boolean(form.proxyUrlMasked)} />
                <RuntimeRow label="默认策略" value={form.enabled ? "启用" : "停用"} ok={form.enabled} />
                <RuntimeRow label="最近连接测试" value={testResult ? (testResult.success ? "通过" : "失败") : "未执行"} ok={testResult?.success} />
                <RuntimeRow
                  label="Mihomo 管理"
                  value={runtimeLoading ? "检测中" : runtime?.managed ? "Compose 托管" : runtime ? "当前环境未托管" : "状态不可用"}
                  ok={runtime?.managed}
                />
                <RuntimeRow
                  label="控制器"
                  value={runtime?.managed ? (runtime.available ? runtime.version || "在线" : "不可用") : "不适用"}
                  ok={runtime?.available}
                />
                {runtime?.lastAppliedAt && (
                  <RuntimeRow label="最近应用" value={new Date(runtime.lastAppliedAt).toLocaleString("zh-CN")} ok />
                )}
              </div>
              <Button
                variant="outline"
                className="mt-5 w-full"
                onClick={() => void applyRuntimeConfig()}
                disabled={!runtime?.managed || !form.proxyUrlMasked || saving || testing || applying}
              >
                {applying ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCw className="h-4 w-4" />}
                {applying ? "应用中" : "应用到 Mihomo"}
              </Button>
              {!runtimeLoading && !runtime?.managed && (
                <div className="mt-4 flex gap-2 rounded-md border border-amber-500/25 bg-amber-500/8 p-3 text-xs leading-5 text-amber-700 dark:text-amber-300">
                  <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>当前环境未托管 Mihomo。你仍可保存和测试代理配置，本地开发无需启动 Mihomo 容器。</span>
                </div>
              )}
            </section>

            <Button variant="ghost" asChild className="w-full justify-between text-muted-foreground">
              <a href="https://wiki.metacubex.one/" target="_blank" rel="noreferrer">
                Mihomo 配置参考
                <ExternalLink className="h-4 w-4" />
              </a>
            </Button>
          </aside>
        </div>
      </div>
    </AdminLayout>
  )
}

function StatusMetric({
  icon: Icon,
  label,
  value,
  tone = "default",
}: {
  icon: typeof Activity
  label: string
  value: string
  tone?: "default" | "success"
}) {
  return (
    <div className="flex min-h-24 items-center gap-3 px-5 py-4">
      <Icon className={`h-4 w-4 shrink-0 ${tone === "success" ? "text-emerald-600 dark:text-emerald-400" : "text-muted-foreground"}`} />
      <div className="min-w-0">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="mt-1 truncate text-sm font-medium">{value}</p>
      </div>
    </div>
  )
}

function RuntimeRow({ label, value, ok }: { label: string; value: string; ok?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-muted-foreground">{label}</span>
      <span className="flex items-center gap-1.5 font-medium">
        <span className={`h-1.5 w-1.5 rounded-full ${ok ? "bg-emerald-500" : "bg-muted-foreground/40"}`} />
        {value}
      </span>
    </div>
  )
}
