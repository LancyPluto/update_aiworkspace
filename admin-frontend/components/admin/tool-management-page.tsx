"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import {
  Image,
  Loader2,
  MoreHorizontal,
  Plus,
  Search,
  Trash2,
  UserRound,
  Video,
  WandSparkles,
  type LucideIcon,
} from "lucide-react"
import { toast } from "sonner"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import {
  deleteTool,
  fetchAllAdminTools,
  offlineTool,
  publishTool,
} from "@/lib/api/tools"
import type { ToolSummary } from "@/lib/api/types"
import { cn } from "@/lib/utils"
import { isWorkflowTool } from "@/lib/workflow-tools"

type ToolKind = "image" | "video" | "digitalHuman" | "text" | "other"

interface ToolManagementPageProps {
  mode?: string
}

const RUNTIME_PATTERN = /<!--\s*ai-tool-runtime:([\s\S]*?)\s*-->/

const kindMeta: Record<ToolKind, { label: string; icon: LucideIcon; className: string }> = {
  image: { label: "图片工具", icon: Image, className: "bg-pink-50 text-pink-700 border-pink-200" },
  video: { label: "视频工具", icon: Video, className: "bg-sky-50 text-sky-700 border-sky-200" },
  digitalHuman: { label: "数字人", icon: UserRound, className: "bg-violet-50 text-violet-700 border-violet-200" },
  text: { label: "文本工具", icon: WandSparkles, className: "bg-amber-50 text-amber-700 border-amber-200" },
  other: { label: "其他工具", icon: WandSparkles, className: "bg-muted text-muted-foreground border-border" },
}

function extractRuntimeKind(configNote?: string | null): ToolKind | null {
  const match = (configNote || "").match(RUNTIME_PATTERN)
  if (!match) return null
  try {
    const parsed = JSON.parse(match[1]) as { toolKind?: string }
    if (parsed.toolKind === "image" || parsed.toolKind === "video" || parsed.toolKind === "digitalHuman") {
      return parsed.toolKind
    }
    if (parsed.toolKind === "text") return "text"
  } catch {
    return null
  }
  return null
}

function resolveToolKind(tool: ToolSummary): ToolKind {
  const runtimeKind = extractRuntimeKind(tool.configNote)
  if (runtimeKind) return runtimeKind
  if (tool.toolKind === "image" || tool.outputModality === "IMAGE") return "image"
  if (tool.toolKind === "video" || tool.outputModality === "VIDEO") return "video"
  if (tool.toolKind === "digitalHuman" || tool.executionHandler === "DIGITAL_HUMAN") return "digitalHuman"
  if (tool.toolKind === "text" || tool.outputModality === "TEXT") return "text"
  return "other"
}

function statusLabel(status: string) {
  if (status === "ONLINE") return "已上线"
  if (status === "OFFLINE") return "已下线"
  return "草稿"
}

function statusClass(status: string) {
  if (status === "ONLINE") return "bg-emerald-50 text-emerald-700 border-emerald-200"
  if (status === "OFFLINE") return "bg-slate-50 text-slate-600 border-slate-200"
  return "bg-amber-50 text-amber-700 border-amber-200"
}

