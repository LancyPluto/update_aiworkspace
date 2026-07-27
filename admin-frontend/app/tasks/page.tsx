"use client"

import { Suspense, useEffect, useMemo, useState } from "react"
import { useSearchParams } from "next/navigation"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Search,
  Filter,
  Eye,
  Download,
  ExternalLink,
  RefreshCw,
  AlertCircle,
  CheckCircle,
  Clock,
  Music,
  XCircle,
} from "lucide-react"
import {
  fetchAdminTaskDetail,
  fetchAdminTasks,
  retryAdminTask,
} from "@/lib/api/tasks"
import { AgentRunsContent } from "@/app/agent-runs/page"
import { ApiError } from "@/lib/api/http"
import { formatShanghaiDateTime } from "@/lib/date-time"
import type { AdminTaskApiPayload } from "@/lib/api/types"

type TaskStatus = "active" | "pending" | "error" | "timeout"

interface Task {
  id: string
  rawId: number
  user: string
  tool: string
  status: TaskStatus
  statusLabel: string
  rawStatus: string
  /** 后端 TaskDetailResponse 未返回消耗字段时为 null，界面展示「—」 */
  credits: number | null
  input: string
  output: string
  outputResourceType: string
  error: string
  errorCode: string
  developerMessage: string
  failureTraceId: string
  errorMessage: string
  progressMessage: string
  agentSource?: AdminTaskApiPayload["agentSource"]
  createdAt: string
  queuedAt: string
  startedAt: string
  completedAt: string
  duration: string
}

function mapStatus(status: string): { status: TaskStatus; label: string } {
  switch (status) {
    case "SUCCESS":
      return { status: "active", label: "已完成" }
    case "QUEUED":
      return { status: "pending", label: "排队中" }
    case "PROCESSING":
      return { status: "pending", label: "生成中" }
    case "FAILED":
      return { status: "error", label: "失败" }
    case "TIMEOUT":
      return { status: "timeout", label: "超时" }
    case "CANCELLED":
      return { status: "error", label: "已取消" }
    default:
      return { status: "pending", label: status || "未知" }
  }
}

function computeDuration(startIso?: string | null, endIso?: string | null): string {
  if (!startIso || !endIso) return "-"
  const start = new Date(startIso).getTime()
  const end = new Date(endIso).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "-"
  const ms = end - start
  if (ms < 60_000) return `${Math.round(ms / 1000)}秒`
  const min = Math.floor(ms / 60_000)
  const sec = Math.round((ms % 60_000) / 1000)
  return `${min}分${sec}秒`
}

function buildParamsText(params: unknown): string {
  if (params == null) return ""
  if (typeof params === "string") return params
  try {
    return JSON.stringify(params, null, 2)
  } catch {
    return String(params)
  }
}

/** Rolling-deployment fallback: new diagnostics use developerMessage, legacy tasks use error/progress fields. */
function taskFailureHint(row: AdminTaskApiPayload): string {
  const st = (row.status || "").toUpperCase()
  if (st !== "FAILED" && st !== "TIMEOUT" && st !== "CANCELLED") return ""
  return row.developerMessage?.trim() || row.errorMessage?.trim() || row.progressMessage?.trim() || ""
}

function isExceptionalTaskStatus(status: string): boolean {
  return ["FAILED", "TIMEOUT", "CANCELLED"].includes((status || "").toUpperCase())
}

function currentDialogTask(item: Task, selectedTask: Task | null): Task {
  return selectedTask?.rawId === item.rawId ? selectedTask : item
}

function taskDialogError(item: Task, selectedTask: Task | null): string {
  const current = currentDialogTask(item, selectedTask)
  if (!isExceptionalTaskStatus(current.rawStatus)) return ""
  return current.developerMessage?.trim()
    || current.errorMessage?.trim()
    || current.error?.trim()
    || current.progressMessage?.trim()
    || ""
}

function taskDialogHasDiagnostic(item: Task, selectedTask: Task | null): boolean {
  const current = currentDialogTask(item, selectedTask)
  return isExceptionalTaskStatus(current.rawStatus)
    && Boolean(taskDialogError(item, selectedTask) || current.failureTraceId)
}

