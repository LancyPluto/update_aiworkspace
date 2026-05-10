"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Switch } from "@/components/ui/switch"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
} from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Plus,
  Search,
  Pencil,
  Trash2,
  Copy,
  MoreHorizontal,
  Sparkles,
  FileText,
  Video,
  ShoppingBag,
  MessageSquare,
  Store,
  type LucideIcon,
} from "lucide-react"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { cn } from "@/lib/utils"
import {
  createTool,
  fetchAdminTools,
  fetchToolCategories,
  offlineTool,
  publishTool,
} from "@/lib/api/tools"
import { ApiError } from "@/lib/api/http"
import type { ToolCategory, ToolSummary } from "@/lib/api/types"

interface ToolRow {
  id: string
  rawId: number
  name: string
  description: string
  category: string
  categoryId: number | null
  icon: LucideIcon
  credits: number
  usageCount: number
  status: boolean
  rawStatus: string
}

const CATEGORY_ICON_MAP: Record<string, LucideIcon> = {
  内容创作: FileText,
  短视频运营: Video,
  电商运营: ShoppingBag,
  私域销售: MessageSquare,
  门店获客: Store,
}

function pickIcon(categoryName?: string | null): LucideIcon {
  if (!categoryName) return Sparkles
  return CATEGORY_ICON_MAP[categoryName] || Sparkles
}

function mapTool(t: ToolSummary): ToolRow {
  return {
    id: String(t.id),
    rawId: t.id,
    name: t.toolName,
    description: t.description || "暂无描述",
    category: t.categoryName || "未分类",
    categoryId: t.categoryId ?? null,
    icon: pickIcon(t.categoryName),
    credits: t.estimatedCreditCost ?? 0,
    usageCount: 0,
    status: (t.status || "").toUpperCase() === "ONLINE",
    rawStatus: t.status,
  }
}

interface CreateForm {
  toolCode: string
  toolName: string
  description: string
  categoryId: string
  estimatedCreditCost: string
}

const initialForm: CreateForm = {
  toolCode: "",
  toolName: "",
  description: "",
  categoryId: "",
  estimatedCreditCost: "5",
}

