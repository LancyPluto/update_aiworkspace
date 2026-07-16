"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import {
  fetchProxyRoutingConfig,
  testProxyDomain,
  updateProxyRoutingConfig,
  type ProxyAutoSettings,
  type ProxyDomainTestResult,
  type ProxyPatternType,
  type ProxyRouteStrategy,
  type ProxyRoutingRule,
} from "@/lib/api/proxy-config"
import {
  diagnosticDomainForRule,
  isRoutingConfigDirty,
  routingConfigFingerprint,
  sortRoutingRules,
  validateRoutingRule,
  validateRoutingRules,
  wildcardTestDomainError,
  type RoutingRuleErrors,
} from "@/lib/proxy-routing-form"
import { ApiError } from "@/lib/api/http"
import { Activity, AlertTriangle, Loader2, Plus, Save, Trash2 } from "lucide-react"

const defaultAuto: ProxyAutoSettings = {
  timeoutMs: 5000,
  sampleSize: 6,
  switchThresholdMs: 150,
  hysteresisMs: 80,
  cooldownSeconds: 300,
}

const strategyTone: Record<ProxyRouteStrategy, string> = {
  DIRECT: "border-emerald-500/30 text-emerald-700 dark:text-emerald-300",
  PROXY: "border-sky-500/30 text-sky-700 dark:text-sky-300",
  AUTO: "border-amber-500/30 text-amber-700 dark:text-amber-300",
}

