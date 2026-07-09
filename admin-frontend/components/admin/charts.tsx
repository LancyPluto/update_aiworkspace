"use client"

import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import type {
  DashboardBusinessTrendPoint,
  DashboardToolContributionPoint,
} from "@/lib/api/types"
import type { ReactNode } from "react"

interface ChartPoint {
  name: string
  value: number
}

interface ChartProps {
  data: ChartPoint[]
}

interface BusinessTrendChartProps {
  data: DashboardBusinessTrendPoint[]
}

interface ToolContributionChartProps {
  data: DashboardToolContributionPoint[]
}

interface TooltipPayloadItem {
  name?: string
  value?: number | string
  color?: string
}

interface ChartTooltipProps {
  active?: boolean
  label?: string
  payload?: TooltipPayloadItem[]
  valueFormatter?: (value: number, name?: string) => string
}

function money(value: number) {
  return `¥${Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function number(value: number) {
  return Number(value || 0).toLocaleString()
}

function percent(value: number) {
  return `${(Number(value || 0) * 100).toFixed(1)}%`
}

function ChartShell({
  title,
  description,
  children,
}: {
  title: string
  description: string
  children: ReactNode
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <div className="mb-5">
        <h3 className="text-base font-semibold text-card-foreground">
          {title}
        </h3>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      {children}
    </div>
  )
}

function EmptyChart({ label }: { label: string }) {
  return (
    <div className="flex h-[280px] items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted-foreground">
      {label}
    </div>
  )
}

function ChartTooltip({
  active,
  label,
  payload,
  valueFormatter,
}: ChartTooltipProps) {
  if (!active || !payload?.length) return null

  return (
    <div className="rounded-lg border border-border bg-popover px-3 py-2 shadow-xl">
      <p className="mb-2 text-sm font-medium text-popover-foreground">{label}</p>
      <div className="space-y-1.5">
        {payload.map((item) => {
          const rawValue = Number(item.value || 0)
          return (
            <div key={item.name} className="flex min-w-40 items-center justify-between gap-5 text-xs">
              <span className="flex items-center gap-2 text-muted-foreground">
                <span
                  className="h-2 w-2 rounded-full"
                  style={{ backgroundColor: item.color || "var(--primary)" }}
                />
                {item.name}
              </span>
              <span className="font-medium text-foreground">
                {valueFormatter ? valueFormatter(rawValue, item.name) : number(rawValue)}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export function BusinessTrendChart({ data }: BusinessTrendChartProps) {
  const hasData = data.some(
    (item) =>
      item.rechargeRevenueAmount > 0 ||
      item.usageRevenueAmount > 0 ||
      item.vendorCostAmount > 0 ||
      item.taskTotal > 0,
  )

  return (
    <ChartShell title="经营趋势" description="近 30 天收入、成本与任务量">
      {!hasData ? (
        <EmptyChart label="暂无经营数据" />
      ) : (
        <ResponsiveContainer width="100%" height={300}>
          <ComposedChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="profitFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--accent)" stopOpacity={0.26} />
                <stop offset="95%" stopColor="var(--accent)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="name"
              axisLine={false}
              tickLine={false}
              tick={{ fill: "var(--muted-foreground)", fontSize: 12 }}
              tickFormatter={(value: string) => value.slice(5)}
            />
            <YAxis
              yAxisId="money"
              axisLine={false}
              tickLine={false}
              tick={{ fill: "var(--muted-foreground)", fontSize: 12 }}
              tickFormatter={(value: number) => `¥${value}`}
              width={58}
            />
            <YAxis
              yAxisId="tasks"
              orientation="right"
              axisLine={false}
              tickLine={false}
              tick={{ fill: "var(--muted-foreground)", fontSize: 12 }}
              width={36}
            />
            <Tooltip
              content={
                <ChartTooltip
                  valueFormatter={(value, name) =>
                    name === "任务量" ? `${number(value)} 个` : money(value)
                  }
                />
              }
            />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Area
              yAxisId="money"
              type="monotone"
              dataKey="grossProfitAmount"
              name="使用毛利"
              stroke="var(--accent)"
              strokeWidth={2}
              fill="url(#profitFill)"
            />
            <Line
              yAxisId="money"
              type="monotone"
              dataKey="rechargeRevenueAmount"
              name="充值收入"
              stroke="var(--primary)"
              strokeWidth={2}
              dot={false}
            />
            <Line
              yAxisId="money"
              type="monotone"
              dataKey="vendorCostAmount"
              name="API 成本"
              stroke="var(--chart-3)"
              strokeWidth={2}
              dot={false}
            />
            <Bar
              yAxisId="tasks"
              dataKey="taskTotal"
              name="任务量"
              fill="var(--chart-4)"
              radius={[4, 4, 0, 0]}
              barSize={12}
            />
          </ComposedChart>
        </ResponsiveContainer>
      )}
    </ChartShell>
  )
}

export function ToolContributionChart({ data }: ToolContributionChartProps) {
  const hasData = data.some((item) => item.taskTotal > 0 || item.usageRevenueAmount > 0)
  const chartData = data.map((item) => ({
    ...item,
    name: item.toolName,
  }))

  return (
    <ChartShell title="工具贡献" description="按使用收入和毛利排序">
      {!hasData ? (
        <EmptyChart label="暂无工具贡献数据" />
      ) : (
        <div className="space-y-4">
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 12, left: 8, bottom: 4 }}>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" horizontal={false} />
              <XAxis
                type="number"
                axisLine={false}
                tickLine={false}
                tick={{ fill: "var(--muted-foreground)", fontSize: 12 }}
                tickFormatter={(value: number) => `¥${value}`}
              />
              <YAxis
                type="category"
                dataKey="name"
                axisLine={false}
                tickLine={false}
                tick={{ fill: "var(--muted-foreground)", fontSize: 12 }}
                width={96}
              />
              <Tooltip content={<ChartTooltip valueFormatter={(value) => money(value)} />} />
              <Bar dataKey="grossProfitAmount" name="使用毛利" fill="var(--accent)" radius={[0, 4, 4, 0]} />
              <Bar dataKey="vendorCostAmount" name="API 成本" fill="var(--chart-3)" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
          <div className="grid gap-2 sm:grid-cols-2">
            {data.slice(0, 4).map((item) => (
              <div key={item.toolName} className="rounded-lg border border-border bg-secondary/25 p-3">
                <div className="flex items-center justify-between gap-3">
                  <p className="truncate text-sm font-medium text-foreground">{item.toolName}</p>
                  <span className="text-xs text-muted-foreground">{percent(item.successRate)}</span>
                </div>
                <div className="mt-2 flex items-end justify-between gap-3">
                  <span className="text-lg font-semibold text-foreground">{money(item.grossProfitAmount)}</span>
                  <span className="text-xs text-muted-foreground">{number(item.taskTotal)} 单</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </ChartShell>
  )
}

export function TaskTrendChart({ data }: ChartProps) {
  return (
    <div className="rounded-lg border border-border bg-card p-6">
      <div className="mb-6">
        <h3 className="text-base font-semibold text-card-foreground">
          任务趋势
        </h3>
        <p className="text-sm text-muted-foreground">过去 7 个月任务数量</p>
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <AreaChart data={data}>
          <defs>
            <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
              <stop
                offset="5%"
                stopColor="oklch(0.65 0.2 250)"
                stopOpacity={0.3}
              />
              <stop
                offset="95%"
                stopColor="oklch(0.65 0.2 250)"
                stopOpacity={0}
              />
            </linearGradient>
          </defs>
          <XAxis
            dataKey="name"
            axisLine={false}
            tickLine={false}
            tick={{ fill: "oklch(0.65 0 0)", fontSize: 12 }}
          />
          <YAxis
            axisLine={false}
            tickLine={false}
            tick={{ fill: "oklch(0.65 0 0)", fontSize: 12 }}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: "oklch(0.18 0.005 285)",
              border: "1px solid oklch(0.28 0.005 285)",
              borderRadius: "12px",
              color: "oklch(0.98 0 0)",
            }}
          />
          <Area
            type="monotone"
            dataKey="value"
            stroke="oklch(0.65 0.2 250)"
            strokeWidth={2}
            fillOpacity={1}
            fill="url(#colorValue)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

export function ToolUsageChart({ data }: ChartProps) {
  return (
    <div className="rounded-lg border border-border bg-card p-6">
      <div className="mb-6">
        <h3 className="text-base font-semibold text-card-foreground">
          热门工具
        </h3>
        <p className="text-sm text-muted-foreground">工具使用排行</p>
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <BarChart data={data} layout="vertical">
          <XAxis
            type="number"
            axisLine={false}
            tickLine={false}
            tick={{ fill: "oklch(0.65 0 0)", fontSize: 12 }}
          />
          <YAxis
            type="category"
            dataKey="name"
            axisLine={false}
            tickLine={false}
            tick={{ fill: "oklch(0.65 0 0)", fontSize: 12 }}
            width={80}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: "oklch(0.18 0.005 285)",
              border: "1px solid oklch(0.28 0.005 285)",
              borderRadius: "12px",
              color: "oklch(0.98 0 0)",
            }}
          />
          <Bar
            dataKey="value"
            fill="oklch(0.55 0.18 165)"
            radius={[0, 6, 6, 0]}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
