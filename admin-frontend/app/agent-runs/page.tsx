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
import type { AdminAgentRunDetail, AdminAgentRunListItem, AdminAgentRunStats } from "@/lib/api/types"
import { Bot, Eye, RefreshCw, Search, Square } from "lucide-react"

function formatDateTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toISOString().replace("T", " ").slice(0, 19)
}

function statusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  const upper = status.toUpperCase()
  if (upper === "SUCCEEDED" || upper === "SUCCESS" || upper === "COMPLETED") return "default"
  if (upper === "FAILED" || upper === "CANCELLED") return "destructive"
  if (upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING") return "secondary"
  return "outline"
}

function isCancellable(status: string) {
  const upper = status.toUpperCase()
  return upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING" || upper === "QUEUED"
}

function parseJson(value?: string | null) {
  if (!value) return ""
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
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
  }, [statusFilter])

  const filteredRuns = useMemo(() => {
    const q = keyword.trim().toLowerCase()
    if (!q) return runs
    return runs.filter((run) =>
      [
        String(run.id),
        String(run.userId),
        run.intent || "",
        run.modelName || "",
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
            ["总运行", stats?.totalRuns ?? 0],
            ["运行中", stats?.activeRuns ?? 0],
            ["失败", stats?.failedRuns ?? 0],
            ["消耗算力", stats?.totalConsumedCredits ?? 0],
          ].map(([label, value]) => (
            <Card key={label}>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm text-muted-foreground">{label}</CardTitle>
              </CardHeader>
              <CardContent className="text-2xl font-semibold">{value}</CardContent>
            </Card>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="relative min-w-72 flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              placeholder="搜索 run id、用户、意图、模型..."
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
          </div>
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="RUNNING">运行中</SelectItem>
              <SelectItem value="SUCCEEDED">成功</SelectItem>
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
            <span>意图/模型</span>
            <span>事件/工具</span>
            <span>算力</span>
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
                  <Button size="icon" variant="ghost" onClick={() => openDetail(run)}>
                    <Eye className="h-4 w-4" />
                  </Button>
                  {isCancellable(run.status) ? (
                    <Button size="icon" variant="ghost" onClick={() => cancelRun(run)} disabled={actionId === run.id}>
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
        <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
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
              <div className="grid gap-3 rounded-lg border p-4 md:grid-cols-3">
                <div><p className="text-xs text-muted-foreground">状态</p><Badge variant={statusVariant(detail.run.status)}>{detail.run.status}</Badge></div>
                <div><p className="text-xs text-muted-foreground">模型</p><p className="font-medium">{detail.run.modelName || "-"}</p></div>
                <div><p className="text-xs text-muted-foreground">算力</p><p className="font-medium">{detail.run.consumedCredits ?? 0}</p></div>
              </div>

              {detail.run.errorMessage ? (
                <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
                  {detail.run.errorCode ? `${detail.run.errorCode}: ` : ""}{detail.run.errorMessage}
                </div>
              ) : null}

              <section className="space-y-2">
                <h3 className="font-semibold">工具调用</h3>
                {detail.toolCalls.length === 0 ? (
                  <p className="text-sm text-muted-foreground">暂无工具调用</p>
                ) : detail.toolCalls.map((call) => (
                  <div key={call.id} className="rounded-lg border p-3">
                    <div className="flex items-center justify-between">
                      <p className="font-medium">{call.toolName || call.toolCode}</p>
                      <Badge variant={statusVariant(call.status)}>{call.status}</Badge>
                    </div>
                    <pre className="mt-2 whitespace-pre-wrap rounded bg-muted p-3 text-xs">{parseJson(call.argumentsJson || call.resultJson || call.errorMessage)}</pre>
                  </div>
                ))}
              </section>

              <section className="space-y-2">
                <h3 className="font-semibold">事件流</h3>
                {detail.events.map((event) => (
                  <div key={event.id} className="rounded-lg border p-3 text-sm">
                    <div className="flex items-center justify-between gap-3">
                      <Badge variant="outline">{event.eventType}</Badge>
                      <span className="text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
                    </div>
                    <p className="mt-2 whitespace-pre-wrap">{event.eventText || parseJson(event.eventJson) || "-"}</p>
                  </div>
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
