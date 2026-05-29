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
import type { AdminAgentRunDetail, AdminAgentRunListItem, AdminAgentRunStats, AgentRunEvent, AgentToolCall } from "@/lib/api/types"
import { AlertTriangle, Bot, CheckCircle2, ChevronDown, Clock3, ExternalLink, Eye, RefreshCw, Search, Square, Wrench } from "lucide-react"

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
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
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
  if (message.includes("invalid token") || message.includes("api key") || code.includes("UNAUTHORIZED") || code.includes("401")) return "模型/供应商凭证"
  if (code.includes("TIMEOUT") || message.includes("timeout")) return "超时"
  if (code.includes("BACKEND") || message.includes("502") || message.includes("bad gateway")) return "后端链路"
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
  return event.eventType
}

function EventRow({ event }: { event: AgentRunEvent }) {
  const [open, setOpen] = useState(false)
  const payload = objectPayload(event.eventJson)
  const detail = textValue(payload.errorMessage || payload.progressMessage || payload.reason || payload.taskDescription) || event.eventText || ""
  const json = prettyJson(event.eventJson)

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
  const [keyword, setKeyword] = useState("")
  const [error, setError] = useState<string | null>(null)

  async function loadRuns() {
    setLoading(true)
    setError(null)
    try {
      const [listResp, statResp] = await Promise.all([
        fetchAdminAgentRuns({
          pageNo: 1,
          pageSize: 50,
          status: statusFilter === "all" ? undefined : statusFilter,
          taskId: /^\d+$/.test(taskIdFilter.trim()) ? Number(taskIdFilter.trim()) : undefined,
        }),
        fetchAdminAgentRunStats().catch(() => null),
      ])
      setRuns(listResp.list)
      setStats(statResp)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Agent 运行记录失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadRuns()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, taskIdFilter])

  useEffect(() => {
    if (typeof window === "undefined") return
    const taskId = new URLSearchParams(window.location.search).get("taskId")
    if (taskId && /^\d+$/.test(taskId)) setTaskIdFilter(taskId)
  }, [])

  const filteredRuns = useMemo(() => {
    const q = keyword.trim().toLowerCase()
    if (!q) return runs
    return runs.filter((run) =>
      [
        String(run.id),
        String(run.userId),
        run.intent || "",
        run.modelName || "",
        run.errorCode || "",
        run.errorMessage || "",
      ].some((item) => item.toLowerCase().includes(q)),
    )
  }, [runs, keyword])

  async function openDetail(run: AdminAgentRunListItem) {
    setDetailOpen(true)
    setDetail(null)
    setDetailLoading(true)
    try {
      setDetail(await fetchAdminAgentRunDetail(run.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Agent 运行详情失败")
    } finally {
      setDetailLoading(false)
    }
  }

  async function cancelRun(run: AdminAgentRunListItem) {
    setActionId(run.id)
    try {
      await cancelAdminAgentRun(run.id)
      await loadRuns()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "取消 Agent 运行失败"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setActionId(null)
    }
  }

  return (
    <>
      <div className="space-y-6">
        {error ? (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
            {error}
          </div>
        ) : null}

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
          <div className="relative min-w-72 flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              placeholder="搜索 run id、用户、意图、模型、错误信息"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
          </div>
          <Input
            className="w-40"
            placeholder="Task ID"
            value={taskIdFilter}
            onChange={(event) => setTaskIdFilter(event.target.value.replace(/\D/g, ""))}
          />
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="RUNNING">运行中</SelectItem>
              <SelectItem value="SUCCESS">成功</SelectItem>
              <SelectItem value="FAILED">失败</SelectItem>
              <SelectItem value="CANCELLED">已取消</SelectItem>
            </SelectContent>
          </Select>
          <Button variant="outline" className="gap-2" onClick={loadRuns} disabled={loading}>
            <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
            刷新
          </Button>
        </div>

        <div className="overflow-hidden rounded-xl border bg-card">
          <div className="grid grid-cols-[90px_90px_120px_1fr_160px_120px_130px] gap-3 border-b bg-muted/40 px-4 py-3 text-xs font-medium text-muted-foreground">
            <span>Run ID</span>
            <span>用户</span>
            <span>状态</span>
            <span>意图 / 模型</span>
            <span>事件 / 工具</span>
            <span>消耗</span>
            <span>操作</span>
          </div>
          {loading ? (
            <div className="p-8 text-center text-sm text-muted-foreground">加载中...</div>
          ) : filteredRuns.length === 0 ? (
            <div className="p-8 text-center text-sm text-muted-foreground">暂无 Agent 运行记录</div>
          ) : (
            filteredRuns.map((run) => (
              <div
                key={run.id}
                className="grid grid-cols-[90px_90px_120px_1fr_160px_120px_130px] items-center gap-3 border-b px-4 py-3 text-sm last:border-b-0"
              >
                <span className="font-mono">#{run.id}</span>
                <span>用户 {run.userId}</span>
                <Badge variant={statusVariant(run.status)}>{run.status}</Badge>
                <div className="min-w-0">
                  <p className="truncate font-medium">{run.intent || "未识别意图"}</p>
                  <p className="truncate text-xs text-muted-foreground">{run.modelName || run.modelProviderCode || "-"}</p>
                  <p className="text-xs text-muted-foreground">{formatDateTime(run.createdAt)}</p>
                </div>
                <span className="text-muted-foreground">
                  {run.eventCount ?? 0} events / {run.toolCallCount ?? 0} tools
                </span>
                <span>{run.consumedCredits ?? 0} / {run.estimatedCredits ?? "-"}</span>
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
            ))
          )}
        </div>
      </div>

      <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
        <DialogContent className="max-h-[90vh] max-w-6xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Bot className="h-5 w-5" />
              Agent 运行详情
            </DialogTitle>
            <DialogDescription>
              {detailLoading ? "加载中..." : detail ? `运行 #${detail.run.id}` : "暂无详情"}
            </DialogDescription>
          </DialogHeader>
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
      <AdminHeader
        title="Agent 运行"
        description="查看 Agent 会话运行状态、工具调用、事件流和失败原因。"
      />
      <div className="p-6">
        <AgentRunsContent />
      </div>
    </AdminLayout>
  )
}
