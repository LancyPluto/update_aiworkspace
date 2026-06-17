"use client"

import { useEffect, useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { StatCard } from "@/components/admin/stat-card"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { TaskTrendChart, ToolUsageChart } from "@/components/admin/charts"
import {
  Users,
  ListTodo,
  Wrench,
  Zap,
  ArrowUpRight,
} from "lucide-react"
import { fetchAdminUsers } from "@/lib/api/users"
import { fetchAdminTools } from "@/lib/api/tools"
import { fetchAdminTasks } from "@/lib/api/tasks"
import { fetchDashboardOverview } from "@/lib/api/dashboard"
import { ApiError } from "@/lib/api/http"
import type { AdminTaskRow, DashboardChartPoint } from "@/lib/api/types"

interface RecentTaskRow {
  id: string
  user: string
  tool: string
  status: "active" | "pending" | "error" | "inactive"
  statusLabel: string
  credits: number | null
  time: string
}

function mapStatus(status: string): { status: RecentTaskRow["status"]; label: string } {
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
      return { status: "inactive", label: "已取消" }
    default:
      return { status: "inactive", label: status || "未知" }
  }
}

function formatRelativeTime(iso?: string | null): string {
  if (!iso) return "-"
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const diff = Date.now() - date.getTime()
  if (diff < 0) return date.toLocaleString()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return "刚刚"
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return date.toLocaleDateString()
}

const taskColumns = [
  { key: "id" as const, title: "任务 ID" },
  { key: "user" as const, title: "用户" },
  { key: "tool" as const, title: "工具" },
  {
    key: "status" as const,
    title: "状态",
    render: (_: unknown, item: RecentTaskRow) => (
      <StatusBadge
        status={item.status === "inactive" ? "inactive" : item.status}
        label={item.statusLabel}
      />
    ),
  },
  {
    key: "credits" as const,
    title: "消耗算力",
    render: (value: unknown) => (
      <span>{value === null || value === undefined ? "—" : `${value as number} 点`}</span>
    ),
  },
  { key: "time" as const, title: "时间" },
]

export default function DashboardPage() {
  const router = useRouter()
  const [userTotal, setUserTotal] = useState<number | null>(null)
  const [toolTotal, setToolTotal] = useState<number | null>(null)
  const [toolDraft, setToolDraft] = useState<number>(0)
  const [taskTotal, setTaskTotal] = useState<number | null>(null)
  const [tasks, setTasks] = useState<AdminTaskRow[]>([])
  const [taskTrend, setTaskTrend] = useState<DashboardChartPoint[]>([])
  const [popularTools, setPopularTools] = useState<DashboardChartPoint[]>([])
  const [apiCreditConsumed, setApiCreditConsumed] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [usersResp, toolsResp, tasksResp, overviewResp] = await Promise.all([
          fetchAdminUsers().catch(() => null),
          fetchAdminTools().catch(() => null),
          fetchAdminTasks().catch(() => null),
          fetchDashboardOverview().catch(() => null),
        ])
        if (cancelled) return
        if (usersResp) setUserTotal(usersResp.total ?? usersResp.list.length)
        if (toolsResp) {
          setToolTotal(toolsResp.total ?? toolsResp.list.length)
          setToolDraft(
            toolsResp.list.filter((t) => t.status !== "ONLINE").length,
          )
        }
        if (tasksResp) {
          setTaskTotal(tasksResp.total ?? tasksResp.list.length)
          setTasks(tasksResp.list)
        }
        if (overviewResp) {
          setTaskTrend(overviewResp.taskTrend)
          setPopularTools(overviewResp.popularTools)
          setApiCreditConsumed(overviewResp.apiCreditConsumed)
        }
      } catch (err) {
        if (cancelled) return
        const message = err instanceof ApiError ? err.message : "加载数据失败"
        setError(message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  const recentTasks: RecentTaskRow[] = useMemo(() => {
    return tasks.slice(0, 5).map((t) => {
      const mapped = mapStatus(t.status)
      return {
        id: t.taskNo || `T${t.taskId}`,
        user: t.userNickname || (t.userId != null ? `用户 ${t.userId}` : "-"),
        tool: t.toolName || t.toolCode,
        status: mapped.status,
        statusLabel: mapped.label,
        credits: t.consumedCredits ?? null,
        time: formatRelativeTime(t.createdAt),
      }
    })
  }, [tasks])

  const userValue = userTotal == null ? (loading ? "—" : "0") : userTotal.toLocaleString()
  const taskValue = taskTotal == null ? (loading ? "—" : "0") : taskTotal.toLocaleString()
  const toolValue = toolTotal == null ? (loading ? "—" : "0") : String(toolTotal)
  const toolChangeText =
    toolTotal == null ? (loading ? "加载中…" : "暂无数据") : `${toolDraft} 个待上线`
  const apiCreditValue =
    apiCreditConsumed == null ? (loading ? "—" : "0") : apiCreditConsumed.toLocaleString()

  return (
    <AdminLayout>
      <AdminHeader
        title="数据概览"
        description={error ? `加载失败：${error}` : "科创点AI运营数据一览"}
      />

      <div className="p-6 space-y-6">
        {/* Stats Grid */}
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <StatCard
            title="总用户数"
            value={userValue}
            change={loading ? "正在加载..." : "实时数据"}
            changeType="neutral"
            icon={Users}
            iconColor="bg-primary/10 text-primary"
          />
          <StatCard
            title="总任务数"
            value={taskValue}
            change={loading ? "正在加载..." : "包含全部状态"}
            changeType="neutral"
            icon={ListTodo}
            iconColor="bg-accent/10 text-accent"
          />
          <StatCard
            title="上线工具"
            value={toolValue}
            change={toolChangeText}
            changeType="neutral"
            icon={Wrench}
            iconColor="bg-chart-3/10 text-chart-3"
          />
          <StatCard
            title="API 消耗"
            value={apiCreditValue}
            change={loading ? "正在加载..." : "累计任务算力消耗"}
            changeType="neutral"
            icon={Zap}
            iconColor="bg-chart-5/10 text-chart-5"
          />
        </div>

        {/* Charts */}
        <div className="grid gap-6 lg:grid-cols-2">
          <TaskTrendChart data={taskTrend} />
          <ToolUsageChart data={popularTools} />
        </div>

        {/* Recent Tasks */}
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-lg font-semibold text-foreground">
                最近任务
              </h2>
              <p className="text-sm text-muted-foreground">
                {loading
                  ? "正在加载最近任务…"
                  : recentTasks.length === 0
                    ? "暂无任务记录"
                    : "实时任务执行状态"}
              </p>
            </div>
            <button
              onClick={() => router.push("/tasks")}
              className="flex items-center gap-1 text-sm font-medium text-primary hover:underline"
            >
              查看全部
              <ArrowUpRight className="h-4 w-4" />
            </button>
          </div>
          <DataTable columns={taskColumns} data={recentTasks} />
        </div>
      </div>
    </AdminLayout>
  )
}
