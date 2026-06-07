"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useSearchParams } from "next/navigation"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { RunDetailTabs } from "@/components/admin/agent-run/RunDetailTabs"
import { RunDiagnosisHero } from "@/components/admin/agent-run/RunDiagnosisHero"
import { RunOverview } from "@/components/admin/agent-run/RunOverview"
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
import { diagnoseAgentRun, diagnoseRunListItem } from "@/lib/agent-run-diagnostics"
import { formatDateTime, statusVariant } from "@/lib/agent-run-utils"
import { ApiError } from "@/lib/api/http"
import type {
  AdminAgentRunDetail,
  AdminAgentRunListItem,
  AdminAgentRunStats,
} from "@/lib/api/types"
import {
  AlertTriangle,
  Bot,
  Clock3,
  Eye,
  RefreshCw,
  Search,
  Square,
  Wrench,
} from "lucide-react"

function isCancellable(status: string) {
  const upper = status.toUpperCase()
  return upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING" || upper === "QUEUED"
}

export function AgentRunsContent() {
  const searchParams = useSearchParams()
  const initialRunId = Number(searchParams.get("runId") || "")

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

  const diagnosis = useMemo(
    () => (detail ? diagnoseAgentRun(detail) : null),
    [detail],
  )

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

  const openDetailById = useCallback(async (runId: number) => {
    setDetailOpen(true)
    setDetailLoading(true)
    setDetail(null)
    try {
      setDetail(await fetchAdminAgentRunDetail(runId))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Run 详情失败")
    } finally {
      setDetailLoading(false)
    }
  }, [])

  async function openDetail(run: AdminAgentRunListItem) {
    await openDetailById(run.id)
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, queryTaskId])

  useEffect(() => {
    if (Number.isFinite(initialRunId) && initialRunId > 0) {
      void openDetailById(initialRunId)
    }
  }, [initialRunId, openDetailById])

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
        subtitle="排查视图：一句话结论、因果链、路由/工具/记忆/对话分步说明"
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
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
            {error}
          </div>
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
                  <div
                    key={run.id}
                    className="grid gap-3 p-4 text-sm md:grid-cols-[minmax(0,1fr)_auto_auto_auto] md:items-center"
                  >
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">Run #{run.id}</span>
                        <Badge variant={statusVariant(run.status)}>{run.status}</Badge>
                        {run.intent ? <Badge variant="outline">{run.intent}</Badge> : null}
                        {run.errorMessage ? <Badge variant="destructive">失败</Badge> : null}
                      </div>
                      <p className="mt-1 truncate text-muted-foreground">
                        user #{run.userId} / session #{run.sessionId} /{" "}
                        {run.modelName || run.modelProviderCode || "-"}
                      </p>
                      <p className="mt-1 truncate text-muted-foreground">
                        {diagnoseRunListItem(run)}
                      </p>
                      {run.errorMessage ? (
                        <p className="mt-0.5 truncate text-xs text-destructive">{run.errorMessage}</p>
                      ) : null}
                    </div>
                    <span className="text-muted-foreground">{formatDateTime(run.createdAt)}</span>
                    <span className="text-muted-foreground">
                      {run.eventCount ?? 0} events / {run.toolCallCount ?? 0} tools
                    </span>
                    <div className="flex gap-2">
                      <Button
                        size="icon"
                        variant="ghost"
                        onClick={() => openDetail(run)}
                        aria-label="查看详情"
                      >
                        <Eye className="h-4 w-4" />
                      </Button>
                      {isCancellable(run.status) ? (
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={() => cancelRun(run)}
                          disabled={actionId === run.id}
                          aria-label="取消运行"
                        >
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
        <DialogContent className="max-h-[92vh] max-w-6xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Agent Run 排查 · #{detail?.run.id ?? "—"}</DialogTitle>
            <DialogDescription>
              用白话说明「为什么失败 / 为什么选了这条路」，技术细节可展开查看。
            </DialogDescription>
          </DialogHeader>
          {detailLoading ? <p className="text-sm text-muted-foreground">加载中...</p> : null}
          {detail && diagnosis ? (
            <div className="space-y-5">
              <RunOverview detail={detail} />
              <RunDiagnosisHero diagnosis={diagnosis} />
              {detail.eventTruncated ? (
                <p className="text-xs text-amber-600">
                  事件较多，仅展示最近 {detail.events.length} 条（共 {detail.totalEventCount ?? "?"} 条）
                </p>
              ) : null}
              <RunDetailTabs detail={detail} diagnosis={diagnosis} />
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
