"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  cancelAdminAgentRun,
  fetchAdminAgentRunDetail,
  fetchAdminAgentRuns,
  fetchAdminAgentRunStats,
} from "@/lib/api/agent-runs"
import { ApiError } from "@/lib/api/http"
import type {
  AdminAgentRunDetail,
  AdminAgentRunListItem,
  AdminAgentRunStats,
  AgentRunEvent,
  AgentToolCall,
} from "@/lib/api/types"
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Database,
  ExternalLink,
  Eye,
  RefreshCw,
  Search,
  Square,
  Wrench,
} from "lucide-react"

type DecisionSignalView = {
  source: string
  verdict: string
  confidence: number | null
  reason: string
}

function formatDateTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", { hour12: false })
}

function statusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  const upper = status.toUpperCase()
  if (upper === "SUCCEEDED" || upper === "SUCCESS" || upper === "COMPLETED") return "default"
  if (upper === "FAILED" || upper === "CANCELLED" || upper === "TIMEOUT") return "destructive"
  if (upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING" || upper === "QUEUED") return "secondary"
  return "outline"
}

function isCancellable(status: string) {
  const upper = status.toUpperCase()
  return upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING" || upper === "QUEUED"
}

function parseJsonValue(value?: string | null): unknown {
  if (!value) return null
  try {
    const parsed = JSON.parse(value)
    if (typeof parsed === "string") return JSON.parse(parsed)
    return parsed
  } catch {
    return value
  }
}

function prettyJson(value?: string | null) {
  const parsed = parseJsonValue(value)
  if (parsed == null || parsed === "") return ""
  return typeof parsed === "string" ? parsed : JSON.stringify(parsed, null, 2)
}

function objectPayload(value?: string | null): Record<string, unknown> {
  const parsed = parseJsonValue(value)
  return parsed && typeof parsed === "object" && !Array.isArray(parsed)
    ? parsed as Record<string, unknown>
    : {}
}

function textValue(value: unknown) {
  return typeof value === "string" || typeof value === "number" ? String(value) : ""
}

function taskIdFromCall(call: AgentToolCall) {
  if (call.taskId != null) return String(call.taskId)
  const payload = objectPayload(call.resultJson)
  return textValue(payload.taskId || payload.task_id)
}

function errorKind(errorCode?: string | null, errorMessage?: string | null) {
  const code = (errorCode || "").toUpperCase()
  const message = (errorMessage || "").toLowerCase()
  if (code.includes("RISK_CONTROL") || message.includes("risk control") || message.includes("风控") || message.includes("safety")) return "第三方风控"
  if (message.includes("invalid token") || message.includes("api key") || code.includes("UNAUTHORIZED") || code.includes("401")) return "模型/供应商凭证"
  if (code.includes("RATE_LIMIT") || code.includes("429") || message.includes("rate limit") || message.includes("too many requests")) return "限流"
  if (code.includes("TIMEOUT") || message.includes("timeout") || message.includes("timed out")) return "超时"
  if (code.includes("MEDIA") || message.includes("save media") || message.includes("upload")) return "媒体保存"
  if (code.includes("BACKEND") || code.includes("AGENT_SERVICE_NOTIFY") || message.includes("502") || message.includes("bad gateway")) return "系统链路"
  if (code.includes("TOOL") || code.includes("TASK")) return "工具任务"
  if (code.includes("MODEL")) return "模型调用"
  return "未分类"
}

