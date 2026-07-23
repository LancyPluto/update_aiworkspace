"use client"

import { useCallback, useEffect, useState } from "react"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ApiError } from "@/lib/api/http"
import {
  fetchProxyRoutingConfig,
  testProxyDomain,
  updateProxyRoutingConfig,
  type ProxyAutoSettings,
  type ProxyDomainTestResult,
  type ProxyDomainTestSummary,
} from "@/lib/api/proxy-config"
import {
  addProxyDomain,
  normalizeProxyDomain,
  proxyDomainsFromRules,
  proxyDomainsToRules,
} from "@/lib/proxy-allowlist"
import { Activity, CheckCircle2, Globe2, Loader2, Plus, Trash2, TriangleAlert } from "lucide-react"

const defaultAutoSettings: ProxyAutoSettings = {
  timeoutMs: 5000,
  sampleSize: 6,
  switchThresholdMs: 150,
  hysteresisMs: 80,
  cooldownSeconds: 300,
}

export function ProxyDomainAllowlist({ onApplied }: { onApplied?: () => void }) {
  const [domains, setDomains] = useState<string[]>([])
  const [autoSettings, setAutoSettings] = useState(defaultAutoSettings)
  const [results, setResults] = useState<Record<string, ProxyDomainTestSummary>>({})
  const [newDomain, setNewDomain] = useState("")
  const [loading, setLoading] = useState(true)
  const [testing, setTesting] = useState<string | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const config = await fetchProxyRoutingConfig()
      setDomains(proxyDomainsFromRules(config.rules))
      setAutoSettings(config.autoSettings || defaultAutoSettings)
      setResults(config.testResults || {})
    } catch (loadError) {
      setError(formatError(loadError, "网站名单加载失败"))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function persist(nextDomains: string[]) {
    await updateProxyRoutingConfig({
      rules: proxyDomainsToRules(nextDomains),
      autoSettings,
    })
    setDomains(nextDomains)
    onApplied?.()
  }

  async function addAndTest() {
    const domain = normalizeProxyDomain(newDomain)
    if (!domain) {
      setError("请输入有效的公网域名或网站地址")
      return
    }
    const nextDomains = addProxyDomain(domains, domain)
    const savedDomain = nextDomains.find((item) => domain === item || domain.endsWith(`.${item}`)) || domain
    const changed = JSON.stringify(nextDomains) !== JSON.stringify(domains)
    const configuredMessage = changed
      ? `${savedDomain} 已添加并启用`
      : domain === savedDomain
        ? `${savedDomain} 已在代理名单中`
        : `${domain} 已由 ${savedDomain} 的代理规则覆盖`

    setTesting(savedDomain)
    setError(null)
    setMessage(null)
    try {
      if (changed) {
        await persist(nextDomains)
      }
      setNewDomain("")

      try {
        const result = await testProxyDomain(domain)
        setResults((current) => ({ ...current, [savedDomain]: summarizeTest(result) }))
        if (result.proxy.success) {
          setMessage(`${configuredMessage}，连接测试通过`)
        } else {
          setError(`${configuredMessage}；Mihomo 连接测试失败（${result.proxy.error || "连接不可用"}），规则仍保持启用`)
        }
      } catch (testError) {
        setError(`${configuredMessage}；${formatError(testError, "连接测试未完成")}，规则仍保持启用`)
      }
    } catch (saveError) {
      setError(formatError(saveError, "添加失败"))
    } finally {
      setTesting(null)
    }
  }

  async function retest(domain: string) {
    setTesting(domain)
    setError(null)
    setMessage(null)
    try {
      const result = await testProxyDomain(domain)
      setResults((current) => ({ ...current, [domain]: summarizeTest(result) }))
      if (result.proxy.success) {
        setMessage(`${domain} 连接正常`)
      } else {
        setError(`${domain} 连接失败：${result.proxy.error || "代理节点不可用"}`)
      }
    } catch (testError) {
      setError(formatError(testError, "连接测试失败"))
    } finally {
      setTesting(null)
    }
  }

  async function remove(domain: string) {
    setRemoving(domain)
    setError(null)
    setMessage(null)
    try {
      const nextDomains = domains.filter((item) => item !== domain)
      await persist(nextDomains)
      setResults((current) => {
        const next = { ...current }
        delete next[domain]
        return next
      })
      setMessage(`${domain} 已移出代理名单`)
    } catch (removeError) {
      setError(formatError(removeError, "删除网站失败"))
    } finally {
      setRemoving(null)
    }
  }

  return (
    <section className="overflow-hidden rounded-lg border bg-card">
      <div className="flex flex-col gap-4 border-b px-5 py-4 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <h2 className="text-base font-semibold">代理网站名单</h2>
          <p className="mt-1 text-sm text-muted-foreground">{domains.length} 个网站使用项目代理</p>
        </div>
        <div className="flex w-full flex-col gap-2 sm:flex-row lg:max-w-2xl">
          <Input
            value={newDomain}
            onChange={(event) => setNewDomain(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !testing) void addAndTest()
            }}
            placeholder="例如 ofox.ai"
            className="min-w-0 font-mono"
            aria-label="网站域名"
          />
          <Button onClick={() => void addAndTest()} disabled={Boolean(testing) || !newDomain.trim()} className="shrink-0">
            {testing === normalizeProxyDomain(newDomain) ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
            添加
          </Button>
        </div>
      </div>

      {(error || message) && (
        <div className="px-5 pt-4">
          <Alert variant={error ? "destructive" : "default"}>
            {error ? <TriangleAlert className="h-4 w-4" /> : <CheckCircle2 className="h-4 w-4" />}
            <AlertDescription>{error || message}</AlertDescription>
          </Alert>
        </div>
      )}

      {loading ? (
        <div className="flex min-h-56 items-center justify-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />正在加载网站名单
        </div>
      ) : domains.length === 0 ? (
        <div className="flex min-h-56 flex-col items-center justify-center gap-3 px-5 text-center">
          <Globe2 className="h-8 w-8 text-muted-foreground" />
          <div>
            <p className="text-sm font-medium">暂无代理网站</p>
            <p className="mt-1 text-xs text-muted-foreground">未加入名单的网站使用服务器网卡</p>
          </div>
        </div>
      ) : (
        <>
          <div className="hidden md:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-5">网站</TableHead>
                  <TableHead>连接状态</TableHead>
                  <TableHead>代理延迟</TableHead>
                  <TableHead>最近测试</TableHead>
                  <TableHead className="pr-5 text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {domains.map((domain) => (
                  <DomainRow
                    key={domain}
                    domain={domain}
                    result={results[domain]}
                    testing={testing === domain}
                    removing={removing === domain}
                    onTest={() => void retest(domain)}
                    onRemove={() => void remove(domain)}
                  />
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="divide-y md:hidden">
            {domains.map((domain) => (
              <MobileDomainRow
                key={domain}
                domain={domain}
                result={results[domain]}
                testing={testing === domain}
                removing={removing === domain}
                onTest={() => void retest(domain)}
                onRemove={() => void remove(domain)}
              />
            ))}
          </div>
        </>
      )}
    </section>
  )
}

