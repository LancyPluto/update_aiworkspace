"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ApiError } from "@/lib/api/http"
import { fetchBillingOverview, fetchBillingUsageLogs } from "@/lib/api/billing"
import type { BillingOverview, BillingUsageLog } from "@/lib/api/types"
import { Coins, DollarSign, Filter, Gauge, RefreshCw, Sigma, WalletCards } from "lucide-react"

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
  const [filters, setFilters] = useState({
    userId: "",
    modelName: "",
    provider: "",
    sourceType: "",
    sourceId: "",
    startDate: "",
    endDate: "",
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  function query() {
    return {
      userId: filters.userId ? Number(filters.userId) : undefined,
      modelName: filters.modelName || undefined,
      provider: filters.provider || undefined,
      sourceType: filters.sourceType || undefined,
      sourceId: filters.sourceId ? Number(filters.sourceId) : undefined,
      startDate: filters.startDate || undefined,
      endDate: filters.endDate || undefined,
    }
  }

  async function loadBilling() {
    setLoading(true)
    setError(null)
    try {
      const baseQuery = query()
      const [overviewData, logData] = await Promise.all([
        fetchBillingOverview(baseQuery),
        fetchBillingUsageLogs({ ...baseQuery, pageNo: 1, pageSize: 30 }),
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
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Filter className="h-5 w-5" />
              过滤与统计维度
            </CardTitle>
            <CardDescription>按用户、模型、供应商、来源和日期范围查看成本与算力消耗。</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid gap-3 md:grid-cols-7">
              <Input
                placeholder="用户 ID"
                inputMode="numeric"
                value={filters.userId}
                onChange={(event) => setFilters((current) => ({ ...current, userId: event.target.value }))}
              />
              <Input
                placeholder="模型名称"
                value={filters.modelName}
                onChange={(event) => setFilters((current) => ({ ...current, modelName: event.target.value }))}
              />
              <Input
                placeholder="Provider"
                value={filters.provider}
                onChange={(event) => setFilters((current) => ({ ...current, provider: event.target.value }))}
              />
              <Input
                placeholder="来源 TASK/AGENT_RUN"
                value={filters.sourceType}
                onChange={(event) => setFilters((current) => ({ ...current, sourceType: event.target.value }))}
              />
              <Input
                placeholder="来源 ID"
                inputMode="numeric"
                value={filters.sourceId}
                onChange={(event) => setFilters((current) => ({ ...current, sourceId: event.target.value }))}
              />
              <Input
                type="date"
                value={filters.startDate}
                onChange={(event) => setFilters((current) => ({ ...current, startDate: event.target.value }))}
              />
              <Input
                type="date"
                value={filters.endDate}
                onChange={(event) => setFilters((current) => ({ ...current, endDate: event.target.value }))}
              />
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <Button
                variant="ghost"
                onClick={() => setFilters({ userId: "", modelName: "", provider: "", sourceType: "", sourceId: "", startDate: "", endDate: "" })}
              >
                清空
              </Button>
              <Button variant="outline" className="gap-2" onClick={loadBilling} disabled={loading}>
                <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
                刷新
              </Button>
            </div>
          </CardContent>
        </Card>

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

        <div className="grid gap-4 lg:grid-cols-3">
          <Card>
            <CardHeader>
              <CardTitle>用户消耗 Top 10</CardTitle>
              <CardDescription>用于识别重点客户和异常消耗。</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>用户</TableHead>
                    <TableHead>积分</TableHead>
                    <TableHead>次数</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(overview?.userCosts || []).map((item) => (
                    <TableRow key={item.userId}>
                      <TableCell>U{item.userId}</TableCell>
                      <TableCell>{number(item.chargedCredits)}</TableCell>
                      <TableCell>{number(item.usageCount)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>模态消耗</CardTitle>
              <CardDescription>一级按文本、图片、视频、音频等生成模态聚合。</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>模态</TableHead>
                    <TableHead>积分</TableHead>
                    <TableHead>次数</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(overview?.modalityCosts || []).map((item) => (
                    <TableRow key={item.modality}>
                      <TableCell>{item.modality || "-"}</TableCell>
                      <TableCell>{number(item.chargedCredits)}</TableCell>
                      <TableCell>{number(item.usageCount)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>日期趋势</CardTitle>
              <CardDescription>最近 14 个有记录的日期聚合。</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>日期</TableHead>
                    <TableHead>成本</TableHead>
                    <TableHead>积分</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(overview?.dailyCosts || []).map((item) => (
                    <TableRow key={item.usageDate}>
                      <TableCell>{item.usageDate}</TableCell>
                      <TableCell>{money(item.costAmount)}</TableCell>
                      <TableCell>{number(item.chargedCredits)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </div>

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