function runDuration(startedAt?: string | null, finishedAt?: string | null) {
  if (!startedAt) return "-"
  const start = new Date(startedAt).getTime()
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "-"
  const seconds = Math.round((end - start) / 1000)
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

function decisionSignalsFromEvent(event: AgentRunEvent): DecisionSignalView[] {
  const payload = objectPayload(event.eventJson)
  const rawSignals = payload.decisionSignals
  if (!Array.isArray(rawSignals)) return []
  return rawSignals
    .map((item) => {
      if (!item || typeof item !== "object" || Array.isArray(item)) return null
      const record = item as Record<string, unknown>
      const source = textValue(record.source)
      const verdict = textValue(record.verdict)
      const reason = textValue(record.reason)
      const confidence = typeof record.confidence === "number" ? record.confidence : null
      if (!source && !verdict && !reason) return null
      return { source, verdict, confidence, reason }
    })
    .filter((item): item is DecisionSignalView => item != null)
}

function decisionSignalGroups(events: AgentRunEvent[]) {
  return events
    .map((event) => ({
      event,
      payload: objectPayload(event.eventJson),
      signals: decisionSignalsFromEvent(event),
    }))
    .filter((item) => item.signals.length > 0)
}

function eventTone(event: AgentRunEvent) {
  const payload = objectPayload(event.eventJson)
  if (event.eventType === "run.failed" || event.eventType.endsWith(".failed") || payload.errorCode) return "destructive" as const
  if (event.eventType === "run.completed" || event.eventType.endsWith(".completed") || payload.status === "SUCCESS") return "default" as const
  if (event.eventType.includes("tool")) return "secondary" as const
  return "outline" as const
}

function eventTitle(event: AgentRunEvent) {
  const payload = objectPayload(event.eventJson)
  const toolCode = textValue(payload.toolCode)
  const taskId = textValue(payload.taskId || payload.task_id)
  if (event.eventType === "tool.task_dispatched") return taskId ? `任务已下发 · ${toolCode || "工具"} · #${taskId}` : `任务已下发 · ${toolCode || "工具"}`
  if (event.eventType === "tool.task_progress") return `任务进度 · ${toolCode || "工具"}`
  if (event.eventType === "tool.finished") return payload.errorCode ? `工具失败 · ${toolCode || "工具"}` : `工具完成 · ${toolCode || "工具"}`
  if (event.eventType === "run.failed") return "运行失败"
  if (event.eventType === "run.completed") return "运行完成"
  if (event.eventType === "intent.detected") return "意图决策"
  if (event.eventType === "message.delta") return "流式片段"
  if (event.eventType === "message.completed") return "回复完成"
  if (event.eventType === "tool_call.loop_started") return "Tool-call loop started"
  if (event.eventType === "tool_call.requested") return `Tool requested · ${textValue(payload.name) || "tool"}`
  if (event.eventType === "tool_call.executed") return `Tool executed · ${textValue(payload.name) || "tool"}`
  if (event.eventType === "tool_call.rejected") return `Tool rejected · ${textValue(payload.name) || "tool"}`
  if (event.eventType === "tool_call.loop_completed") return "Tool-call loop completed"
  return event.eventType
}

function DecisionSignals({ signals }: { signals: DecisionSignalView[] }) {
  if (!signals.length) return null
  return (
    <div className="mt-3 grid gap-2">
      {signals.map((signal, index) => (
        <div key={`${signal.source}-${index}`} className="rounded-md border bg-muted/30 p-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">{signal.source || "unknown"}</Badge>
            <Badge variant={signal.verdict === "tool_use" ? "secondary" : "outline"}>{signal.verdict || "-"}</Badge>
            {signal.confidence != null ? (
              <span className="text-xs text-muted-foreground">confidence {(signal.confidence * 100).toFixed(0)}%</span>
            ) : null}
          </div>
          {signal.reason ? <p className="mt-2 text-xs text-muted-foreground">{signal.reason}</p> : null}
        </div>
      ))}
    </div>
  )
}

function DecisionSignalPanel({ events }: { events: AgentRunEvent[] }) {
  const groups = decisionSignalGroups(events)
  if (!groups.length) {
    return (
      <section className="space-y-2">
        <h3 className="font-semibold">决策轨迹</h3>
        <p className="text-sm text-muted-foreground">暂无决策信号。旧运行可能没有写入 decisionSignals。</p>
      </section>
    )
  }

  return (
    <section className="space-y-2">
      <h3 className="font-semibold">决策轨迹</h3>
      {groups.map(({ event, payload, signals }) => (
        <div key={event.id} className="rounded-lg border p-3 text-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <Badge variant="outline">{event.eventType}</Badge>
              <span className="font-medium">{textValue(payload.intent) || "unknown"}</span>
              {textValue(payload.selectedToolCode) ? (
                <span className="text-muted-foreground">tool {textValue(payload.selectedToolCode)}</span>
              ) : null}
            </div>
            <span className="text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
          </div>
          {textValue(payload.reason) ? <p className="mt-2 text-muted-foreground">{textValue(payload.reason)}</p> : null}
          <DecisionSignals signals={signals} />
        </div>
      ))}
    </section>
  )
}

function MemoryTracePanel({ events }: { events: AgentRunEvent[] }) {
  const memoryEvents = events.filter((event) => event.eventType.startsWith("memory."))
  if (!memoryEvents.length) {
    return null
  }
  return (
    <section className="space-y-2">
      <h3 className="flex items-center gap-2 font-semibold"><Database className="h-4 w-4" />长期记忆轨迹</h3>
      <div className="grid gap-2">
        {memoryEvents.map((event) => {
          const payload = objectPayload(event.eventJson)
          return (
            <div key={event.id} className="rounded-lg border bg-muted/20 p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="outline">{event.eventType}</Badge>
                <span className="text-muted-foreground">{formatDateTime(event.createdAt)}</span>
              </div>
              <div className="mt-2 grid gap-1 text-muted-foreground">
                {payload.action ? <div>动作：{textValue(payload.action)}</div> : null}
                {payload.memory_id ? <div>记忆 ID：{textValue(payload.memory_id)}</div> : null}
                {payload.memory_type ? <div>类型：{textValue(payload.memory_type)}</div> : null}
                {payload.count ? <div>注入数量：{textValue(payload.count)}</div> : null}
                {payload.title ? <div className="text-foreground">标题：{textValue(payload.title)}</div> : null}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}

function ToolCallTracePanel({ events }: { events: AgentRunEvent[] }) {
  const toolCallEvents = events.filter((event) => event.eventType.startsWith("tool_call."))
  if (!toolCallEvents.length) {
    return null
  }
  return (
    <section className="space-y-2">
      <h3 className="flex items-center gap-2 font-semibold"><Wrench className="h-4 w-4" />Tool-call loop</h3>
      <div className="grid gap-2">
        {toolCallEvents.map((event) => {
          const payload = objectPayload(event.eventJson)
          return (
            <div key={event.id} className="rounded-lg border bg-muted/20 p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant={event.eventType === "tool_call.rejected" ? "destructive" : "outline"}>{event.eventType}</Badge>
                <span className="text-muted-foreground">{formatDateTime(event.createdAt)}</span>
              </div>
              <div className="mt-2 grid gap-1 text-muted-foreground">
                {payload.name ? <div>Tool: {textValue(payload.name)}</div> : null}
                {payload.id ? <div>Call ID: {textValue(payload.id)}</div> : null}
                {payload.reason ? <div>Reason: {textValue(payload.reason)}</div> : null}
                {payload.executedToolCalls ? <div>Executed: {textValue(payload.executedToolCalls)}</div> : null}
                {payload.iterations ? <div>Iterations: {textValue(payload.iterations)}</div> : null}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}

function EventRow({ event }: { event: AgentRunEvent }) {
  const [open, setOpen] = useState(false)
  const payload = objectPayload(event.eventJson)
  const detail = textValue(payload.errorMessage || payload.progressMessage || payload.reason || payload.taskDescription) || event.eventText || ""
  const json = prettyJson(event.eventJson)
  const signals = decisionSignalsFromEvent(event)

  return (
    <div className="rounded-lg border p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <Badge variant={eventTone(event)}>{event.eventType}</Badge>
          <span className="truncate font-medium">{eventTitle(event)}</span>
        </div>
        <span className="text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
      </div>
      {detail ? <p className="mt-2 whitespace-pre-wrap text-muted-foreground">{detail}</p> : null}
      <DecisionSignals signals={signals} />
      {json ? (
        <>
          <button
            type="button"
            className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => setOpen((value) => !value)}
          >
            <ChevronDown className={open ? "h-3 w-3 rotate-180" : "h-3 w-3"} />
            JSON 详情
          </button>
          {open ? <pre className="mt-2 max-h-72 overflow-auto rounded bg-muted p-3 text-xs">{json}</pre> : null}
        </>
      ) : null}
    </div>
  )
}

function ToolCallCard({ call }: { call: AgentToolCall }) {
  const [open, setOpen] = useState(false)
  const taskId = taskIdFromCall(call)
  const errorLabel = call.errorMessage || call.errorCode ? errorKind(call.errorCode, call.errorMessage) : ""

  return (
    <div className="rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{call.toolName || call.toolCode}</p>
          <p className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <span>call #{call.id}</span>
            {taskId ? (
              <>
                <span>/</span>
                <a
                  className="inline-flex items-center gap-1 text-primary hover:underline"
                  href={`/tasks?taskId=${encodeURIComponent(taskId)}`}
                >
                  task #{taskId}
                  <ExternalLink className="h-3 w-3" />
                </a>
              </>
            ) : null}
            <span>/ {runDuration(call.startedAt || call.createdAt, call.finishedAt)}</span>
          </p>
        </div>
        <div className="flex items-center gap-2">
          {errorLabel ? <Badge variant="outline">{errorLabel}</Badge> : null}
          <Badge variant={statusVariant(call.status)}>{call.status}</Badge>
        </div>
      </div>
      {call.errorMessage ? (
        <div className="mt-3 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
          {call.errorCode ? `${call.errorCode}: ` : ""}{call.errorMessage}
        </div>
      ) : null}
      <button
        type="button"
        className="mt-3 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        onClick={() => setOpen((value) => !value)}
      >
        <ChevronDown className={open ? "h-3 w-3 rotate-180" : "h-3 w-3"} />
        参数与结果
      </button>
      {open ? (
        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">参数</p>
            <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-xs">{prettyJson(call.argumentsJson) || "-"}</pre>
          </div>
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">结果</p>
            <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-xs">{prettyJson(call.resultJson) || "-"}</pre>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export function AgentRunsContent() {
  const [runs, setRuns] = useState<AdminAgentRunListItem[]>([])
  const [stats, setStats] = useState<AdminAgentRunStats | null>(null)
  const [detail, setDetail] = useState<AdminAgentRunDetail | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [actionId, setActionId] = useState<number | null>(null)
  const [statusFilter, setStatusFilter] = useState("all")
  const [taskIdFilter, setTaskIdFilter] = useState("")
  const [error, setError] = useState<string | null>(null)

  const queryTaskId = useMemo(() => {
    const parsed = Number(taskIdFilter.trim())
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }, [taskIdFilter])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const query = {
        pageNo: 1,
        pageSize: 50,
        status: statusFilter === "all" ? undefined : statusFilter,
        taskId: queryTaskId,
      }
      const [runPage, nextStats] = await Promise.all([
        fetchAdminAgentRuns(query),
        fetchAdminAgentRunStats(),
      ])
      setRuns(runPage.list)
      setStats(nextStats)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Agent Run 失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, queryTaskId])

  async function openDetail(run: AdminAgentRunListItem) {
    setDetailOpen(true)
    setDetailLoading(true)
    setDetail(null)
    try {
      setDetail(await fetchAdminAgentRunDetail(run.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Run 详情失败")
    } finally {
      setDetailLoading(false)
    }
  }

  async function cancelRun(run: AdminAgentRunListItem) {
    setActionId(run.id)
    try {
      await cancelAdminAgentRun(run.id)
      await load()
      if (detail?.run.id === run.id) setDetail(await fetchAdminAgentRunDetail(run.id))
    } finally {
      setActionId(null)
    }
  }

  return (
    <>
      <AdminHeader
        title="Agent Runs"
        subtitle="查看 Agent 决策轨迹、工具调用、任务关联和失败分类"
      />
      <div className="space-y-6 p-6">
        <div className="grid gap-4 md:grid-cols-4">
          {[
            ["总运行", stats?.totalRuns ?? 0, Bot],
            ["运行中", stats?.activeRuns ?? 0, Clock3],
            ["失败", stats?.failedRuns ?? 0, AlertTriangle],
            ["工具调用", stats?.toolCalls ?? 0, Wrench],
          ].map(([label, value, Icon]) => (
            <Card key={String(label)}>
              <CardHeader className="pb-2">
                <CardTitle className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Icon className="h-4 w-4" />
                  {label as string}
                </CardTitle>
              </CardHeader>
              <CardContent className="text-2xl font-semibold">{value as number}</CardContent>
            </Card>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="w-44">
              <SelectValue placeholder="运行状态" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="RUNNING">运行中</SelectItem>
              <SelectItem value="COMPLETED">已完成</SelectItem>
              <SelectItem value="FAILED">失败</SelectItem>
              <SelectItem value="CANCELLED">已取消</SelectItem>
              <SelectItem value="TIMEOUT">超时</SelectItem>
            </SelectContent>
          </Select>
          <div className="relative w-64">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              inputMode="numeric"
              placeholder="按 taskId 搜索"
              value={taskIdFilter}
              onChange={(event) => setTaskIdFilter(event.target.value)}
            />
          </div>
          <Button variant="outline" onClick={load} disabled={loading}>
            <RefreshCw className={loading ? "mr-2 h-4 w-4 animate-spin" : "mr-2 h-4 w-4"} />
            刷新
          </Button>
        </div>

        {error ? (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">{error}</div>
        ) : null}

        <Card>
          <CardHeader>
            <CardTitle>运行列表</CardTitle>
          </CardHeader>
          <CardContent>
            {loading ? (
              <p className="text-sm text-muted-foreground">加载中...</p>
            ) : runs.length === 0 ? (
              <p className="text-sm text-muted-foreground">暂无 Agent Run</p>
            ) : (
              <div className="divide-y rounded-lg border">
                {runs.map((run) => (
                  <div key={run.id} className="grid gap-3 p-4 text-sm md:grid-cols-[minmax(0,1fr)_auto_auto_auto] md:items-center">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">Run #{run.id}</span>
                        <Badge variant={statusVariant(run.status)}>{run.status}</Badge>
                        {run.intent ? <Badge variant="outline">{run.intent}</Badge> : null}
                        {run.errorMessage ? <Badge variant="destructive">{errorKind(run.errorCode, run.errorMessage)}</Badge> : null}
                      </div>
                      <p className="mt-1 truncate text-muted-foreground">
                        user #{run.userId} / session #{run.sessionId} / {run.modelName || run.modelProviderCode || "-"}
                      </p>
                      {run.errorMessage ? <p className="mt-1 truncate text-destructive">{run.errorMessage}</p> : null}
                    </div>
                    <span className="text-muted-foreground">{formatDateTime(run.createdAt)}</span>
                    <span className="text-muted-foreground">
                      {run.eventCount ?? 0} events / {run.toolCallCount ?? 0} tools
                    </span>
                    <div className="flex gap-2">
                      <Button size="icon" variant="ghost" onClick={() => openDetail(run)} aria-label="查看详情">
                        <Eye className="h-4 w-4" />
                      </Button>
                      {isCancellable(run.status) ? (
                        <Button size="icon" variant="ghost" onClick={() => cancelRun(run)} disabled={actionId === run.id} aria-label="取消运行">
                          <Square className="h-4 w-4" />
                        </Button>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
        <DialogContent className="max-h-[90vh] max-w-5xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Agent Run 详情</DialogTitle>
            <DialogDescription>
              后台展示完整决策、工具和任务诊断；用户侧保持轻量提示。
            </DialogDescription>
          </DialogHeader>
          {detailLoading ? <p className="text-sm text-muted-foreground">加载中...</p> : null}
          {detail ? (
            <div className="space-y-5">
              <div className="grid gap-3 rounded-lg border p-4 md:grid-cols-4">
                <div>
                  <p className="text-xs text-muted-foreground">状态</p>
                  <Badge variant={statusVariant(detail.run.status)}>{detail.run.status}</Badge>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">模型</p>
                  <p className="font-medium">{detail.run.modelName || detail.run.modelProviderCode || "-"}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">耗时</p>
                  <p className="font-medium">{runDuration(detail.run.startedAt || detail.run.createdAt, detail.run.finishedAt)}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">算力</p>
                  <p className="font-medium">{detail.run.consumedCredits ?? 0} / {detail.run.estimatedCredits ?? "-"}</p>
                </div>
              </div>

              {detail.run.errorMessage ? (
                <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
                  <div className="mb-2 flex items-center gap-2">
                    <AlertTriangle className="h-4 w-4" />
                    <span className="font-medium">{errorKind(detail.run.errorCode, detail.run.errorMessage)}</span>
                    {detail.run.errorCode ? <Badge variant="outline">{detail.run.errorCode}</Badge> : null}
                  </div>
                  <p className="whitespace-pre-wrap">{detail.run.errorMessage}</p>
                </div>
              ) : (
                <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 p-4 text-sm text-emerald-700">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 className="h-4 w-4" />
                    当前运行没有记录错误。
                  </div>
                </div>
              )}

              <DecisionSignalPanel events={detail.events} />
              <ToolCallTracePanel events={detail.events} />
              <MemoryTracePanel events={detail.events} />

              <section className="space-y-2">
                <h3 className="font-semibold">工具调用</h3>
                {detail.toolCalls.length === 0 ? (
                  <p className="text-sm text-muted-foreground">暂无工具调用</p>
                ) : detail.toolCalls.map((call) => (
                  <ToolCallCard key={call.id} call={call} />
                ))}
              </section>

              <section className="space-y-2">
                <h3 className="font-semibold">事件流</h3>
                {detail.events.map((event) => (
                  <EventRow key={event.id} event={event} />
                ))}
              </section>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  )
}

export default function AgentRunsPage() {
  return (
    <AdminLayout>
      <AgentRunsContent />
    </AdminLayout>
  )
}