export function PublicRoutingPanel() {
  const [rules, setRules] = useState<ProxyRoutingRule[]>([])
  const [autoSettings, setAutoSettings] = useState(defaultAuto)
  const [warnings, setWarnings] = useState<string[]>([])
  const [errors, setErrors] = useState<RoutingRuleErrors[]>([])
  const [results, setResults] = useState<Record<string, ProxyDomainTestResult>>({})
  const [testDomains, setTestDomains] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testingId, setTestingId] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [savedFingerprint, setSavedFingerprint] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    setMessage(null)
    try {
      const config = await fetchProxyRoutingConfig()
      const loadedRules = sortRoutingRules(config.rules)
      setRules(loadedRules)
      setAutoSettings(config.autoSettings)
      setSavedFingerprint(routingConfigFingerprint(loadedRules, config.autoSettings))
      setWarnings(config.warnings)
      setErrors([])
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "公网分流规则加载失败")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const enabledCount = useMemo(() => rules.filter((rule) => rule.enabled).length, [rules])
  const hasUnsavedChanges = useMemo(
    () => Boolean(savedFingerprint) && isRoutingConfigDirty(rules, autoSettings, savedFingerprint),
    [autoSettings, rules, savedFingerprint],
  )

  function updateRule(index: number, patch: Partial<ProxyRoutingRule>) {
    setRules((current) => current.map((rule, ruleIndex) => ruleIndex === index ? { ...rule, ...patch } : rule))
    setResults({})
    setErrors([])
    setMessage(null)
  }

  function updateAutoSettings(settings: ProxyAutoSettings) {
    setAutoSettings(settings)
    setResults({})
    setMessage(null)
  }

  function addRule() {
    setRules((current) => [...current, {
      id: `rule-${current.length + 1}`,
      patternType: "EXACT",
      pattern: "",
      strategy: "DIRECT",
      priority: 100,
      enabled: true,
      note: "",
      probeUrl: "",
    }])
    setResults({})
    setMessage(null)
  }

  function removeRule(index: number) {
    setRules((current) => current.filter((_, ruleIndex) => ruleIndex !== index))
    setResults({})
    setErrors([])
    setMessage(null)
  }

  async function save() {
    const nextErrors = validateRoutingRules(rules)
    setErrors(nextErrors)
    if (nextErrors.some((error) => Object.keys(error).length > 0)) {
      setMessage("请先修正规则中的校验错误")
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      const config = await updateProxyRoutingConfig({ rules: sortRoutingRules(rules), autoSettings })
      const savedRules = sortRoutingRules(config.rules)
      setRules(savedRules)
      setAutoSettings(config.autoSettings)
      setSavedFingerprint(routingConfigFingerprint(savedRules, config.autoSettings))
      setWarnings(config.warnings)
      setMessage("公网分流规则已保存")
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "公网分流规则保存失败")
    } finally {
      setSaving(false)
    }
  }

  async function testRule(rule: ProxyRoutingRule) {
    const ruleErrors = validateRoutingRules([rule])[0]
    if (Object.keys(ruleErrors).length > 0) {
      setMessage("当前规则校验未通过，无法执行诊断")
      return
    }
    setTestingId(rule.id)
    setMessage(null)
    try {
      const domain = diagnosticDomainForRule(rule, testDomains[rule.id] || "")
      if (!domain) {
        setMessage("通配规则需要填写一个真实命中的测试域名")
        return
      }
      const result = await testProxyDomain(domain, rule.probeUrl)
      setResults((current) => ({ ...current, [rule.id]: result }))
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "双路径诊断失败")
    } finally {
      setTestingId(null)
    }
  }

  return (
    <section className="min-w-0 overflow-hidden rounded-lg border bg-card">
      <div className="flex flex-col gap-4 border-b px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-base font-semibold">公网域名分流规则</h2>
            <Badge variant="outline">{enabledCount} 条启用</Badge>
            <Badge variant="outline" className="border-emerald-500/30 text-emerald-700 dark:text-emerald-300">业务兜底 DIRECT</Badge>
            <Badge variant="secondary">第一阶段：Worker 统一客户端</Badge>
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            容器内代理地址为 mihomo:7890；仅命中 PROXY/AUTO 的请求进入 Mihomo，AUTO 再由 Mihomo 选择公网出口。
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          <Button variant="outline" onClick={addRule} disabled={loading || saving}>
            <Plus className="h-4 w-4" />新增规则
          </Button>
          <Button onClick={() => void save()} disabled={loading || saving}>
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saving ? "保存中" : "保存规则"}
          </Button>
          <p className="basis-full text-right text-xs text-muted-foreground">
            {hasUnsavedChanges ? "有未保存修改，保存后才能执行诊断" : "保存时尝试同步到 Mihomo"}
          </p>
        </div>
      </div>

      {(message || warnings.length > 0) && (
        <div className="space-y-2 border-b px-5 py-4">
          {message && <Alert><AlertTitle>配置状态</AlertTitle><AlertDescription>{message}</AlertDescription></Alert>}
          {warnings.map((warning) => (
            <Alert key={warning} variant="destructive">
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>路由冲突</AlertTitle>
              <AlertDescription>{warning}</AlertDescription>
            </Alert>
          ))}
        </div>
      )}

      {loading ? (
        <div className="flex min-h-48 items-center justify-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />正在加载公网分流规则
        </div>
      ) : (
        <div className="overflow-x-auto">
          <Table className="min-w-[1280px] table-fixed">
            <TableHeader>
              <TableRow>
                <TableHead className="w-20">启用</TableHead>
                <TableHead className="w-36">模式</TableHead>
                <TableHead className="w-64">域名</TableHead>
                <TableHead className="w-36">策略</TableHead>
                <TableHead className="w-28">优先级</TableHead>
                <TableHead className="w-[360px]">备注 / AUTO 探针</TableHead>
                <TableHead className="w-32 text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rules.map((rule, index) => (
                <RuleRow
                  key={rule.id}
                  rule={rule}
                  errors={errors[index] || {}}
                  result={results[rule.id]}
                  testing={testingId === rule.id}
                  configDirty={hasUnsavedChanges}
                  testDomain={testDomains[rule.id] || ""}
                  onChange={(patch) => updateRule(index, patch)}
                  onTestDomainChange={(value) => setTestDomains((current) => ({ ...current, [rule.id]: value }))}
                  onTest={() => void testRule(rule)}
                  onRemove={() => removeRule(index)}
                />
              ))}
              <TableRow className="bg-muted/25">
                <TableCell><Switch checked disabled aria-label="兜底规则启用" /></TableCell>
                <TableCell><Badge variant="secondary">MATCH</Badge></TableCell>
                <TableCell className="font-mono text-xs">所有未命中公网域名</TableCell>
                <TableCell><Badge variant="outline" className={strategyTone.DIRECT}>DIRECT</Badge></TableCell>
                <TableCell className="font-mono text-xs">最后</TableCell>
                <TableCell className="text-xs text-muted-foreground">业务请求完全不经过 Mihomo</TableCell>
                <TableCell />
              </TableRow>
            </TableBody>
          </Table>
        </div>
      )}

      <AutoSettings settings={autoSettings} onChange={updateAutoSettings} />
    </section>
  )
}

