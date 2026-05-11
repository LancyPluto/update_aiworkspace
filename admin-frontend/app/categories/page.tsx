"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { ApiError } from "@/lib/api/http"
import { createToolCategory, fetchAdminToolCategories, fetchAdminTools, updateToolCategory, updateToolCategoryStatus } from "@/lib/api/tools"
import type { ToolCategory, ToolSummary } from "@/lib/api/types"
import { Edit, FileText, Plus, Search, Tags } from "lucide-react"

interface CategoryForm {
  categoryCode: string
  categoryName: string
  sortOrder: string
}

const emptyForm: CategoryForm = {
  categoryCode: "",
  categoryName: "",
  sortOrder: "0",
}

export default function CategoriesPage() {
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [toolCounts, setToolCounts] = useState<Record<number, number>>({})
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<ToolCategory | null>(null)
  const [form, setForm] = useState<CategoryForm>(emptyForm)
  const [submitting, setSubmitting] = useState(false)
  const [togglingId, setTogglingId] = useState<number | null>(null)

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [categoryResp, toolsResp] = await Promise.all([
        fetchAdminToolCategories(),
        fetchAdminTools().catch(() => ({ list: [] as ToolSummary[], total: 0 })),
      ])
      const counts: Record<number, number> = {}
      toolsResp.list.forEach((tool) => {
        const cid = tool.categoryId
        if (cid == null) return
        counts[cid] = (counts[cid] ?? 0) + 1
      })
      setCategories(categoryResp)
      setToolCounts(counts)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载分类失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filtered = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return categories
    return categories.filter((category) =>
      [category.categoryCode, category.categoryName, category.status ?? ""].some((value) =>
        value.toLowerCase().includes(keyword),
      ),
    )
  }, [categories, searchQuery])

  function openCreate() {
    setEditing(null)
    setForm(emptyForm)
    setDialogOpen(true)
  }

  function openEdit(category: ToolCategory) {
    setEditing(category)
    setForm({
      categoryCode: category.categoryCode,
      categoryName: category.categoryName,
      sortOrder: String(category.sortOrder ?? 0),
    })
    setDialogOpen(true)
  }

  function updateForm<K extends keyof CategoryForm>(key: K, value: CategoryForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function submitForm() {
    if (!form.categoryCode.trim() || !form.categoryName.trim()) {
      setError("请填写分类编码和名称")
      return
    }
    const sortOrder = Number(form.sortOrder)
    if (!Number.isFinite(sortOrder)) {
      setError("排序必须是数字")
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const payload = {
        categoryCode: form.categoryCode.trim(),
        categoryName: form.categoryName.trim(),
        sortOrder: Math.floor(sortOrder),
        status: editing?.status ?? "ACTIVE",
      }
      if (editing) {
        await updateToolCategory(editing.id, payload)
      } else {
        await createToolCategory(payload)
      }
      setDialogOpen(false)
      await loadAll()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存分类失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function toggleStatus(category: ToolCategory) {
    const nextStatus = category.status === "ACTIVE" ? "DISABLED" : "ACTIVE"
    setTogglingId(category.id)
    setError(null)
    try {
      await updateToolCategoryStatus(category.id, nextStatus)
      await loadAll()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "更新分类状态失败")
    } finally {
      setTogglingId(null)
    }
  }

  const description = error
    ? `联调异常：${error}`
    : loading
      ? "正在从数据库加载分类"
      : "管理 AI 工具分类，新增、编辑和启停状态会写入数据库"

  return (
    <AdminLayout>
      <AdminHeader title="分类管理" description={description} />

      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索分类名称、编码或状态"
              className="pl-9"
            />
          </div>
          <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
            <DialogTrigger asChild>
              <Button className="gap-2" onClick={openCreate}>
                <Plus className="h-4 w-4" />
                新增分类
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>{editing ? "编辑分类" : "新增分类"}</DialogTitle>
                <DialogDescription>分类会直接保存到后端数据库，并影响工具分类下拉框。</DialogDescription>
              </DialogHeader>
              <div className="grid gap-4 py-2">
                <div className="space-y-2">
                  <Label>分类编码</Label>
                  <Input value={form.categoryCode} onChange={(event) => updateForm("categoryCode", event.target.value)} placeholder="copywriting" />
                </div>
                <div className="space-y-2">
                  <Label>分类名称</Label>
                  <Input value={form.categoryName} onChange={(event) => updateForm("categoryName", event.target.value)} placeholder="内容创作" />
                </div>
                <div className="space-y-2">
                  <Label>排序</Label>
                  <Input type="number" value={form.sortOrder} onChange={(event) => updateForm("sortOrder", event.target.value)} />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setDialogOpen(false)} disabled={submitting}>取消</Button>
                <Button onClick={submitForm} disabled={submitting}>{submitting ? "保存中..." : "保存"}</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((category) => (
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
                <Switch
                  checked={category.status === "ACTIVE"}
                  disabled={togglingId === category.id}
                  onCheckedChange={() => toggleStatus(category)}
                />
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
                  <p className="text-muted-foreground">状态</p>
                  <p className="mt-1 font-medium">{category.status ?? "ACTIVE"}</p>
                </div>
              </div>
              <div className="mt-5 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <FileText className="h-4 w-4" />
                  <span>数据库分类</span>
                </div>
                <Button variant="outline" size="sm" className="gap-2" onClick={() => openEdit(category)}>
                  <Edit className="h-4 w-4" />
                  编辑
                </Button>
              </div>
            </article>
          ))}
        </div>
      </div>
    </AdminLayout>
  )
}