function errorStatusLabel(status: string): string {
  switch ((status || "").toUpperCase()) {
    case "TIMEOUT":
      return "任务超时"
    case "CANCELLED":
      return "任务已取消"
    default:
      return "任务失败"
  }
}

function errorPanelTone(status: string) {
  return (status || "").toUpperCase() === "TIMEOUT"
    ? {
        wrap: "rounded-lg border border-amber-500/20 bg-amber-500/10 p-4",
        icon: "text-amber-600",
        title: "text-amber-700",
        code: "border-amber-500/20 bg-amber-500/10 text-amber-700",
      }
    : {
        wrap: "rounded-lg border border-destructive/20 bg-destructive/10 p-4",
        icon: "text-destructive",
        title: "text-destructive",
        code: "border-destructive/20 bg-destructive/10 text-destructive",
      }
}

function rowToTask(row: AdminTaskApiPayload): Task {
  const mapped = mapStatus(row.status)
  return {
    id: row.taskNo || `T${row.taskId}`,
    rawId: row.taskId,
    user: row.userId != null ? `用户 ${row.userId}` : "-",
    tool: row.toolName || row.toolCode,
    status: mapped.status,
    statusLabel: mapped.label,
    rawStatus: row.status,
    credits: row.consumedCredits ?? null,
    input: "",
    output: row.result?.contentText || "",
    outputResourceType: row.result?.resourceType || "",
    error: taskFailureHint(row),
    errorCode: row.errorCode?.trim() || "",
    developerMessage: row.developerMessage?.trim() || "",
    failureTraceId: row.failureTraceId?.trim() || "",
    errorMessage: row.errorMessage?.trim() || "",
    progressMessage: row.progressMessage?.trim() || "",
    agentSource: row.agentSource ?? null,
    createdAt: formatShanghaiDateTime(row.createdAt),
    queuedAt: formatShanghaiDateTime(row.queuedAt),
    startedAt: formatShanghaiDateTime(row.startedAt),
    completedAt: formatShanghaiDateTime(row.finishedAt),
    duration: computeDuration(row.startedAt ?? row.queuedAt ?? row.createdAt, row.finishedAt),
  }
}

interface AudioResultItem {
  url?: string
  sourceUrl?: string
  contentType?: string
}

interface AudioResultPayload {
  provider?: string
  model?: string
  audios?: AudioResultItem[]
  metadata?: Record<string, unknown>
}

