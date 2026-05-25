"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { ApiError } from "@/lib/api/http"
import {
  createToolCategory,
  fetchAdminToolCategories,
  fetchAdminTools,
  updateToolCategory,
  updateToolCategoryStatus,
} from "@/lib/api/tools"
import type { ToolCategory, ToolSummary } from "@/lib/api/types"
import { Edit3, FileText, Plus, RefreshCw, Search, Tags } from "lucide-react"

interface CategoryForm {
  categoryCode: string
  categoryName: string
  sortOrder: string
  status: string
}

const emptyForm: CategoryForm = {
  categoryCode: "",
  categoryName: "",
  sortOrder: "10",
  status: "ACTIVE",
}

function normalizeStatus(status?: string | null) {
  return (status || "ACTIVE").trim().toUpperCase()
}

function statusLabel(status?: string | null) {
  return normalizeStatus(status) === "ACTIVE" ? "启用" : "停用"
}

function statusVariant(status?: string | null) {
  return normalizeStatus(status) === "ACTIVE" ? "default" : "secondary"
}

export default function CategoriesPage() {
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [tools, setTools] = useState<ToolSummary[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [statusUpdatingId, setStatusUpdatingId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingCategory, setEditingCategory] = useState<ToolCategory | null>(null)
  const [form, setForm] = useState<CategoryForm>(emptyForm)

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [cats, toolsResp] = await Promise.all([
        fetchAdminToolCategories(),
        fetchAdminTools().catch(() => ({ list: [] as ToolSummary[], total: 0 })),
      ])
      setCategories(cats)
      setTools(toolsResp.list)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载工具分类失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const toolCounts = useMemo(() => {
    return tools.reduce<Record<number, number>>((acc, tool) => {
      if (tool.categoryId != null) {
        acc[tool.categoryId] = (acc[tool.categoryId] ?? 0) + 1
      }
      return acc
    }, {})
  }, [tools])

  const filtered = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    const sorted = [...categories].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id)
    if (!keyword) return sorted
    return sorted.filter((category) =>
      [category.categoryCode, category.categoryName, category.status ?? ""].some((value) =>
        value.toLowerCase().includes(keyword),
      ),
    )
  }, [categories, searchQuery])

  function updateForm<K extends keyof CategoryForm>(key: K, value: CategoryForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function openCreateDialog() {
    setEditingCategory(null)
    setForm({
      ...emptyForm,
      sortOrder: String((categories.length + 1) * 10),
    })
    setError(null)
    setDialogOpen(true)
  }

  function openEditDialog(category: ToolCategory) {
    setEditingCategory(category)
    setForm({
      categoryCode: category.categoryCode,
      categoryName: category.categoryName,
      sortOrder: String(category.sortOrder ?? 10),
      status: normalizeStatus(category.status),
    })
    setError(null)
    setDialogOpen(true)
  }

  async function handleSubmit() {
    const categoryCode = form.categoryCode.trim()
    const categoryName = form.categoryName.trim()
    const sortOrder = Number(form.sortOrder)
    if (!categoryCode || !categoryName) {
      setError("请填写分类编码和分类名称")
      return
    }
    if (!Number.isFinite(sortOrder)) {
      setError("排序值必须是数字")
      return
    }

    setSubmitting(true)
    setError(null)
    try {
      const payload = {
        categoryCode,
        categoryName,
        sortOrder,
        status: normalizeStatus(form.status),
      }
      if (editingCategory) {
        await updateToolCategory(editingCategory.id, payload)
      } else {
        await createToolCategory(payload)
      }
      setDialogOpen(false)
      await loadAll()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : editingCategory ? "更新分类失败" : "创建分类失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function handleStatusChange(category: ToolCategory, checked: boolean) {
    const nextStatus = checked ? "ACTIVE" : "DISABLED"
    setStatusUpdatingId(category.id)
    setError(null)
    try {
      await updateToolCategoryStatus(category.id, nextStatus)
      setCategories((current) =>
        current.map((item) => (item.id === category.id ? { ...item, status: nextStatus } : item)),
      )
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "更新分类状态失败")
    } finally {
      setStatusUpdatingId(null)
    }
  }

  const description = error
    ? `联调异常：${error}`
    : loading
      ? "正在加载数据库中的工具分类"
      : "管理工具分类，工具创建和前台分类筛选会使用这里的数据"

  return (
    <AdminLayout>
      <AdminHeader title="分类管理" description={description} />

      <div className="space-y-6 p-6">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索分类名称、编码或状态"
              className="pl-9"
            />
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" className="gap-2" onClick={loadAll} disabled={loading || submitting}>
              <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
              刷新
            </Button>
            <Button className="gap-2" onClick={openCreateDialog} disabled={loading || submitting}>
              <Plus className="h-4 w-4" />
              新建分类
            </Button>
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((category) => {
            const active = normalizeStatus(category.status) === "ACTIVE"
            return (
              <article key={category.id} className="rounded-lg border border-border bg-card p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
                      <Tags className="h-5 w-5" />
                    </div>
                    <div>
                      <h2 className="font-semibold">{category.categoryName}</h2>
                      <p className="text-xs text-muted-foreground">{category.categoryCode}</p>
                    </div>
                  </div>
                  <Badge variant={statusVariant(category.status)}>{statusLabel(category.status)}</Badge>
                </div>

                <div className="mt-5 grid grid-cols-3 gap-3 text-sm">
                  <div>
                    <p className="text-muted-foreground">工具数</p>
                    <p className="mt-1 font-medium">{toolCounts[category.id] ?? 0}</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">排序</p>
                    <p className="mt-1 font-medium">{category.sortOrder ?? 0}</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">ID</p>
                    <p className="mt-1 font-medium">{category.id}</p>
                  </div>
                </div>

                <div className="mt-5 flex items-center justify-between border-t border-border pt-4">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <FileText className="h-4 w-4" />
                    <span>工具分类</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <Switch
                      checked={active}
                      disabled={statusUpdatingId === category.id}
                      onCheckedChange={(checked) => handleStatusChange(category, checked)}
                    />
                    <Button variant="outline" size="sm" className="gap-2" onClick={() => openEditDialog(category)}>
                      <Edit3 className="h-4 w-4" />
                      编辑
                    </Button>
                  </div>
                </div>
              </article>
            )
          })}
        </div>

        {!loading && filtered.length === 0 && (
          <div className="rounded-lg border border-dashed border-border p-10 text-center text-sm text-muted-foreground">
            暂无匹配的工具分类
          </div>
        )}

        <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{editingCategory ? "编辑分类" : "新建分类"}</DialogTitle>
              <DialogDescription>分类会用于后台工具创建、编辑，以及前台工具列表的分类筛选。</DialogDescription>
            </DialogHeader>

            <div className="grid gap-4">
              <div className="space-y-2">
                <Label>分类编码</Label>
                <Input
                  value={form.categoryCode}
                  onChange={(event) => updateForm("categoryCode", event.target.value)}
                  placeholder="例如 image_tools"
                />
              </div>
              <div className="space-y-2">
                <Label>分类名称</Label>
                <Input
                  value={form.categoryName}
                  onChange={(event) => updateForm("categoryName", event.target.value)}
                  placeholder="例如 图片工具"
                />
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label>排序</Label>
                  <Input
                    type="number"
                    value={form.sortOrder}
                    onChange={(event) => updateForm("sortOrder", event.target.value)}
                  />
                </div>
                <div className="flex items-end justify-between rounded-md bg-secondary p-3">
                  <div>
                    <p className="text-sm font-medium">启用状态</p>
                    <p className="text-xs text-muted-foreground">停用后前台不展示</p>
                  </div>
                  <Switch
                    checked={normalizeStatus(form.status) === "ACTIVE"}
                    onCheckedChange={(checked) => updateForm("status", checked ? "ACTIVE" : "DISABLED")}
                  />
                </div>
              </div>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setDialogOpen(false)} disabled={submitting}>
                取消
              </Button>
              <Button onClick={handleSubmit} disabled={submitting}>
                {submitting ? "保存中..." : "保存"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </AdminLayout>
  )
}
