"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ApiError } from "@/lib/api/http"
import { fetchBillingOverview, fetchBillingUsageLogs } from "@/lib/api/billing"
import type { BillingModelCostPoint, BillingOverview, BillingUsageLog } from "@/lib/api/types"
import { Check, ChevronDown, ChevronRight, Coins, DollarSign, Gauge, Search, Sigma, WalletCards } from "lucide-react"

type ModelSortMode = "tokens_desc" | "tokens_asc" | "cost_desc" | "cost_asc"
type FilterOption = {
  value: string
  label: string
  description?: string
}
type ModelFilterGroup = FilterOption & {
  models: FilterOption[]
}

const headClass = "text-center align-middle"
const cellClass = "text-center align-middle"

function number(value: number | null | undefined) {
  return Number(value || 0).toLocaleString()
}

function money(value: number | null | undefined) {
  return `¥${Number(value || 0).toFixed(6)}`
}

function formatDate(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 10).replaceAll("/", "-")
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
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
  const inputPrice = log.inputTokenPricePer1m ?? Number(log.inputTokenPricePer1k || 0) * 1000
  const outputPrice = log.outputTokenPricePer1m ?? Number(log.outputTokenPricePer1k || 0) * 1000
  return `输入 ${money(inputPrice)}/1M，输出 ${money(outputPrice)}/1M`
}

function taskDisplayId(log: BillingUsageLog) {
  if (log.taskNo) return log.taskNo
  if (log.sourceType === "TASK") return `#${log.sourceId}`
  if (log.sourceType === "AGENT_RUN") return `Agent运行 #${log.sourceId}`
  return log.sourceId ? `${log.sourceType} #${log.sourceId}` : "-"
}

function uniqueValues(values: Array<string | number | null | undefined>) {
  return Array.from(new Set(values.map((value) => String(value ?? "").trim()).filter(Boolean)))
}

function sortModelCosts(items: BillingModelCostPoint[], mode: ModelSortMode) {
  return [...items].sort((a, b) => {
    if (mode === "cost_desc") {
      return Number(b.costAmount || 0) - Number(a.costAmount || 0)
    }
    if (mode === "cost_asc") {
      return Number(a.costAmount || 0) - Number(b.costAmount || 0)
    }
    if (mode === "tokens_asc") {
      return Number(a.totalTokens || 0) - Number(b.totalTokens || 0)
    }
    return Number(b.totalTokens || 0) - Number(a.totalTokens || 0)
  })
}

function modalityValue(log: BillingUsageLog) {
  if (log.inputModality || log.outputModality) {
    return `${log.inputModality || "未知输入"} -> ${log.outputModality || "未知输出"}`
  }
  return log.sourceType === "AGENT_RUN" ? "Agent运行" : "未分类"
}

function modalityLabel(value: string) {
  return value.includes("->") ? value.replace("->", "→") : value
}

function modelSelectionValue(type: "modality" | "model", value: string) {
  return `${type}:${value}`
}

function findModelSelectionLabel(value: string, groups: ModelFilterGroup[]) {
  if (value.startsWith("modality:")) {
    const modality = value.slice("modality:".length)
    return groups.find((group) => group.value === modality)?.label || "全部模型"
  }
  if (value.startsWith("model:")) {
    const modelName = value.slice("model:".length)
    return groups.flatMap((group) => group.models).find((model) => model.value === modelName)?.label || "全部模型"
  }
  return "全部模型"
}

function toggleValue(values: string[], nextValue: string) {
  return values.includes(nextValue) ? values.filter((value) => value !== nextValue) : [...values, nextValue]
}

function SortTriangles({
  active,
  mode,
  direction,
}: {
  active: ModelSortMode
  mode: ModelSortMode
  direction: "up" | "down"
}) {
  const activeClass = direction === "up" ? "border-b-blue-700" : "border-t-blue-700"
  const inactiveClass = direction === "up" ? "border-b-blue-200" : "border-t-blue-200"
  return (
    <span
      aria-hidden="true"
      className={
        direction === "up"
          ? `h-0 w-0 border-x-[5px] border-b-[7px] border-x-transparent ${active === mode ? activeClass : inactiveClass}`
          : `h-0 w-0 border-x-[5px] border-t-[7px] border-x-transparent ${active === mode ? activeClass : inactiveClass}`
      }
    />
  )
}

