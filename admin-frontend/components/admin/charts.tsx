"use client"

import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"

interface ChartPoint {
  name: string
  value: number
}

interface ChartProps {
  data: ChartPoint[]
}

export function TaskTrendChart({ data }: ChartProps) {
  return (
    <div className="rounded-2xl border border-border bg-card p-6">
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
    <div className="rounded-2xl border border-border bg-card p-6">
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
