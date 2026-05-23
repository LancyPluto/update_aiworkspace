"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ApiError } from "@/lib/api/http"
import { fetchBillingOverview, fetchBillingUsageLogs } from "@/lib/api/billing"
import type { BillingOverview, BillingUsageLog } from "@/lib/api/types"
import { Coins, DollarSign, Gauge, RefreshCw, Sigma, WalletCards } from "lucide-react"

function number(value: number | null | undefined) {
  return Number(value || 0).toLocaleString()
}

function money(value: number | null | undefined) {
  return `¥${Number(value || 0).toFixed(6)}`
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function billingUnit(log: BillingUsageLog) {
  if (log.billingUnit === "PER_CALL") {
    return `${number(log.billableUnits)} 次 x ${money(log.unitPrice)}`
  }
  if (log.billingUnit === "IMAGE_TOKEN") {
    return "图片 Token / 1M"
  }
  return "Token / 1M"
}

export default function BillingPage() {
  const [overview, setOverview] = useState<BillingOverview | null>(null)
  const [logs, setLogs] = useState<BillingUsageLog[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  async function loadBilling() {
    setLoading(true)
    setError(null)
    try {
      const [overviewData, logData] = await Promise.all([
        fetchBillingOverview(),
        fetchBillingUsageLogs({ pageNo: 1, pageSize: 30 }),
      ])
      setOverview(overviewData)
      setLogs(logData.list)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载计费数据失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadBilling()
  }, [])

  const stats = useMemo(
    () => [
      { label: "今日 Token", value: number(overview?.todayTotalTokens), icon: Sigma },
      { label: "用户消耗积分", value: number(overview?.todayChargedCredits), icon: Coins },
      { label: "平台模型成本", value: money(overview?.todayCostAmount), icon: DollarSign },
      { label: "计费记录", value: number(overview?.todayUsageCount), icon: Gauge },
    ],
    [overview],
  )

  return (
    <AdminLayout>
      <AdminHeader
        title="计费日志"
        description={error ? `计费数据加载异常：${error}` : "查看模型 token 消耗、平台成本和用户侧积分消费"}
      />

      <div className="space-y-6 p-6">
        <div className="flex justify-end">
          <Button variant="outline" className="gap-2" onClick={loadBilling} disabled={loading}>
            <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
            刷新
          </Button>
        </div>

        <div className="grid gap-4 md:grid-cols-4">
          {stats.map((stat) => (
            <Card key={stat.label}>
              <CardContent className="flex items-center gap-3 p-4">
                <div className="rounded-md bg-secondary p-2">
                  <stat.icon className="h-5 w-5 text-primary" />
                </div>
                <div>
                  <p className="text-2xl font-semibold">{loading ? "--" : stat.value}</p>
                  <p className="text-sm text-muted-foreground">{stat.label}</p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

        <Card>
          <CardHeader>
            <CardTitle>模型成本分布</CardTitle>
            <CardDescription>按今天已写入的 token 计费日志聚合</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>模型</TableHead>
                  <TableHead>Provider</TableHead>
                  <TableHead>Token</TableHead>
                  <TableHead>成本</TableHead>
                  <TableHead>用户积分</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(overview?.modelCosts || []).map((item) => (
                  <TableRow key={`${item.provider}-${item.modelName}`}>
                    <TableCell className="font-medium">{item.modelName || "-"}</TableCell>
                    <TableCell>{item.provider || "-"}</TableCell>
                    <TableCell>{number(item.totalTokens)}</TableCell>
                    <TableCell>{money(item.costAmount)}</TableCell>
                    <TableCell>{number(item.chargedCredits)}</TableCell>
                  </TableRow>
                ))}
                {!loading && (!overview?.modelCosts || overview.modelCosts.length === 0) ? (
                  <TableRow>
                    <TableCell colSpan={5} className="h-24 text-center text-muted-foreground">
                      暂无今日 token 计费记录
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <WalletCards className="h-5 w-5" />
              最近计费日志
            </CardTitle>
            <CardDescription>任务和 Agent 完成时上报 token 后会写入这里</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>来源</TableHead>
                  <TableHead>用户</TableHead>
                  <TableHead>模型</TableHead>
                  <TableHead>输入/输出 Token</TableHead>
                  <TableHead>计费单位</TableHead>
                  <TableHead>成本</TableHead>
                  <TableHead>积分</TableHead>
                  <TableHead>时间</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {logs.map((log) => (
                  <TableRow key={log.id}>
                    <TableCell>
                      <Badge variant="secondary">{log.sourceType} #{log.sourceId}</Badge>
                    </TableCell>
                    <TableCell>U{log.userId}</TableCell>
                    <TableCell>
                      <div>
                        <p className="font-medium">{log.modelName || "-"}</p>
                        <p className="text-xs text-muted-foreground">{log.provider || "-"}</p>
                      </div>
                    </TableCell>
                    <TableCell>{number(log.promptTokens)} / {number(log.completionTokens)}</TableCell>
                    <TableCell>{billingUnit(log)}</TableCell>
                    <TableCell>{money(log.costAmount)}</TableCell>
                    <TableCell>{number(log.chargedCredits)}</TableCell>
                    <TableCell>{formatTime(log.createdAt)}</TableCell>
                  </TableRow>
                ))}
                {!loading && logs.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} className="h-24 text-center text-muted-foreground">
                      暂无计费日志
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  )
}
