"use client"

import { useCallback, useEffect, useState } from "react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ApiError } from "@/lib/api/http"
import {
  fetchMihomoNodes,
  fetchProxyConfig,
  refreshMihomoSubscription,
  selectMihomoNode,
  testAllMihomoNodes,
  testMihomoNode,
  testProxyConnection,
  updateProxyConfig,
  type ManualProxyProtocol,
  type MihomoNodeList,
  type ProxySourceType,
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
  Check,
  CheckCircle2,
  Cloud,
  Gauge,
  Loader2,
  Pin,
  RefreshCw,
  Save,
  TriangleAlert,
  Wifi,
  Zap,
} from "lucide-react"

export function ProxyNodeManager({ onChanged }: { onChanged?: () => void }) {
  const [form, setForm] = useState<ProxyConfigFormState>(defaultProxyFormState)
  const [errors, setErrors] = useState<ProxyFormErrors>({})
  const [nodes, setNodes] = useState<MihomoNodeList | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [action, setAction] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [config, nodeState] = await Promise.all([fetchProxyConfig(), fetchMihomoNodes()])
      setForm(proxyConfigToFormState(config))
      setNodes(nodeState)
      setErrors({})
    } catch (loadError) {
      setError(formatError(loadError, "代理节点加载失败"))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  function updateForm<K extends keyof ProxyConfigFormState>(key: K, value: ProxyConfigFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }))
    setErrors((current) => {
      if (!current[key]) return current
      const next = { ...current }
      delete next[key]
      return next
    })
    setMessage(null)
  }

  function changeSource(sourceType: ProxySourceType) {
    setForm((current) => ({ ...current, sourceType }))
    setErrors({})
    setMessage(null)
  }

  async function save() {
    const nextErrors = validateProxyForm(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setError("请先修正节点配置")
      return
    }
    setSaving(true)
    setError(null)
    setMessage(null)
    try {
      const saved = await updateProxyConfig(proxyFormToUpdate(form))
      setForm(proxyConfigToFormState(saved))
      setMessage("代理节点配置已保存并应用")
      const nextNodes = await fetchMihomoNodes()
      setNodes(nextNodes)
      onChanged?.()
    } catch (saveError) {
      setError(formatError(saveError, "代理节点保存失败"))
    } finally {
      setSaving(false)
    }
  }

  async function runAction(key: string, task: () => Promise<unknown>, success: string) {
    setAction(key)
    setError(null)
    setMessage(null)
    try {
      await task()
      setNodes(await fetchMihomoNodes())
      setMessage(success)
      onChanged?.()
    } catch (actionError) {
      setError(formatError(actionError, "节点操作失败"))
    } finally {
      setAction(null)
    }
  }

  async function testOne(nodeName: string) {
    setAction(`test:${nodeName}`)
    setError(null)
    setMessage(null)
    try {
      const result = await testMihomoNode(nodeName)
      setNodes((current) => current ? {
        ...current,
        nodes: current.nodes.map((node) => node.name === nodeName
          ? { ...node, available: result.available, latencyMs: result.latencyMs }
          : node),
      } : current)
      setMessage(result.available ? `${nodeName} 延迟 ${result.latencyMs} ms` : `${nodeName} 当前不可用`)
    } catch (testError) {
      setError(formatError(testError, "节点测速失败"))
    } finally {
      setAction(null)
    }
  }

  if (loading) {
    return (
      <section className="flex min-h-72 items-center justify-center rounded-lg border bg-card text-sm text-muted-foreground">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />正在加载代理节点
      </section>
    )
  }

  return (
    <section className="overflow-hidden rounded-lg border bg-card">
      <div className="flex flex-col gap-4 border-b px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h2 className="text-base font-semibold">代理节点</h2>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{form.sourceType === "SUBSCRIPTION" ? "机场订阅" : "云服务器"}</span>
            {nodes?.activeNode && <><span>·</span><span className="max-w-72 truncate font-medium text-foreground">{nodes.activeNode}</span></>}
          </div>
        </div>
        <Button variant="outline" onClick={() => void load()} disabled={Boolean(action) || saving}>
          <RefreshCw className="h-4 w-4" />重新加载
        </Button>
      </div>

      {(error || message) && (
        <div className="px-5 pt-4">
          <Alert variant={error ? "destructive" : "default"}>
            {error ? <TriangleAlert className="h-4 w-4" /> : <CheckCircle2 className="h-4 w-4" />}
            <AlertDescription>{error || message}</AlertDescription>
          </Alert>
        </div>
      )}

      <Tabs value={form.sourceType} onValueChange={(value) => changeSource(value as ProxySourceType)}>
        <div className="px-5 pt-5">
          <TabsList className="grid h-10 w-full max-w-md grid-cols-2">
            <TabsTrigger value="SUBSCRIPTION"><Wifi className="h-4 w-4" />机场订阅</TabsTrigger>
            <TabsTrigger value="MANUAL"><Cloud className="h-4 w-4" />云服务器</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="SUBSCRIPTION" className="mt-0">
          <div className="grid gap-4 p-5 lg:grid-cols-[minmax(0,1fr)_180px]">
            <Field label="订阅链接" error={errors.subscriptionUrl}>
              <Input
                type="password"
                autoComplete="off"
                value={form.subscriptionUrl}
                onChange={(event) => updateForm("subscriptionUrl", event.target.value)}
                placeholder={form.subscriptionConfigured ? form.subscriptionUrlMasked : "https://example.com/sub?..."}
                aria-invalid={Boolean(errors.subscriptionUrl)}
              />
            </Field>
            <Field label="自动更新" error={errors.subscriptionUpdateIntervalMinutes}>
              <div className="relative">
                <Input
                  type="number"
                  min={15}
                  max={10080}
                  value={form.subscriptionUpdateIntervalMinutes}
                  onChange={(event) => updateForm("subscriptionUpdateIntervalMinutes", event.target.value)}
                  className="pr-14"
                />
                <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">分钟</span>
              </div>
            </Field>
          </div>

          <div className="flex flex-col gap-3 border-y bg-muted/20 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant={nodes?.selectionMode === "AUTO" ? "default" : "outline"}
                onClick={() => void runAction("auto", () => selectMihomoNode("AUTO"), "已恢复自动选择最快节点")}
                disabled={Boolean(action) || !nodes?.managed}
              >
                {action === "auto" ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap className="h-4 w-4" />}
                自动最快
              </Button>
              {nodes?.selectionMode === "MANUAL" && nodes.selectedNode && (
                <Badge variant="outline" className="gap-1"><Pin className="h-3 w-3" />已固定 {nodes.selectedNode}</Badge>
              )}
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                onClick={() => void runAction("refresh", refreshMihomoSubscription, "订阅已刷新")}
                disabled={Boolean(action) || !form.subscriptionConfigured}
              >
                {action === "refresh" ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
                刷新订阅
              </Button>
              <Button
                variant="outline"
                onClick={() => void runAction("test-all", testAllMihomoNodes, "全部节点测速完成")}
                disabled={Boolean(action) || !form.subscriptionConfigured}
              >
                {action === "test-all" ? <Loader2 className="h-4 w-4 animate-spin" /> : <Gauge className="h-4 w-4" />}
                全部测速
              </Button>
            </div>
          </div>

          <NodeList
            nodes={nodes}
            action={action}
            onTest={(name) => void testOne(name)}
            onSelect={(name) => void runAction(`select:${name}`, () => selectMihomoNode("MANUAL", name), `已固定使用 ${name}`)}
          />
        </TabsContent>

        <TabsContent value="MANUAL" className="mt-0">
          <div className="grid gap-4 p-5 md:grid-cols-[160px_minmax(0,1fr)_160px]">
            <Field label="代理协议">
              <Select value={form.manualProtocol} onValueChange={(value) => updateForm("manualProtocol", value as ManualProxyProtocol)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="HTTP">HTTP</SelectItem>
                  <SelectItem value="HTTPS">HTTPS</SelectItem>
                  <SelectItem value="SOCKS5">SOCKS5</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label="服务器公网 IP" error={errors.manualHost}>
              <Input className="font-mono" value={form.manualHost} onChange={(event) => updateForm("manualHost", event.target.value)} placeholder="203.0.113.10" />
            </Field>
            <Field label="端口" error={errors.manualPort}>
              <Input type="number" min={1} max={65535} value={form.manualPort} onChange={(event) => updateForm("manualPort", event.target.value)} />
            </Field>
            <Field label="用户名">
              <Input value={form.manualUsername} onChange={(event) => updateForm("manualUsername", event.target.value)} placeholder={form.manualUsernameMasked || "可留空"} />
            </Field>
            <Field label="密码">
              <Input type="password" autoComplete="new-password" value={form.manualPassword} onChange={(event) => updateForm("manualPassword", event.target.value)} placeholder={form.manualPasswordConfigured ? "留空保留现有密码" : "可留空"} />
            </Field>
          </div>
          <div className="flex justify-end border-t bg-muted/20 px-5 py-4">
            <Button
              variant="outline"
              onClick={() => void runAction("test-manual", testProxyConnection, "已保存节点连接正常")}
              disabled={Boolean(action) || saving || !form.manualHost}
            >
              {action === "test-manual" ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4" />}
              测试已保存节点
            </Button>
          </div>
        </TabsContent>
      </Tabs>

      <div className="flex justify-end border-t px-5 py-4">
        <Button onClick={() => void save()} disabled={saving || Boolean(action)}>
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          保存节点配置
        </Button>
      </div>
    </section>
  )
}

function NodeList({ nodes, action, onTest, onSelect }: {
  nodes: MihomoNodeList | null
  action: string | null
  onTest: (name: string) => void
  onSelect: (name: string) => void
}) {
  if (!nodes?.managed) {
    return <div className="px-5 py-10 text-center text-sm text-muted-foreground">当前环境未托管 Mihomo</div>
  }
  if (nodes.nodes.length === 0) {
    return <div className="px-5 py-10 text-center text-sm text-muted-foreground">订阅尚未返回可用节点</div>
  }
  return (
    <div className="divide-y">
      {nodes.nodes.map((node) => (
        <div key={node.name} className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-center gap-3">
            <span className={`h-2 w-2 shrink-0 rounded-full ${node.available ? "bg-emerald-500" : "bg-rose-500"}`} />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="break-all text-sm font-medium">{node.name}</p>
                <Badge variant="secondary">{node.type}</Badge>
                {node.selected && <Badge className="gap-1 bg-emerald-600 hover:bg-emerald-600"><Check className="h-3 w-3" />当前出口</Badge>}
              </div>
              <p className="mt-1 font-mono text-xs text-muted-foreground">{node.latencyMs > 0 ? `${node.latencyMs} ms` : node.available ? "等待测速" : "不可用"}</p>
            </div>
          </div>
          <div className="flex shrink-0 justify-end gap-2">
            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="ghost" size="icon" onClick={() => onTest(node.name)} disabled={Boolean(action)} aria-label={`测试 ${node.name}`}>
                  {action === `test:${node.name}` ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4" />}
                </Button>
              </TooltipTrigger>
              <TooltipContent>单节点测速</TooltipContent>
            </Tooltip>
            <Button variant={node.selected ? "secondary" : "outline"} onClick={() => onSelect(node.name)} disabled={Boolean(action) || !node.available || node.selected}>
              {action === `select:${node.name}` ? <Loader2 className="h-4 w-4 animate-spin" /> : <Pin className="h-4 w-4" />}
              {node.selected ? "使用中" : "固定使用"}
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      {children}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}

function formatError(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}