export function ToolManagementPage(_props: ToolManagementPageProps) {
  const [tools, setTools] = useState<ToolSummary[]>([])
  const [keyword, setKeyword] = useState("")
  const [kindFilter, setKindFilter] = useState<ToolKind | "all">("all")
  const [statusFilter, setStatusFilter] = useState("all")
  const [loading, setLoading] = useState(true)
  const [actionId, setActionId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function loadTools() {
    setLoading(true)
    setError(null)
    try {
      const page = await fetchAllAdminTools()
      setTools(page.list)
    } catch (err) {
      const message = err instanceof Error ? err.message : "加载工具失败"
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadTools()
  }, [])

  const filteredTools = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase()
    return tools.filter((tool) => {
      if (isWorkflowTool(tool)) return false
      const kind = resolveToolKind(tool)
      const text = `${tool.toolName} ${tool.toolCode} ${tool.description || ""}`.toLowerCase()
      const matchKeyword = !normalizedKeyword || text.includes(normalizedKeyword)
      const matchKind = kindFilter === "all" || kind === kindFilter
      const matchStatus = statusFilter === "all" || tool.status === statusFilter
      return matchKeyword && matchKind && matchStatus
    })
  }, [keyword, kindFilter, statusFilter, tools])

  async function runAction(tool: ToolSummary, action: "publish" | "offline" | "delete") {
    setActionId(tool.id)
    try {
      if (action === "publish") {
        await publishTool(tool.id)
        toast.success("工具已上线")
      } else if (action === "offline") {
        await offlineTool(tool.id)
        toast.success("工具已下线")
      } else {
        const confirmed = window.confirm(`确定删除「${tool.toolName}」吗？`)
        if (!confirmed) return
        await deleteTool(tool.id)
        toast.success("工具已删除")
      }
      await loadTools()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    } finally {
      setActionId(null)
    }
  }

  return (
    <AdminLayout>
      <AdminHeader title="工具配置" description="运营只需要配置用户输入、隐藏提示词、模型和展示素材。" />
      <main className="space-y-6 p-6">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-1 flex-col gap-3 md:flex-row">
            <div className="relative w-full md:max-w-md">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-9"
                placeholder="搜索工具名称、编码、描述"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
              />
            </div>
            <Select value={kindFilter} onValueChange={(value) => setKindFilter(value as ToolKind | "all")}>
              <SelectTrigger className="w-full md:w-40">
                <SelectValue placeholder="工具类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部类型</SelectItem>
                <SelectItem value="image">图片工具</SelectItem>
                <SelectItem value="video">视频工具</SelectItem>
                <SelectItem value="digitalHuman">数字人</SelectItem>
                <SelectItem value="text">文本工具</SelectItem>
              </SelectContent>
            </Select>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="w-full md:w-36">
                <SelectValue placeholder="状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部状态</SelectItem>
                <SelectItem value="DRAFT">草稿</SelectItem>
                <SelectItem value="ONLINE">已上线</SelectItem>
                <SelectItem value="OFFLINE">已下线</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Button asChild>
            <Link href="/tools/configure">
              <Plus className="mr-2 h-4 w-4" />
              新建工具
            </Link>
          </Button>
        </div>

        {error ? (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
            {error}
          </div>
        ) : null}

        <section className="rounded-xl border bg-card">
          <div className="flex items-center justify-between border-b px-5 py-4">
            <div>
              <h2 className="text-base font-semibold">AI 工具</h2>
              <p className="text-sm text-muted-foreground">共 {filteredTools.length} 个工具</p>
            </div>
            {loading ? <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" /> : null}
          </div>

          {loading ? (
            <div className="p-10 text-center text-sm text-muted-foreground">正在加载工具...</div>
          ) : filteredTools.length === 0 ? (
            <div className="p-10 text-center">
              <p className="text-sm font-medium">还没有符合条件的工具</p>
              <p className="mt-1 text-sm text-muted-foreground">新建一个图片工具，只需要上传字段和管理员提示词。</p>
              <Button className="mt-4" asChild>
                <Link href="/tools/configure">新建工具</Link>
              </Button>
            </div>
          ) : (
            <div className="grid gap-4 p-4 md:grid-cols-2 xl:grid-cols-3">
              {filteredTools.map((tool) => {
                const kind = resolveToolKind(tool)
                const meta = kindMeta[kind]
                const Icon = meta.icon
                const busy = actionId === tool.id
                return (
                  <article key={tool.id} className="rounded-lg border bg-background p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex min-w-0 items-start gap-3">
                        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted">
                          {tool.coverUrl ? (
                            <img src={tool.coverUrl} alt="" className="h-full w-full rounded-lg object-cover" />
                          ) : (
                            <Icon className="h-5 w-5 text-muted-foreground" />
                          )}
                        </div>
                        <div className="min-w-0">
                          <h3 className="truncate text-sm font-semibold">{tool.toolName}</h3>
                          <p className="truncate text-xs text-muted-foreground">{tool.toolCode}</p>
                        </div>
                      </div>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" disabled={busy}>
                            {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <MoreHorizontal className="h-4 w-4" />}
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem asChild>
                            <Link href={`/tools/configure?toolId=${tool.id}`}>编辑配置</Link>
                          </DropdownMenuItem>
                          {tool.status === "ONLINE" ? (
                            <DropdownMenuItem onClick={() => runAction(tool, "offline")}>下线</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onClick={() => runAction(tool, "publish")}>上线</DropdownMenuItem>
                          )}
                          <DropdownMenuItem className="text-destructive" onClick={() => runAction(tool, "delete")}>
                            <Trash2 className="mr-2 h-4 w-4" />
                            删除
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>

                    <p className="mt-4 line-clamp-2 min-h-10 text-sm text-muted-foreground">
                      {tool.description || "暂无描述"}
                    </p>

                    <div className="mt-4 flex flex-wrap items-center gap-2">
                      <Badge variant="outline" className={cn("border", meta.className)}>
                        <Icon className="h-3 w-3" />
                        {meta.label}
                      </Badge>
                      <Badge variant="outline" className={cn("border", statusClass(tool.status))}>
                        {statusLabel(tool.status)}
                      </Badge>
                      <Badge variant="outline">{tool.estimatedCreditCost} 算力</Badge>
                    </div>

                    <div className="mt-4 flex items-center justify-between border-t pt-3 text-xs text-muted-foreground">
                      <span>{tool.executionHandler || tool.toolType || "未配置执行器"}</span>
                      <Button variant="ghost" size="sm" asChild>
                        <Link href={`/tools/configure?toolId=${tool.id}`}>配置</Link>
                      </Button>
                    </div>
                  </article>
                )
              })}
            </div>
          )}
        </section>
      </main>
    </AdminLayout>
  )
}