function parseJsonObject(value: string): Record<string, unknown> | null {
  if (!value.trim()) return null
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function resolveMediaUrl(url?: string): string {
  if (!url) return ""
  if (/^https?:\/\//i.test(url)) return url
  const baseUrl = process.env.NEXT_PUBLIC_API_BASE_URL
    || (typeof window !== "undefined"
      && (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1")
      && window.location.port === "5174"
      ? "http://127.0.0.1:8080"
      : "")
  return `${baseUrl}${url.startsWith("/") ? url : `/${url}`}`
}

function formatBytes(value: unknown): string {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes <= 0) return "-"
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function formatAudioLength(value: unknown): string {
  const milliseconds = Number(value)
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) return "-"
  const seconds = milliseconds / 1000
  return seconds < 60 ? `${seconds.toFixed(1)} 秒` : `${Math.floor(seconds / 60)} 分 ${Math.round(seconds % 60)} 秒`
}

function renderTaskOutput(task?: Task | null) {
  const content = task?.output || ""
  const resourceType = (task?.outputResourceType || "").toUpperCase()
  const parsed = parseJsonObject(content) as AudioResultPayload | null
  const audios = Array.isArray(parsed?.audios) ? parsed.audios.filter((item) => item?.url) : []

  if ((resourceType === "AUDIO" || audios.length > 0) && audios.length > 0) {
    const metadata =
      parsed?.metadata && typeof parsed.metadata === "object" && !Array.isArray(parsed.metadata)
        ? (parsed.metadata as Record<string, unknown>)
        : {}
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <div className="rounded-lg bg-primary/10 p-2">
            <Music className="h-5 w-5 text-primary" />
          </div>
          <div>
            <p className="font-medium text-card-foreground">音频已生成</p>
            <p className="text-xs text-muted-foreground">
              {parsed?.provider || "TTS"}{parsed?.model ? ` · ${parsed.model}` : ""}
            </p>
          </div>
        </div>
        {audios.map((audio, index) => {
          const mediaUrl = resolveMediaUrl(audio.url)
          return (
            <div key={`${audio.url}-${index}`} className="rounded-lg border border-border bg-background p-4">
              <audio controls preload="metadata" className="w-full" src={mediaUrl}>
                当前浏览器不支持音频播放。
              </audio>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button asChild size="sm" variant="outline">
                  <a href={mediaUrl} download>
                    <Download className="mr-2 h-4 w-4" />
                    下载音频
                  </a>
                </Button>
                <Button asChild size="sm" variant="ghost">
                  <a href={mediaUrl} target="_blank" rel="noreferrer">
                    <ExternalLink className="mr-2 h-4 w-4" />
                    新窗口打开
                  </a>
                </Button>
              </div>
            </div>
          )
        })}
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div className="rounded-md bg-background p-3">
            <p className="text-muted-foreground">时长</p>
            <p className="font-medium">{formatAudioLength(metadata.audio_length)}</p>
          </div>
          <div className="rounded-md bg-background p-3">
            <p className="text-muted-foreground">文件大小</p>
            <p className="font-medium">{formatBytes(metadata.audio_size)}</p>
          </div>
          <div className="rounded-md bg-background p-3">
            <p className="text-muted-foreground">采样率</p>
            <p className="font-medium">{metadata.audio_sample_rate ? `${String(metadata.audio_sample_rate)} Hz` : "-"}</p>
          </div>
          <div className="rounded-md bg-background p-3">
            <p className="text-muted-foreground">计费字符</p>
            <p className="font-medium">{metadata.usage_characters == null ? "-" : String(metadata.usage_characters)}</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <pre className="min-w-0 whitespace-pre-wrap break-words text-sm">
      {content || "暂无输出"}
    </pre>
  )
}

function TasksPageContent() {
  const searchParams = useSearchParams()
  const runIdParam = searchParams.get("runId")
  const mainTab = runIdParam && /^\d+$/.test(runIdParam) ? "agent-runs" : "tasks"

  const [searchQuery, setSearchQuery] = useState("")
  const [statusFilter, setStatusFilter] = useState("all")
  const [selectedTask, setSelectedTask] = useState<Task | null>(null)
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [detailLoadingId, setDetailLoadingId] = useState<number | null>(null)
  const [actionId, setActionId] = useState<number | null>(null)

  const loadTasks = async () => {
    setLoading(true)
    setError(null)
    try {
      const taskIdParam = typeof window !== "undefined" ? new URLSearchParams(window.location.search).get("taskId") : null
      const taskId = taskIdParam && /^\d+$/.test(taskIdParam) ? Number(taskIdParam) : undefined
      if (taskIdParam && !searchQuery) setSearchQuery(taskIdParam)
      const resp = await fetchAdminTasks({ taskId })
      setTasks(resp.list.map(rowToTask))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载任务列表失败"
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadTasks()
  }, [])

  const filteredTasks = useMemo(() => {
    return tasks.filter((task) => {
      const matchesSearch =
        task.id.includes(searchQuery) ||
        task.user.includes(searchQuery) ||
        task.tool.includes(searchQuery)
      const matchesStatus =
        statusFilter === "all" ||
        (statusFilter === "active" && task.rawStatus === "SUCCESS") ||
        (statusFilter === "pending" && ["QUEUED", "PROCESSING", "RETRYING"].includes(task.rawStatus)) ||
        (statusFilter === "error" && ["FAILED", "CANCELLED"].includes(task.rawStatus)) ||
        (statusFilter === "timeout" && task.rawStatus === "TIMEOUT")
      return matchesSearch && matchesStatus
    })
  }, [tasks, searchQuery, statusFilter])

  const stats = useMemo(() => {
    const total = tasks.length
    const done = tasks.filter((t) => t.rawStatus === "SUCCESS").length
    const running = tasks.filter(
      (t) => t.rawStatus === "PROCESSING" || t.rawStatus === "QUEUED",
    ).length
    const failed = tasks.filter((t) => t.rawStatus === "FAILED" || t.rawStatus === "CANCELLED").length
    const timeout = tasks.filter((t) => t.rawStatus === "TIMEOUT").length
    return [
      { label: "全部任务", value: total, icon: Clock, color: "text-foreground" },
      { label: "已完成", value: done, icon: CheckCircle, color: "text-accent" },
      { label: "生成中", value: running, icon: RefreshCw, color: "text-chart-5" },
      { label: "失败", value: failed, icon: XCircle, color: "text-destructive" },
      { label: "超时", value: timeout, icon: AlertCircle, color: "text-amber-600" },
    ]
  }, [tasks])

  const openDetail = async (item: Task) => {
    setSelectedTask(item)
    setDetailLoadingId(item.rawId)
    try {
      const detail = await fetchAdminTaskDetail(item.rawId)
      const row = rowToTask(detail)
      setSelectedTask({
        ...item,
        rawStatus: row.rawStatus,
        status: row.status,
        statusLabel: row.statusLabel,
        input: buildParamsText(detail.params),
        output: detail.result?.contentText || "",
        outputResourceType: detail.result?.resourceType || "",
        error: taskFailureHint(detail) || item.error,
        errorCode: row.errorCode,
        developerMessage: row.developerMessage,
        failureTraceId: row.failureTraceId,
        errorMessage: row.errorMessage,
        progressMessage: row.progressMessage,
        agentSource: detail.agentSource ?? null,
        credits: row.credits,
        queuedAt: row.queuedAt,
        startedAt: row.startedAt,
        completedAt: row.completedAt,
        duration: row.duration,
      })
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载任务详情失败"
      setSelectedTask({ ...item, error: message })
    } finally {
      setDetailLoadingId(null)
    }
  }

  const handleRetry = async (item: Task) => {
    if (typeof window !== "undefined") {
      const ok = window.confirm(`确认重试任务 ${item.id} 吗？`)
      if (!ok) return
    }
    setActionId(item.rawId)
    try {
      await retryAdminTask(item.rawId)
      await loadTasks()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "重试失败"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setActionId(null)
    }
  }

  const taskColumns = [
    { key: "id" as const, title: "任务 ID" },
    { key: "user" as const, title: "用户" },
    { key: "tool" as const, title: "工具" },
    {
      key: "status" as const,
      title: "状态",
      render: (_: unknown, item: Task) => (
        <StatusBadge status={item.status} label={item.statusLabel} />
      ),
    },
    {
      key: "credits" as const,
      title: "消耗算力",
      render: (value: unknown) => (
        <span>{value === null || value === undefined ? "—" : `${value as number} 算力`}</span>
      ),
    },
    {
      key: "developerMessage" as const,
      title: "错误信息",
      render: (_: unknown, item: Task) => {
        if (!isExceptionalTaskStatus(item.rawStatus)) return <span className="text-muted-foreground">—</span>
        const message = taskDialogError(item, null)
        if (!message && !item.failureTraceId) return <span className="text-muted-foreground">—</span>
        return (
          <div className="max-w-[28rem] space-y-1 text-xs">
            {message ? <p className="whitespace-pre-wrap break-all text-destructive">{message}</p> : null}
            {item.failureTraceId ? (
              <p className="break-all font-mono text-[11px] text-muted-foreground">traceId: {item.failureTraceId}</p>
            ) : null}
          </div>
        )
      },
    },
    { key: "duration" as const, title: "耗时" },
    { key: "createdAt" as const, title: "创建时间" },
    {
      key: "actions" as const,
      title: "操作",
      render: (_: unknown, item: Task) => (
        <div className="flex items-center gap-2">
          <Dialog>
            <DialogTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8"
                onClick={() => openDetail(item)}
              >
                <Eye className="h-4 w-4" />
              </Button>
            </DialogTrigger>
            <DialogContent className="h-[min(760px,calc(100vh-2rem))] !max-w-[min(960px,calc(100vw-2rem))] grid-rows-[auto_minmax(0,1fr)_auto] overflow-hidden border-border bg-card">
              <DialogHeader className="shrink-0 pr-8">
                <DialogTitle>任务详情</DialogTitle>
                <DialogDescription>
                  {detailLoadingId === item.rawId ? (
                    "正在加载详情…"
                  ) : (
                    <span className="inline-flex flex-wrap items-center gap-1">
                      <span>任务 ID: {selectedTask?.id || item.id}</span>
                      {selectedTask?.agentSource ? (
                        <>
                          <span>·</span>
                          <a
                            className="text-primary hover:underline"
                            href={`/tasks?runId=${selectedTask.agentSource.runId}`}
                          >
                            Agent Run #{selectedTask.agentSource.runId}
                          </a>
                          <span>/ Tool Call #{selectedTask.agentSource.toolCallId}</span>
                        </>
                      ) : null}
                    </span>
                  )}
                </DialogDescription>
              </DialogHeader>
              <Tabs defaultValue="input" className="mt-4 flex min-h-0 flex-col overflow-hidden">
                <TabsList className="shrink-0 bg-secondary">
                  <TabsTrigger value="input">输入参数</TabsTrigger>
                  <TabsTrigger value="output">生成结果</TabsTrigger>
                  {taskDialogHasDiagnostic(item, selectedTask) && (
                    <TabsTrigger value="error">错误信息</TabsTrigger>
                  )}
                </TabsList>
                <TabsContent value="input" className="mt-4 min-h-0 flex-1 overflow-hidden data-[state=active]:flex">
                  <div className="h-full min-h-0 w-full min-w-0 overflow-auto overscroll-contain rounded-lg bg-secondary p-4">
                    <pre className="min-w-0 whitespace-pre-wrap break-words text-sm">
                      {selectedTask?.input || "—"}
                    </pre>
                  </div>
                </TabsContent>
                <TabsContent value="output" className="mt-4 min-h-0 flex-1 overflow-hidden data-[state=active]:flex">
                  <div className="h-full min-h-0 w-full min-w-0 overflow-auto overscroll-contain rounded-lg bg-secondary p-4">
                    {renderTaskOutput(selectedTask)}
                  </div>
                </TabsContent>
                {taskDialogHasDiagnostic(item, selectedTask) && (() => {
                  const current = currentDialogTask(item, selectedTask)
                  const tone = errorPanelTone(current.rawStatus)
                  const fullError = taskDialogError(item, selectedTask) || "-"
                  const summary = current.progressMessage || current.error || "-"
                  return (
                  <TabsContent value="error" className="mt-4 min-h-0 flex-1 overflow-hidden data-[state=active]:flex">
                    <div className={`${tone.wrap} h-full min-h-0 w-full min-w-0 overflow-hidden`}>
                      <div className="flex items-start gap-3">
                        <AlertCircle className={`mt-0.5 h-5 w-5 shrink-0 ${tone.icon}`} />
                        <div className="min-h-0 min-w-0 flex-1 space-y-3 overflow-auto overscroll-contain pr-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <p className={`font-medium ${tone.title}`}>
                              {errorStatusLabel(current.rawStatus)}
                            </p>
                            {current.errorCode && (
                              <span className={`max-w-full break-all rounded-full border px-2 py-0.5 text-xs font-medium ${tone.code}`}>
                                {current.errorCode}
                              </span>
                            )}
                          </div>
                          <div className="grid gap-3 text-sm sm:grid-cols-2">
                            <div className="min-w-0">
                              <p className="text-xs text-muted-foreground">当前状态</p>
                              <p className="mt-1 break-words font-medium">{current.statusLabel}</p>
                            </div>
                            <div className="min-w-0">
                              <p className="text-xs text-muted-foreground">结束时间</p>
                              <p className="mt-1 break-words font-medium">{current.completedAt || "-"}</p>
                            </div>
                          </div>
                          <div>
                            <p className="text-xs text-muted-foreground">阶段摘要</p>
                            <p className="mt-1 break-words text-sm text-card-foreground">{summary}</p>
                          </div>
                          <div>
                            <p className="text-xs text-muted-foreground">完整错误信息</p>
                            <pre className="mt-1 max-h-40 max-w-full overflow-auto whitespace-pre-wrap break-all rounded-md bg-background/80 p-3 text-xs text-card-foreground">
                              {fullError}
                            </pre>
                          </div>
                          {current.failureTraceId ? (
                            <div>
                              <p className="text-xs text-muted-foreground">Trace ID</p>
                              <code className="mt-1 block break-all rounded-md bg-background/80 p-2 text-xs text-card-foreground">
                                {current.failureTraceId}
                              </code>
                            </div>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  </TabsContent>
                  )
                })()}
              </Tabs>
              <div className="mt-4 grid shrink-0 grid-cols-2 gap-4 text-sm">
                <div>
                  <p className="text-muted-foreground">创建时间</p>
                  <p className="font-medium">
                    {selectedTask?.createdAt || item.createdAt}
                  </p>
                </div>
                <div>
                <div>
                  <p className="text-muted-foreground">????</p>
                  <p className="font-medium">
                    {selectedTask?.startedAt || item.startedAt}
                  </p>
                </div>
                  <p className="text-muted-foreground">完成时间</p>
                  <p className="font-medium">
                    {selectedTask?.completedAt || item.completedAt}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">消耗算力</p>
                  <p className="font-medium">
                    {selectedTask?.credits != null || item.credits != null
                      ? `${selectedTask?.credits ?? item.credits} 算力`
                      : "—"}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">执行耗时</p>
                  <p className="font-medium">
                    {selectedTask?.duration || item.duration}
                  </p>
                </div>
              </div>
            </DialogContent>
          </Dialog>
          {(item.rawStatus === "FAILED" || item.rawStatus === "TIMEOUT") && (
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              disabled={actionId === item.rawId}
              onClick={() => handleRetry(item)}
            >
              <RefreshCw className="h-4 w-4" />
            </Button>
          )}
        </div>
      ),
    },
  ]

  const headerDescription = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载任务列表..."
      : "查看和管理 AI 任务执行记录与 Agent 运行记录"

  return (
    <AdminLayout>
      <AdminHeader title="任务管理" description={headerDescription} />

      <div className="p-6 space-y-6">
        <Tabs defaultValue={mainTab} key={mainTab} className="space-y-6">
          <TabsList className="bg-secondary">
            <TabsTrigger value="tasks">AI 任务</TabsTrigger>
            <TabsTrigger value="agent-runs">Agent 运行</TabsTrigger>
          </TabsList>

          <TabsContent value="tasks" className="space-y-6">
            {/* Stats */}
            <div className="grid gap-4 md:grid-cols-5">
              {stats.map((stat) => (
                <div
                  key={stat.label}
                  className="rounded-xl border border-border bg-card p-4"
                >
                  <div className="flex items-center gap-3">
                    <div className="rounded-lg bg-secondary p-2">
                      <stat.icon className={`h-5 w-5 ${stat.color}`} />
                    </div>
                    <div>
                      <p className="text-2xl font-semibold text-card-foreground">
                        {stat.value.toLocaleString()}
                      </p>
                      <p className="text-sm text-muted-foreground">{stat.label}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* Filters */}
            <div className="flex items-center gap-4">
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  placeholder="搜索任务 ID、用户或工具..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-9 bg-secondary border-0"
                />
              </div>
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="w-40 bg-secondary border-0">
                  <Filter className="mr-2 h-4 w-4" />
                  <SelectValue placeholder="状态筛选" />
                </SelectTrigger>
                <SelectContent className="bg-card border-border">
                  <SelectItem value="all">全部状态</SelectItem>
                  <SelectItem value="active">已完成</SelectItem>
                  <SelectItem value="pending">生成中</SelectItem>
                  <SelectItem value="error">失败</SelectItem>
                  <SelectItem value="timeout">超时</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {/* Tasks Table */}
            <DataTable columns={taskColumns} data={filteredTasks} />
          </TabsContent>

          <TabsContent value="agent-runs">
            <AgentRunsContent />
          </TabsContent>
        </Tabs>
      </div>
    </AdminLayout>
  )
}

export default function TasksPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">正在加载任务页面...</div>}>
      <TasksPageContent />
    </Suspense>
  )
}
