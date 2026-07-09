"use client"

import { useEffect, useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import type { ComponentType } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { StatCard } from "@/components/admin/stat-card"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import {
  BusinessTrendChart,
  ToolContributionChart,
} from "@/components/admin/charts"
import {
  ArrowUpRight,
  BadgeDollarSign,
  Gauge,
  ListTodo,
  TrendingUp,
  Users,
  WalletCards,
  Wrench,
} from "lucide-react"
import { CreditPowerIcon } from "@/components/admin/credit-power-icon"
import { fetchAdminTasks } from "@/lib/api/tasks"
import { fetchDashboardOverview } from "@/lib/api/dashboard"
import { ApiError } from "@/lib/api/http"
import type { AdminTaskRow, DashboardOverview } from "@/lib/api/types"
import { cn } from "@/lib/utils"

interface RecentTaskRow {
  id: string
  user: string
  tool: string
  status: "active" | "pending" | "error" | "inactive"
  statusLabel: string
  credits: number | null
  time: string
}

interface KpiCardProps {
  title: string
  value: string
  helper: string
  icon: ComponentType<{ className?: string }>
  tone: "revenue" | "profit" | "cost" | "rate"
  loading?: boolean
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

function money(value?: number | null): string {
  if (value == null) return "—"
  return `¥${Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function number(value?: number | null): string {
  if (value == null) return "—"
  return Number(value || 0).toLocaleString()
}

function percent(value?: number | null): string {
  if (value == null) return "—"
  return `${(Number(value || 0) * 100).toFixed(1)}%`
}

function compactDate(iso?: string | null): string {
  if (!iso) return ""
  const date = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getMonth() + 1}/${date.getDate()}`
}

function KpiCard({ title, value, helper, icon: Icon, tone, loading }: KpiCardProps) {
  const toneClass = {
    revenue: "border-primary/25 bg-primary/5 text-primary",
    profit: "border-accent/25 bg-accent/5 text-accent",
    cost: "border-chart-3/25 bg-chart-3/5 text-chart-3",
    rate: "border-chart-4/25 bg-chart-4/5 text-chart-4",
  }[tone]

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm text-muted-foreground">{title}</p>
          <p className="mt-3 text-3xl font-semibold tracking-tight text-foreground">
            {loading ? "—" : value}
          </p>
          <p className="mt-2 text-sm text-muted-foreground">{helper}</p>
        </div>
        <div className={cn("rounded-lg border p-3", toneClass)}>
          <Icon className="h-5 w-5" />
        </div>
      </div>
    </div>
  )
}

function BusinessStructure({ overview }: { overview: DashboardOverview | null }) {
  const rows = [
    {
      label: "充值收入",
      value: overview?.rechargeRevenueAmount ?? 0,
      className: "bg-primary",
    },
    {
      label: "使用收入",
      value: overview?.usageRevenueAmount ?? 0,
      className: "bg-accent",
    },
    {
      label: "API 成本",
      value: overview?.vendorCostAmount ?? 0,
      className: "bg-chart-3",
    },
    {
      label: "使用毛利",
      value: overview?.grossProfitAmount ?? 0,
      className: overview && overview.grossProfitAmount < 0 ? "bg-destructive" : "bg-chart-4",
    },
  ]
  const max = Math.max(...rows.map((row) => Math.abs(row.value)), 1)

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h3 className="text-base font-semibold text-card-foreground">收入结构</h3>
          <p className="text-sm text-muted-foreground">充值到账与使用侧毛利</p>
        </div>
        <span className="rounded-lg border border-border px-2.5 py-1 text-xs text-muted-foreground">
          {overview ? `${compactDate(overview.rangeStartDate)}-${compactDate(overview.rangeEndDate)}` : "近 30 天"}
        </span>
      </div>
      <div className="space-y-4">
        {rows.map((row) => (
          <div key={row.label} className="space-y-2">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="text-muted-foreground">{row.label}</span>
              <span className="font-medium text-foreground">{money(row.value)}</span>
            </div>
            <div className="h-2 overflow-hidden rounded-lg bg-secondary">
              <div
                className={cn("h-full rounded-lg", row.className)}
                style={{ width: `${Math.max(4, (Math.abs(row.value) / max) * 100)}%` }}
              />
            </div>
          </div>
        ))}
      </div>
      <div className="mt-5 grid grid-cols-2 gap-3 border-t border-border pt-4">
        <div>
          <p className="text-xs text-muted-foreground">毛利率</p>
          <p className="mt-1 text-lg font-semibold text-foreground">{percent(overview?.grossMarginRate)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">成功率</p>
          <p className="mt-1 text-lg font-semibold text-foreground">{percent(overview?.successRate)}</p>
        </div>
      </div>
    </div>
  )
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
  const [overview, setOverview] = useState<DashboardOverview | null>(null)
  const [tasks, setTasks] = useState<AdminTaskRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [overviewResp, tasksResp] = await Promise.all([
          fetchDashboardOverview(),
          fetchAdminTasks().catch(() => null),
        ])
        if (cancelled) return
        setOverview(overviewResp)
        if (tasksResp) setTasks(tasksResp.list)
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

  const rangeLabel = overview
    ? `${compactDate(overview.rangeStartDate)} - ${compactDate(overview.rangeEndDate)}`
    : "近 30 天"

  return (
    <AdminLayout>
      <AdminHeader
        title="经营驾驶舱"
        description={error ? `加载失败：${error}` : `科创点AI经营数据 · ${rangeLabel}`}
      />

      <div className="space-y-6 p-6">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <KpiCard
            title="充值收入"
            value={money(overview?.rechargeRevenueAmount)}
            helper="已支付/已入账"
            icon={BadgeDollarSign}
            tone="revenue"
            loading={loading && !overview}
          />
          <KpiCard
            title="使用毛利"
            value={money(overview?.grossProfitAmount)}
            helper={`毛利率 ${percent(overview?.grossMarginRate)}`}
            icon={TrendingUp}
            tone="profit"
            loading={loading && !overview}
          />
          <KpiCard
            title="API 成本"
            value={money(overview?.vendorCostAmount)}
            helper={`使用收入 ${money(overview?.usageRevenueAmount)}`}
            icon={WalletCards}
            tone="cost"
            loading={loading && !overview}
          />
          <KpiCard
            title="任务成功率"
            value={percent(overview?.successRate)}
            helper={`${number(overview?.successTaskTotal)} / ${number(overview?.taskTotal)} 成功`}
            icon={Gauge}
            tone="rate"
            loading={loading && !overview}
          />
        </div>

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <StatCard
            title="总用户"
            value={number(overview?.totalUserCount)}
            change={`新增 ${number(overview?.newUserCount)}`}
            changeType="neutral"
            icon={Users}
            iconColor="bg-primary/10 text-primary"
          />
          <StatCard
            title="任务量"
            value={number(overview?.taskTotal)}
            change={`处理中 ${number(overview?.processingTaskTotal)} · 失败 ${number(overview?.failedTaskTotal)}`}
            changeType="neutral"
            icon={ListTodo}
            iconColor="bg-accent/10 text-accent"
          />
          <StatCard
            title="上线工具"
            value={number(overview?.onlineToolCount)}
            change={`${number(overview?.draftToolCount)} 个待上线`}
            changeType="neutral"
            icon={Wrench}
            iconColor="bg-chart-3/10 text-chart-3"
          />
          <StatCard
            title="算力消耗"
            value={number(overview?.apiCreditConsumed)}
            change="累计任务算力消耗"
            changeType="neutral"
            icon={CreditPowerIcon}
            iconColor="bg-chart-5/10 text-chart-5"
          />
        </div>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(360px,0.8fr)]">
          <BusinessTrendChart data={overview?.businessTrend ?? []} />
          <BusinessStructure overview={overview} />
        </div>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
          <ToolContributionChart data={overview?.toolContributions ?? []} />
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-foreground">最近任务</h2>
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
            <DataTable columns={taskColumns} data={recentTasks} className="rounded-lg" />
          </div>
        </div>
      </div>
    </AdminLayout>
  )
}