export default function ToolsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [toolList, setToolList] = useState<ToolRow[]>([])
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [togglingId, setTogglingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState<CreateForm>(initialForm)
  const [formError, setFormError] = useState<string | null>(null)

  const loadAll = async () => {
    setLoading(true)
    setError(null)
    try {
      const [toolsResp, cats] = await Promise.all([
        fetchAdminTools(),
        fetchToolCategories().catch(() => [] as ToolCategory[]),
      ])
      setToolList(toolsResp.list.map(mapTool))
      setCategories(cats)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载工具列表失败"
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filteredTools = useMemo(() => {
    return toolList.filter(
      (tool) =>
        tool.name.includes(searchQuery) ||
        tool.description.includes(searchQuery) ||
        tool.category.includes(searchQuery),
    )
  }, [toolList, searchQuery])

  const toggleToolStatus = async (id: string) => {
    const target = toolList.find((t) => t.id === id)
    if (!target) return
    setTogglingId(target.rawId)
    const desired = !target.status
    try {
      const updated = desired
        ? await publishTool(target.rawId)
        : await offlineTool(target.rawId)
      setToolList((prev) =>
        prev.map((tool) => (tool.id === id ? mapTool(updated) : tool)),
      )
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "状态更新失败"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setTogglingId(null)
    }
  }

  const updateForm = <K extends keyof CreateForm>(key: K, value: CreateForm[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  const handleCreate = async () => {
    setFormError(null)
    if (!form.toolName.trim()) {
      setFormError("请输入工具名称")
      return
    }
    if (!form.categoryId) {
      setFormError("请选择分类")
      return
    }
    const credits = Number(form.estimatedCreditCost)
    if (!Number.isFinite(credits) || credits < 0) {
      setFormError("消耗算力必须是大于等于 0 的数字")
      return
    }
    setSubmitting(true)
    try {
      const created = await createTool({
        toolCode: form.toolCode.trim() || undefined,
        toolName: form.toolName.trim(),
        categoryId: Number(form.categoryId),
        description: form.description.trim() || undefined,
        estimatedCreditCost: Math.floor(credits),
      })
      setToolList((prev) => [mapTool(created), ...prev])
      setForm(initialForm)
      setIsAddDialogOpen(false)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "创建工具失败"
      setFormError(message)
    } finally {
      setSubmitting(false)
    }
  }

  const headerDescription = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载工具列表..."
      : "管理平台 AI 工具，配置工具参数和上下架"

  return (
    <AdminLayout>
      <AdminHeader title="AI 工具管理" description={headerDescription} />

      <div className="p-6 space-y-6">
        {/* Actions */}
        <div className="flex items-center justify-between">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索工具名称或描述..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <Dialog
            open={isAddDialogOpen}
            onOpenChange={(open) => {
              setIsAddDialogOpen(open)
              if (!open) {
                setForm(initialForm)
                setFormError(null)
              }
            }}
          >
            <DialogTrigger asChild>
              <Button className="gap-2">
                <Plus className="h-4 w-4" />
                新增工具
              </Button>
            </DialogTrigger>
            <DialogContent className="bg-card border-border max-w-lg">
              <DialogHeader>
                <DialogTitle>新增 AI 工具</DialogTitle>
                <DialogDescription>
                  {formError ? formError : "配置新的 AI 工具信息和参数"}
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>工具名称</Label>
                  <Input
                    placeholder="输入工具名称"
                    value={form.toolName}
                    onChange={(e) => updateForm("toolName", e.target.value)}
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="space-y-2">
                  <Label>工具描述</Label>
                  <Textarea
                    placeholder="输入工具描述"
                    value={form.description}
                    onChange={(e) => updateForm("description", e.target.value)}
                    className="bg-secondary border-0 min-h-[80px]"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>所属分类</Label>
                    <Select
                      value={form.categoryId}
                      onValueChange={(v) => updateForm("categoryId", v)}
                    >
                      <SelectTrigger className="bg-secondary border-0">
                        <SelectValue placeholder="选择分类" />
                      </SelectTrigger>
                      <SelectContent className="bg-card border-border">
                        {categories.map((cat) => (
                          <SelectItem key={cat.id} value={String(cat.id)}>
                            {cat.categoryName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>消耗算力</Label>
                    <Input
                      type="number"
                      placeholder="5"
                      value={form.estimatedCreditCost}
                      onChange={(e) =>
                        updateForm("estimatedCreditCost", e.target.value)
                      }
                      className="bg-secondary border-0"
                    />
                  </div>
                </div>
              </div>
              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setIsAddDialogOpen(false)}
                  disabled={submitting}
                >
                  取消
                </Button>
                <Button onClick={handleCreate} disabled={submitting}>
                  {submitting ? "创建中..." : "创建工具"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        {/* Tools Grid */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {filteredTools.map((tool) => (
            <div
              key={tool.id}
              className={cn(
                "group relative overflow-hidden rounded-2xl border border-border bg-card p-6 transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5",
                !tool.status && "opacity-60",
              )}
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
                    <tool.icon className="h-5 w-5 text-primary" />
                  </div>
                  <div>
                    <h3 className="font-semibold text-card-foreground">
                      {tool.name}
                    </h3>
                    <Badge
                      variant="secondary"
                      className="mt-1 text-xs font-normal"
                    >
                      {tool.category}
                    </Badge>
                  </div>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 opacity-0 group-hover:opacity-100 transition-opacity"
                    >
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent
                    align="end"
                    className="bg-card border-border"
                  >
                    <DropdownMenuItem className="gap-2" disabled>
                      <Pencil className="h-4 w-4" /> 编辑
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" disabled>
                      <Copy className="h-4 w-4" /> 复制
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2 text-destructive" disabled>
                      <Trash2 className="h-4 w-4" /> 删除
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>

              <p className="mt-3 text-sm text-muted-foreground line-clamp-2">
                {tool.description}
              </p>

              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-1 text-muted-foreground">
                    <Sparkles className="h-4 w-4" />
                    <span>{tool.credits} 点</span>
                  </div>
                  <div className="text-muted-foreground">
                    {tool.rawStatus === "ONLINE"
                      ? "已上架"
                      : tool.rawStatus === "DRAFT"
                        ? "草稿"
                        : "已下架"}
                  </div>
                </div>
                <Switch
                  checked={tool.status}
                  disabled={togglingId === tool.rawId}
                  onCheckedChange={() => toggleToolStatus(tool.id)}
                />
              </div>
            </div>
          ))}
        </div>
      </div>
    </AdminLayout>
  )
}
