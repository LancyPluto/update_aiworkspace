"use client"

import { useEffect, useMemo, useState } from "react"
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
  RefreshCw,
  AlertCircle,
  CheckCircle,
  Clock,
  XCircle,
} from "lucide-react"
import {
  fetchAdminTaskDetail,
  fetchAdminTasks,
  retryAdminTask,
} from "@/lib/api/tasks"
import { AgentRunsContent } from "@/app/agent-runs/page"
import { ApiError } from "@/lib/api/http"
import type { AdminTaskApiPayload } from "@/lib/api/types"

type TaskStatus = "active" | "pending" | "error"

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
  error: string
  createdAt: string
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
    case "CANCELLED":
      return { status: "error", label: "已取消" }
    default:
      return { status: "pending", label: status || "未知" }
  }
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return "-"
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toISOString().replace("T", " ").slice(0, 19)
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

/** 失败/取消时后端将原因写在 progressMessage */
function taskFailureHint(row: AdminTaskApiPayload): string {
  const st = (row.status || "").toUpperCase()
  if (st !== "FAILED" && st !== "CANCELLED") return ""
  return row.progressMessage?.trim() || ""
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
    credits: null,
    input: "",
    output: "",
    error: taskFailureHint(row),
    createdAt: formatDateTime(row.createdAt),
    completedAt: formatDateTime(row.finishedAt),
    duration: computeDuration(row.createdAt, row.finishedAt),
  }
}

export default function TasksPage() {
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
      const resp = await fetchAdminTasks()
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
        statusFilter === "all" || task.status === statusFilter
      return matchesSearch && matchesStatus
    })
  }, [tasks, searchQuery, statusFilter])

  const stats = useMemo(() => {
    const total = tasks.length
    const done = tasks.filter((t) => t.rawStatus === "SUCCESS").length
    const running = tasks.filter(
      (t) => t.rawStatus === "PROCESSING" || t.rawStatus === "QUEUED",
    ).length
    const failed = tasks.filter(
      (t) => t.rawStatus === "FAILED" || t.rawStatus === "CANCELLED",
    ).length
    return [
      { label: "全部任务", value: total, icon: Clock, color: "text-foreground" },
      { label: "已完成", value: done, icon: CheckCircle, color: "text-accent" },
      { label: "生成中", value: running, icon: RefreshCw, color: "text-chart-5" },
      { label: "失败", value: failed, icon: XCircle, color: "text-destructive" },
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
        input: buildParamsText(detail.params),
        output: detail.result?.contentText || "",
        error: taskFailureHint(detail) || item.error,
        credits: row.credits,
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
        <span>{value === null || value === undefined ? "—" : `${value as number} 点`}</span>
      ),
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
            <DialogContent className="bg-card border-border max-w-2xl">
              <DialogHeader>
                <DialogTitle>任务详情</DialogTitle>
                <DialogDescription>
                  {detailLoadingId === item.rawId
                    ? "正在加载详情…"
                    : `任务 ID: ${selectedTask?.id || item.id}`}
                </DialogDescription>
              </DialogHeader>
              <Tabs defaultValue="input" className="mt-4">
                <TabsList className="bg-secondary">
                  <TabsTrigger value="input">输入参数</TabsTrigger>
                  <TabsTrigger value="output">生成结果</TabsTrigger>
                  {(selectedTask?.error || item.error) && (
                    <TabsTrigger value="error">错误信息</TabsTrigger>
                  )}
                </TabsList>
                <TabsContent value="input" className="mt-4">
                  <div className="rounded-lg bg-secondary p-4">
                    <pre className="whitespace-pre-wrap text-sm">
                      {selectedTask?.input || "—"}
                    </pre>
                  </div>
                </TabsContent>
                <TabsContent value="output" className="mt-4">
                  <div className="rounded-lg bg-secondary p-4">
                    <pre className="whitespace-pre-wrap text-sm">
                      {selectedTask?.output || "暂无输出"}
                    </pre>
                  </div>
                </TabsContent>
                {(selectedTask?.error || item.error) && (
                  <TabsContent value="error" className="mt-4">
                    <div className="rounded-lg bg-destructive/10 border border-destructive/20 p-4">
                      <div className="flex items-start gap-3">
                        <AlertCircle className="h-5 w-5 text-destructive mt-0.5" />
                        <div>
                          <p className="font-medium text-destructive">
                            任务执行失败
                          </p>
                          <p className="mt-1 text-sm text-muted-foreground">
                            {selectedTask?.error || item.error}
                          </p>
                        </div>
                      </div>
                    </div>
                  </TabsContent>
                )}
              </Tabs>
              <div className="mt-4 grid grid-cols-2 gap-4 text-sm">
                <div>
                  <p className="text-muted-foreground">创建时间</p>
                  <p className="font-medium">
                    {selectedTask?.createdAt || item.createdAt}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">完成时间</p>
                  <p className="font-medium">
                    {selectedTask?.completedAt || item.completedAt}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">消耗算力</p>
                  <p className="font-medium">
                    {selectedTask?.credits != null || item.credits != null
                      ? `${selectedTask?.credits ?? item.credits} 点`
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
          {item.status === "error" && item.rawStatus === "FAILED" && (
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
        <Tabs defaultValue="tasks" className="space-y-6">
          <TabsList className="bg-secondary">
            <TabsTrigger value="tasks">AI 任务</TabsTrigger>
            <TabsTrigger value="agent-runs">Agent 运行</TabsTrigger>
          </TabsList>

          <TabsContent value="tasks" className="space-y-6">
            {/* Stats */}
            <div className="grid gap-4 md:grid-cols-4">
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