function RuleRow({ rule, errors, result, testing, configDirty, testDomain, onChange, onTestDomainChange, onTest, onRemove }: {
  rule: ProxyRoutingRule
  errors: RoutingRuleErrors
  result?: ProxyDomainTestResult
  testing: boolean
  configDirty: boolean
  testDomain: string
  onChange: (patch: Partial<ProxyRoutingRule>) => void
  onTestDomainChange: (value: string) => void
  onTest: () => void
  onRemove: () => void
}) {
  const wildcardDomainError = wildcardTestDomainError(rule, testDomain)
  const ruleValidationError = Object.values(validateRoutingRule(rule))[0]
  const diagnosticDomain = diagnosticDomainForRule(rule, testDomain)
  const testDisabledReason = configDirty
    ? "请先保存规则后再执行双路径测试"
    : ruleValidationError || wildcardDomainError || (!diagnosticDomain ? "当前规则无法执行诊断" : "")

  return (
    <>
      <TableRow className="align-top">
        <TableCell className="pt-4"><Switch checked={rule.enabled} onCheckedChange={(enabled) => onChange({ enabled })} aria-label={`启用规则 ${rule.id}`} /></TableCell>
        <TableCell>
          <Select value={rule.patternType} onValueChange={(value) => onChange({ patternType: value as ProxyPatternType })}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="EXACT">精确</SelectItem><SelectItem value="SUFFIX">后缀</SelectItem><SelectItem value="WILDCARD">通配</SelectItem></SelectContent>
          </Select>
        </TableCell>
        <TableCell>
          <Input className="font-mono text-xs" value={rule.pattern} onChange={(event) => onChange({ pattern: event.target.value })} placeholder={rule.patternType === "WILDCARD" ? "*.example.com" : "api.example.com"} />
          {errors.pattern && <p className="mt-1 text-xs text-destructive">{errors.pattern}</p>}
        </TableCell>
        <TableCell>
          <Select value={rule.strategy} onValueChange={(value) => onChange({ strategy: value as ProxyRouteStrategy })}>
            <SelectTrigger className={strategyTone[rule.strategy]}><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="DIRECT">DIRECT</SelectItem><SelectItem value="PROXY">PROXY</SelectItem><SelectItem value="AUTO">AUTO</SelectItem></SelectContent>
          </Select>
        </TableCell>
        <TableCell>
          <Input type="number" min={0} max={10000} value={rule.priority} onChange={(event) => onChange({ priority: Number(event.target.value) })} />
          {errors.priority && <p className="mt-1 text-xs text-destructive">{errors.priority}</p>}
        </TableCell>
        <TableCell className="w-[360px] max-w-[360px] space-y-2">
          <Input className="block" value={rule.note} onChange={(event) => onChange({ note: event.target.value })} placeholder="备注" />
          {rule.strategy === "AUTO" && <Input className="block font-mono text-xs" value={rule.probeUrl} onChange={(event) => onChange({ probeUrl: event.target.value })} placeholder="https://api.example.com/health" />}
          {rule.patternType === "WILDCARD" && rule.strategy !== "AUTO" && (
            <>
              <Input className="block font-mono text-xs" value={testDomain} onChange={(event) => onTestDomainChange(event.target.value)} placeholder="实际测试子域（不保存）" aria-invalid={Boolean(wildcardDomainError)} />
              {wildcardDomainError && <p className="text-xs text-destructive">{wildcardDomainError}</p>}
            </>
          )}
          {errors.probeUrl && <p className="text-xs text-destructive">{errors.probeUrl}</p>}
        </TableCell>
        <TableCell>
          <div className="flex justify-end gap-1">
            <Tooltip>
              <TooltipTrigger asChild>
                <span>
                  <Button variant="ghost" size="icon" onClick={onTest} disabled={testing || Boolean(testDisabledReason)} aria-label="双路径测试">
                    {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4" />}
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>{testDisabledReason || "双路径测试"}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="ghost" size="icon" onClick={onRemove} aria-label="删除规则"><Trash2 className="h-4 w-4" /></Button>
              </TooltipTrigger>
              <TooltipContent>删除规则</TooltipContent>
            </Tooltip>
          </div>
        </TableCell>
      </TableRow>
      {result && (
        <TableRow className="bg-muted/20">
          <TableCell colSpan={7} className="px-5 py-3">
            <div className="grid gap-3 text-xs md:grid-cols-[1fr_1fr_1.2fr]">
              <PathEvidence label="服务器网卡 DIRECT" result={result.direct} />
              <PathEvidence label="项目代理 PROXY" result={result.proxy} />
              <div className="min-w-0 border-l pl-3">
                <p className="font-medium">AUTO 诊断建议：{result.autoDecision.selectedPath}</p>
                <p className="mt-1 text-muted-foreground">{result.autoDecision.reason}</p>
                <p className="mt-1 text-muted-foreground">实际 AUTO 由 Mihomo url-test 选路 · HEAD 样本 {result.autoDecision.sampleCount} · 冷却 {result.autoDecision.cooldownSeconds}s</p>
              </div>
            </div>
          </TableCell>
        </TableRow>
      )}
    </>
  )
}

function PathEvidence({ label, result }: { label: string; result: ProxyDomainTestResult["direct"] }) {
  return <div className="min-w-0"><div className="flex items-center gap-2"><span className="font-medium">{label}</span><Badge variant={result.success ? "default" : "destructive"}>{result.success ? "可用" : result.error}</Badge></div><p className="mt-1 break-words font-mono text-muted-foreground">DNS {result.dnsMs} · TCP {result.tcpMs} · TLS {result.tlsMs} · HTTP {result.httpMs} · 总计 {result.totalMs} ms</p><p className="mt-1 text-muted-foreground">成功率 {(result.successRate * 100).toFixed(0)}% / {result.sampleCount} 样本{result.httpStatus ? ` · HTTP ${result.httpStatus}` : ""}</p></div>
}

function AutoSettings({ settings, onChange }: { settings: ProxyAutoSettings; onChange: (settings: ProxyAutoSettings) => void }) {
  const fields: Array<[keyof ProxyAutoSettings, string, string]> = [["sampleSize", "样本数", ""], ["timeoutMs", "超时", "ms"], ["switchThresholdMs", "切换阈值", "ms"], ["hysteresisMs", "滞回", "ms"], ["cooldownSeconds", "冷却", "s"]]
  return <div className="grid gap-4 border-t bg-muted/20 px-5 py-4 sm:grid-cols-2 xl:grid-cols-5">{fields.map(([key, label, unit]) => <div key={key} className="space-y-1.5"><Label htmlFor={`auto-${key}`} className="text-xs">AUTO {label}</Label><div className="relative"><Input id={`auto-${key}`} type="number" value={settings[key]} onChange={(event) => onChange({ ...settings, [key]: Number(event.target.value) })} className={unit ? "pr-10" : ""} />{unit && <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">{unit}</span>}</div></div>)}</div>
}