function SortableHead({
  label,
  active,
  desc,
  asc,
  onChange,
}: {
  label: string
  active: ModelSortMode
  desc: ModelSortMode
  asc: ModelSortMode
  onChange: (mode: ModelSortMode) => void
}) {
  return (
    <div className="flex items-center justify-center gap-2">
      <span>{label}</span>
      <div className="inline-flex flex-col">
        <button
          type="button"
          aria-label={`${label}从高到低排序`}
          onClick={() => onChange(desc)}
          className="flex h-3 w-5 items-center justify-center"
        >
          <SortTriangles active={active} mode={desc} direction="up" />
        </button>
        <button
          type="button"
          aria-label={`${label}从低到高排序`}
          onClick={() => onChange(asc)}
          className="flex h-3 w-5 items-center justify-center"
        >
          <SortTriangles active={active} mode={asc} direction="down" />
        </button>
      </div>
    </div>
  )
}

function MultiSelectFilter({
  label,
  values,
  allLabel,
  options,
  onChange,
  widthClass = "w-44",
}: {
  label: string
  values: string[]
  allLabel: string
  options: FilterOption[]
  onChange: (values: string[]) => void
  widthClass?: string
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const filteredOptions = options.filter((option) => {
    const keyword = query.trim().toLowerCase()
    if (!keyword) return true
    return `${option.label} ${option.description || ""}`.toLowerCase().includes(keyword)
  })
  const selectedLabels = options.filter((option) => values.includes(option.value)).map((option) => option.label)
  const triggerLabel =
    values.length === 0 ? allLabel : values.length === 1 ? selectedLabels[0] || allLabel : `已选 ${values.length} 项`

  function toggle(nextValue: string) {
    onChange(values.includes(nextValue) ? values.filter((value) => value !== nextValue) : [...values, nextValue])
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="sm" className={`mx-auto h-8 justify-center px-2 font-medium ${widthClass}`}>
          <span className="truncate">{triggerLabel}</span>
          <ChevronDown className="h-3.5 w-3.5 opacity-60" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-64 p-2" align="center" onWheelCapture={(event) => event.stopPropagation()}>
        <div className="relative mb-2">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={`搜索${label}`}
            className="h-8 pl-8 text-sm"
          />
        </div>
        <div className="max-h-64 overflow-y-auto overscroll-contain">
          <button
            type="button"
            className="flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
            onClick={() => onChange([])}
          >
            <span>{allLabel}</span>
            {values.length === 0 ? <Check className="h-4 w-4 text-primary" /> : null}
          </button>
          {filteredOptions.map((option) => (
            <button
              key={option.value}
              type="button"
              className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
              onClick={() => toggle(option.value)}
            >
              <span className="min-w-0">
                <span className="block truncate">{option.label}</span>
                {option.description ? (
                  <span className="block truncate text-xs text-muted-foreground">{option.description}</span>
                ) : null}
              </span>
              {values.includes(option.value) ? <Check className="h-4 w-4 shrink-0 text-primary" /> : null}
            </button>
          ))}
          {filteredOptions.length === 0 ? (
            <div className="px-2 py-6 text-center text-sm text-muted-foreground">没有匹配项</div>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  )
}

function ModelTreeFilter({
  values,
  groups,
  onAll,
  onToggleModality,
  onToggleModel,
}: {
  values: string[]
  groups: ModelFilterGroup[]
  onAll: () => void
  onToggleModality: (value: string) => void
  onToggleModel: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const [activeModality, setActiveModality] = useState<string>("")
  const selectedLabel = values.length === 0 ? "全部模型" : values.length === 1 ? findModelSelectionLabel(values[0], groups) : `已选 ${values.length} 项`
  const keyword = query.trim().toLowerCase()
  const filteredGroups = groups.filter((group) => {
    if (!keyword) return true
    const haystack = `${group.label} ${group.description || ""} ${group.models
      .map((model) => `${model.label} ${model.description || ""}`)
      .join(" ")}`
    return haystack.toLowerCase().includes(keyword)
  })
  const activeGroup =
    filteredGroups.find((group) => group.value === activeModality) || filteredGroups[0] || groups[0] || null

  function chooseAll() {
    onAll()
    setQuery("")
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="sm" className="mx-auto h-8 w-44 justify-center px-2 font-medium">
          <span className="truncate">{selectedLabel || "全部模型"}</span>
          <ChevronDown className="h-3.5 w-3.5 opacity-60" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[32rem] p-2" align="center" onWheelCapture={(event) => event.stopPropagation()}>
        <div className="relative mb-2">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索模态或模型"
            className="h-8 pl-8 text-sm"
          />
        </div>
        <div className="grid h-72 grid-cols-[13rem_1fr] overflow-hidden rounded-md border">
          <div className="min-h-0 overflow-y-auto overscroll-contain border-r bg-muted/20 p-1">
            <button
              type="button"
              className="flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
              onClick={chooseAll}
            >
              <span>全部模型</span>
              {values.length === 0 ? <Check className="h-4 w-4 text-primary" /> : null}
            </button>
            {filteredGroups.map((group) => (
              <button
                key={group.value}
                type="button"
                className={`flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent ${
                  activeGroup?.value === group.value ? "bg-accent" : ""
                }`}
                onMouseEnter={() => setActiveModality(group.value)}
                onFocus={() => setActiveModality(group.value)}
                onClick={() => onToggleModality(group.value)}
              >
                <span className="min-w-0">
                  <span className="block truncate">{group.label}</span>
                  {group.description ? (
                    <span className="block truncate text-xs text-muted-foreground">{group.description}</span>
                  ) : null}
                </span>
                <span className="flex shrink-0 items-center gap-1">
                  {values.includes(modelSelectionValue("modality", group.value)) ? (
                    <Check className="h-4 w-4 text-primary" />
                  ) : null}
                  <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
                </span>
              </button>
            ))}
            {filteredGroups.length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">没有匹配项</div>
            ) : null}
          </div>
          <div className="min-h-0 overflow-y-auto overscroll-contain p-1">
            {activeGroup ? (
              <>
                <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">{activeGroup.label} 下的模型</div>
                {activeGroup.models.map((model) => (
                  <button
                    key={model.value}
                    type="button"
                    className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
                    onClick={() => onToggleModel(model.value)}
                  >
                    <span className="min-w-0">
                      <span className="block truncate">{model.label}</span>
                      {model.description ? (
                        <span className="block truncate text-xs text-muted-foreground">{model.description}</span>
                      ) : null}
                    </span>
                    {values.includes(modelSelectionValue("model", model.value)) ? (
                      <Check className="h-4 w-4 shrink-0 text-primary" />
                    ) : null}
                  </button>
                ))}
                {activeGroup.models.length === 0 ? (
                  <div className="px-2 py-6 text-center text-sm text-muted-foreground">该模态暂无模型</div>
                ) : null}
              </>
            ) : (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">暂无模型分类</div>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

export default function BillingPage() {
  const [overview, setOverview] = useState<BillingOverview | null>(null)
  const [logs, setLogs] = useState<BillingUsageLog[]>([])
  const [modelSort, setModelSort] = useState<ModelSortMode>("tokens_desc")
  const [logFilters, setLogFilters] = useState({
    taskQuery: "",
    userIds: [] as string[],
    modelSelections: [] as string[],
    startDate: "",
    endDate: "",
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  async function loadBilling() {
    setLoading(true)
    setError(null)
    try {
      const [overviewData, logData] = await Promise.all([
        fetchBillingOverview({}),
        fetchBillingUsageLogs({ pageNo: 1, pageSize: 200 }),
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

  const sortedModelCosts = useMemo(
    () => sortModelCosts(overview?.modelCosts || [], modelSort),
    [overview?.modelCosts, modelSort],
  )

  const displayLogs = logs
  const userOptions = useMemo(
    () => uniqueValues(displayLogs.map((log) => log.userId)).map((userId) => ({ value: userId, label: `U${userId}` })),
    [displayLogs],
  )
  const modelGroups = useMemo(() => {
    const groups = new Map<string, ModelFilterGroup>()
    for (const log of displayLogs) {
      const modality = modalityValue(log)
      const modelName = log.modelName || "-"
      if (!groups.has(modality)) {
        groups.set(modality, {
          value: modality,
          label: modalityLabel(modality),
          description: "按此模态筛选",
          models: [],
        })
      }
      const group = groups.get(modality)
      if (group && !group.models.some((model) => model.value === modelName)) {
        group.models.push({
          value: modelName,
          label: modelName,
          description: log.provider || undefined,
        })
      }
    }
    return Array.from(groups.values()).map((group) => ({
      ...group,
      models: group.models.sort((a, b) => a.label.localeCompare(b.label)),
    }))
  }, [displayLogs])

  const filteredLogs = useMemo(
    () =>
      displayLogs.filter((log) => {
        const taskKeyword = logFilters.taskQuery.trim().toLowerCase()
        const taskMatched =
          !taskKeyword ||
          `${taskDisplayId(log)} ${log.sourceType || ""} ${log.sourceId || ""}`.toLowerCase().includes(taskKeyword)
        const userMatched = logFilters.userIds.length === 0 || logFilters.userIds.includes(String(log.userId))
        const modelMatched =
          logFilters.modelSelections.length === 0 ||
          logFilters.modelSelections.some((selection) => {
            if (selection.startsWith("model:")) {
              return (log.modelName || "-") === selection.slice("model:".length)
            }
            if (selection.startsWith("modality:")) {
              return modalityValue(log) === selection.slice("modality:".length)
            }
            return false
          })
        const usageDate = formatDate(log.createdAt)
        const afterStart = !logFilters.startDate || usageDate >= logFilters.startDate
        const beforeEnd = !logFilters.endDate || usageDate <= logFilters.endDate
        return taskMatched && userMatched && modelMatched && afterStart && beforeEnd
      }),
    [displayLogs, logFilters],
  )

  return (
    <AdminLayout>
      <AdminHeader
        title="计费日志"
        description={error ? `计费数据加载异常：${error}` : "查看模型 token 消耗、平台成本和用户侧积分消费"}
      />

      <div className="space-y-6 p-6">
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
            <div>
              <CardTitle>模型成本分布</CardTitle>
              <CardDescription>按今天已写入的 token 计费日志聚合</CardDescription>
            </div>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className={headClass}>模型</TableHead>
                  <TableHead className={headClass}>Provider</TableHead>
                  <TableHead className={headClass}>
                    <SortableHead
                      label="Token"
                      active={modelSort}
                      desc="tokens_desc"
                      asc="tokens_asc"
                      onChange={setModelSort}
                    />
                  </TableHead>
                  <TableHead className={headClass}>
                    <SortableHead
                      label="成本"
                      active={modelSort}
                      desc="cost_desc"
                      asc="cost_asc"
                      onChange={setModelSort}
                    />
                  </TableHead>
                  <TableHead className={headClass}>用户积分</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sortedModelCosts.map((item) => (
                  <TableRow key={`${item.provider}-${item.modelName}`}>
                    <TableCell className={`${cellClass} font-medium`}>{item.modelName || "-"}</TableCell>
                    <TableCell className={cellClass}>{item.provider || "-"}</TableCell>
                    <TableCell className={cellClass}>{number(item.totalTokens)}</TableCell>
                    <TableCell className={cellClass}>{money(item.costAmount)}</TableCell>
                    <TableCell className={cellClass}>{number(item.chargedCredits)}</TableCell>
                  </TableRow>
                ))}
                {!loading && sortedModelCosts.length === 0 ? (
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
          <CardHeader className="grid gap-3 lg:grid-cols-[1fr_auto_1fr] lg:items-start">
            <div>
              <CardTitle className="flex items-center gap-2">
                <WalletCards className="h-5 w-5" />
                最近计费日志
              </CardTitle>
              <CardDescription>任务和 Agent 完成或失败时上报 token 后会写入这里</CardDescription>
            </div>
            <div className="relative mx-auto w-full max-w-sm lg:w-80">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={logFilters.taskQuery}
                onChange={(event) => setLogFilters((current) => ({ ...current, taskQuery: event.target.value }))}
                placeholder="搜索任务 ID"
                className="h-9 pl-8 text-sm"
              />
            </div>
            <div aria-hidden="true" />
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className={headClass}>任务ID</TableHead>
                  <TableHead className={headClass}>
                    <MultiSelectFilter
                      label="用户"
                      values={logFilters.userIds}
                      allLabel="全部用户"
                      options={userOptions}
                      onChange={(values) => setLogFilters((current) => ({ ...current, userIds: values }))}
                      widthClass="w-32"
                    />
                  </TableHead>
                  <TableHead className={headClass}>
                    <ModelTreeFilter
                      values={logFilters.modelSelections}
                      groups={modelGroups}
                      onAll={() => setLogFilters((current) => ({ ...current, modelSelections: [] }))}
                      onToggleModality={(value) =>
                        setLogFilters((current) => ({
                          ...current,
                          modelSelections: toggleValue(current.modelSelections, modelSelectionValue("modality", value)),
                        }))
                      }
                      onToggleModel={(value) =>
                        setLogFilters((current) => ({
                          ...current,
                          modelSelections: toggleValue(current.modelSelections, modelSelectionValue("model", value)),
                        }))
                      }
                    />
                  </TableHead>
                  <TableHead className={headClass}>输入/输出 Token</TableHead>
                  <TableHead className={headClass}>计费单位</TableHead>
                  <TableHead className={headClass}>成本</TableHead>
                  <TableHead className={headClass}>积分</TableHead>
                  <TableHead className={headClass}>
                    <div className="mx-auto flex min-w-52 items-center justify-center gap-2">
                      <input
                        aria-label="开始日期"
                        type="date"
                        value={logFilters.startDate}
                        onChange={(event) => setLogFilters((current) => ({ ...current, startDate: event.target.value }))}
                        className="h-8 w-28 rounded-md border border-input bg-transparent px-2 text-xs font-normal text-foreground shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                      />
                      <span className="text-xs font-normal text-muted-foreground">至</span>
                      <input
                        aria-label="结束日期"
                        type="date"
                        value={logFilters.endDate}
                        onChange={(event) => setLogFilters((current) => ({ ...current, endDate: event.target.value }))}
                        className="h-8 w-28 rounded-md border border-input bg-transparent px-2 text-xs font-normal text-foreground shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                      />
                    </div>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredLogs.map((log) => (
                  <TableRow key={log.id}>
                    <TableCell className={cellClass}>
                      <span className="font-mono text-sm">{taskDisplayId(log)}</span>
                    </TableCell>
                    <TableCell className={cellClass}>U{log.userId}</TableCell>
                    <TableCell className={cellClass}>
                      <div>
                        <p className="font-medium">{log.modelName || "-"}</p>
                        <p className="text-xs text-muted-foreground">{log.provider || "-"}</p>
                      </div>
                    </TableCell>
                    <TableCell className={cellClass}>
                      {number(log.promptTokens)} / {number(log.completionTokens)}
                    </TableCell>
                    <TableCell className={cellClass}>{billingUnit(log)}</TableCell>
                    <TableCell className={cellClass}>{money(log.costAmount)}</TableCell>
                    <TableCell className={cellClass}>{number(log.chargedCredits)}</TableCell>
                    <TableCell className={cellClass}>{formatTime(log.createdAt)}</TableCell>
                  </TableRow>
                ))}
                {!loading && filteredLogs.length === 0 ? (
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