function DomainRow({ domain, result, testing, removing, onTest, onRemove }: DomainRowProps) {
  return (
    <TableRow>
      <TableCell className="pl-5 font-mono font-medium">{domain}</TableCell>
      <TableCell><ConnectionBadge result={result} /></TableCell>
      <TableCell className="font-mono text-xs">{result?.success ? `${result.latencyMs} ms` : "-"}</TableCell>
      <TableCell className="text-xs text-muted-foreground">{result ? formatTime(result.testedAt) : "尚未测试"}</TableCell>
      <TableCell className="pr-5">
        <DomainActions {...{ domain, testing, removing, onTest, onRemove }} />
      </TableCell>
    </TableRow>
  )
}

function MobileDomainRow({ domain, result, testing, removing, onTest, onRemove }: DomainRowProps) {
  return (
    <div className="space-y-3 px-4 py-4">
      <div className="flex min-w-0 items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="break-all font-mono text-sm font-medium">{domain}</p>
          <p className="mt-1 text-xs text-muted-foreground">{result ? formatTime(result.testedAt) : "尚未测试"}</p>
        </div>
        <ConnectionBadge result={result} />
      </div>
      <div className="flex items-center justify-between gap-3">
        <span className="font-mono text-xs text-muted-foreground">{result?.success ? `${result.latencyMs} ms` : "等待连接测试"}</span>
        <DomainActions {...{ domain, testing, removing, onTest, onRemove }} />
      </div>
    </div>
  )
}

interface DomainRowProps {
  domain: string
  result?: ProxyDomainTestSummary
  testing: boolean
  removing: boolean
  onTest: () => void
  onRemove: () => void
}

function DomainActions({ domain, testing, removing, onTest, onRemove }: Omit<DomainRowProps, "result">) {
  return (
    <div className="flex justify-end gap-1">
      <Tooltip>
        <TooltipTrigger asChild>
          <Button variant="ghost" size="icon" onClick={onTest} disabled={testing || removing} aria-label={`测试 ${domain}`}>
            {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4" />}
          </Button>
        </TooltipTrigger>
        <TooltipContent>测试连接</TooltipContent>
      </Tooltip>
      <AlertDialog>
        <Tooltip>
          <TooltipTrigger asChild>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="icon" disabled={testing || removing} aria-label={`删除 ${domain}`}>
                {removing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
              </Button>
            </AlertDialogTrigger>
          </TooltipTrigger>
          <TooltipContent>移出代理名单</TooltipContent>
        </Tooltip>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>移出代理名单？</AlertDialogTitle>
            <AlertDialogDescription>{domain} 将改为使用服务器网卡直连。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={onRemove}>确认移除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function ConnectionBadge({ result }: { result?: ProxyDomainTestSummary }) {
  if (!result) return <Badge variant="secondary">待测试</Badge>
  return result.success
    ? <Badge className="bg-emerald-600 hover:bg-emerald-600">可用</Badge>
    : <Badge variant="destructive">不可用</Badge>
}

function summarizeTest(result: ProxyDomainTestResult): ProxyDomainTestSummary {
  return {
    success: result.proxy.success,
    latencyMs: result.proxy.success ? result.proxy.totalMs : 0,
    testedAt: result.testedAt,
  }
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", { hour12: false })
}

function formatError(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}
