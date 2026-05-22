"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { CapabilityEditor } from "@/components/admin/capability-editor"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { GripVertical, Pencil, Plus, Search, Trash2, UploadCloud } from "lucide-react"
import {
  createEmptyTool,
  capabilityTypeLabel,
  type AITool,
  type UpsertAIToolPayload,
} from "@/lib/ai-tool-types"
import {
  createAITool,
  deleteAITool,
  fetchAdminAITools,
  updateAITool,
  uploadAIToolIcon,
} from "@/lib/api/ai-tools"
import { ApiError, getBaseUrl } from "@/lib/api/http"
import { cn } from "@/lib/utils"

function normalizeMediaUrl(url?: string | null): string {
  const raw = url?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const baseUrl = getBaseUrl().replace(/\/$/, "")
  return baseUrl ? `${baseUrl}${path}` : path
}

function slugifyId(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/\s+/g, "-")
    .replace(/[^a-z0-9\u4e00-\u9fff-]/g, "")
    .slice(0, 32)
}

export default function ToolsPage() {
  const [tools, setTools] = useState<AITool[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState("")
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<AITool | null>(null)
  const [form, setForm] = useState<UpsertAIToolPayload>(createEmptyTool())
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [iconUploading, setIconUploading] = useState(false)
  const [togglingId, setTogglingId] = useState<string | null>(null)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<AITool | null>(null)

  async function loadTools() {
    setLoading(true)
    setError(null)
    try {
      const list = await fetchAdminAITools()
      setTools([...list].sort((a, b) => a.order - b.order))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 AI 工具失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadTools()
  }, [])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return tools
    return tools.filter((tool) =>
      [tool.name, tool.description || "", tool.id].some((value) => value.toLowerCase().includes(keyword)),
    )
  }, [tools, searchQuery])

  function openCreateDialog() {
    setEditingTool(null)
    const nextOrder = tools.length > 0 ? Math.max(...tools.map((tool) => tool.order)) + 10 : 10
    setForm(createEmptyTool(nextOrder))
    setFormError(null)
    setDialogOpen(true)
  }

  function openEditDialog(tool: AITool) {
    setEditingTool(tool)
    setForm({
      name: tool.name,
      iconUrl: tool.iconUrl,
      description: tool.description || "",
      enabled: tool.enabled,
      order: tool.order,
      primaryColor: tool.primaryColor || "#3b82f6",
      welcomeMessage: tool.welcomeMessage || "",
      capabilities: tool.capabilities || [],
    })
    setFormError(null)
    setDialogOpen(true)
  }

  function updateForm<K extends keyof UpsertAIToolPayload>(key: K, value: UpsertAIToolPayload[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleIconUpload(file: File) {
    setIconUploading(true)
    setFormError(null)
    try {
      const result = await uploadAIToolIcon(file)
      updateForm("iconUrl", result.url)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "图标上传失败")
    } finally {
      setIconUploading(false)
    }
  }

  async function handleSubmit() {
    setFormError(null)
    if (!form.name.trim()) {
      setFormError("请填写名称")
      return
    }
    if (form.name.trim().length > 20) {
      setFormError("名称最多 20 个字符")
      return
    }
    if (!form.iconUrl.trim()) {
      setFormError("请上传图标")
      return
    }
    if (!form.order || form.order < 1) {
      setFormError("排序必须为正整数")
      return
    }
    if ((form.description || "").length > 100) {
      setFormError("描述最多 100 个字符")
      return
    }

    setSubmitting(true)
    try {
      if (editingTool) {
        await updateAITool(editingTool.id, form)
      } else {
        const id = slugifyId(form.name) || `tool-${Date.now()}`
        await createAITool({ ...form, id })
      }
      setDialogOpen(false)
      await loadTools()
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "保存失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function toggleEnabled(tool: AITool) {
    setTogglingId(tool.id)
    try {
      await updateAITool(tool.id, {
        name: tool.name,
        iconUrl: tool.iconUrl,
        description: tool.description,
        enabled: !tool.enabled,
        order: tool.order,
        primaryColor: tool.primaryColor,
        welcomeMessage: tool.welcomeMessage,
        capabilities: tool.capabilities,
      })
      await loadTools()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "更新上架状态失败")
    } finally {
      setTogglingId(null)
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeletingId(deleteTarget.id)
    try {
      await deleteAITool(deleteTarget.id)
      setDeleteTarget(null)
      await loadTools()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "删除失败")
    } finally {
      setDeletingId(null)
    }
  }

  async function moveTool(toolId: string, direction: -1 | 1) {
    const sorted = [...tools].sort((a, b) => a.order - b.order)
    const index = sorted.findIndex((tool) => tool.id === toolId)
    const swapIndex = index + direction
    if (index < 0 || swapIndex < 0 || swapIndex >= sorted.length) return

    const current = sorted[index]
    const target = sorted[swapIndex]
    try {
      await Promise.all([
        updateAITool(current.id, {
          name: current.name,
          iconUrl: current.iconUrl,
          description: current.description,
          enabled: current.enabled,
          order: target.order,
          primaryColor: current.primaryColor,
          welcomeMessage: current.welcomeMessage,
          capabilities: current.capabilities,
        }),
        updateAITool(target.id, {
          name: target.name,
          iconUrl: target.iconUrl,
          description: target.description,
          enabled: target.enabled,
          order: current.order,
          primaryColor: target.primaryColor,
          welcomeMessage: target.welcomeMessage,
          capabilities: target.capabilities,
        }),
      ])
      await loadTools()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "调整排序失败")
    }
  }

  return (
    <AdminLayout>
      <AdminHeader
        title="大模型管理"
        description="配置 AI 模型图标、能力与上架状态，C 端聊天页将自动适配"
      />

      <div className="space-y-4 p-6">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              placeholder="搜索名称、描述、ID…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button onClick={openCreateDialog}>
            <Plus className="mr-1.5 h-4 w-4" />
            新增 AI 工具
          </Button>
        </div>

        {error ? (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            {error}
            <Button variant="link" className="ml-2 h-auto p-0 text-destructive" onClick={loadTools}>
              重试
            </Button>
          </div>
        ) : null}

        <div className="rounded-xl border border-border bg-card shadow-sm">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-10" />
                <TableHead>图标</TableHead>
                <TableHead>名称</TableHead>
                <TableHead>描述</TableHead>
                <TableHead>能力</TableHead>
                <TableHead>排序</TableHead>
                <TableHead>上架</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={8} className="py-10 text-center text-muted-foreground">
                    加载中…
                  </TableCell>
                </TableRow>
              ) : filteredTools.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="py-10 text-center text-muted-foreground">
                    暂无 AI 工具，点击右上角新增
                  </TableCell>
                </TableRow>
              ) : (
                filteredTools.map((tool, index) => (
                  <TableRow key={tool.id}>
                    <TableCell>
                      <div className="flex flex-col gap-1">
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          disabled={index === 0}
                          onClick={() => moveTool(tool.id, -1)}
                        >
                          <GripVertical className="h-3.5 w-3.5 rotate-180" />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          disabled={index === filteredTools.length - 1}
                          onClick={() => moveTool(tool.id, 1)}
                        >
                          <GripVertical className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell>
                      {tool.iconUrl ? (
                        <img
                          src={normalizeMediaUrl(tool.iconUrl)}
                          alt={tool.name}
                          className="h-10 w-10 rounded-lg border border-border object-cover"
                        />
                      ) : (
                        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-secondary text-xs text-muted-foreground">
                          无
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      <div>
                        <p className="font-medium">{tool.name}</p>
                        <p className="text-xs text-muted-foreground">{tool.id}</p>
                      </div>
                    </TableCell>
                    <TableCell className="max-w-[220px] truncate text-muted-foreground">
                      {tool.description || "—"}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {(tool.capabilities || []).length === 0 ? (
                          <span className="text-xs text-muted-foreground">无</span>
                        ) : (
                          tool.capabilities.map((capability, capIndex) => (
                            <Badge key={`${capability.type}-${capIndex}`} variant="secondary" className="text-[10px]">
                              {capabilityTypeLabel(capability.type)}
                            </Badge>
                          ))
                        )}
                      </div>
                    </TableCell>
                    <TableCell>{tool.order}</TableCell>
                    <TableCell>
                      <Switch
                        checked={tool.enabled}
                        disabled={togglingId === tool.id}
                        onCheckedChange={() => toggleEnabled(tool)}
                      />
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button type="button" variant="ghost" size="icon" onClick={() => openEditDialog(tool)}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(tool)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{editingTool ? "编辑 AI 工具" : "新增 AI 工具"}</DialogTitle>
            <DialogDescription>配置基础信息与能力，保存后 C 端将自动渲染对应控件</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label>名称 *</Label>
                <Input
                  className="mt-1.5"
                  maxLength={20}
                  placeholder="如：豆包"
                  value={form.name}
                  onChange={(e) => updateForm("name", e.target.value)}
                />
              </div>
              <div>
                <Label>排序 *</Label>
                <Input
                  className="mt-1.5"
                  type="number"
                  min={1}
                  value={form.order}
                  onChange={(e) => updateForm("order", Number(e.target.value) || 1)}
                />
              </div>
            </div>

            <div>
              <Label>图标 *</Label>
              <div className="mt-1.5 flex items-center gap-3">
                {form.iconUrl ? (
                  <img
                    src={normalizeMediaUrl(form.iconUrl)}
                    alt="icon preview"
                    className="h-16 w-16 rounded-xl border border-border object-cover"
                  />
                ) : (
                  <div className="flex h-16 w-16 items-center justify-center rounded-xl border border-dashed border-border bg-secondary/40 text-xs text-muted-foreground">
                    1:1
                  </div>
                )}
                <label className={cn("inline-flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 text-sm hover:bg-secondary", iconUploading && "pointer-events-none opacity-60")}>
                  <UploadCloud className="h-4 w-4" />
                  {iconUploading ? "上传中…" : "上传图标"}
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/svg+xml,image/webp"
                    className="hidden"
                    onChange={(e) => {
                      const file = e.target.files?.[0]
                      if (file) void handleIconUpload(file)
                      e.target.value = ""
                    }}
                  />
                </label>
              </div>
            </div>

            <div>
              <Label>描述</Label>
              <Textarea
                className="mt-1.5"
                maxLength={100}
                rows={2}
                placeholder="简短描述，最多 100 字"
                value={form.description || ""}
                onChange={(e) => updateForm("description", e.target.value)}
              />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label>主题色</Label>
                <div className="mt-1.5 flex items-center gap-2">
                  <Input
                    type="color"
                    className="h-10 w-14 cursor-pointer p-1"
                    value={form.primaryColor || "#3b82f6"}
                    onChange={(e) => updateForm("primaryColor", e.target.value)}
                  />
                  <Input
                    value={form.primaryColor || "#3b82f6"}
                    onChange={(e) => updateForm("primaryColor", e.target.value)}
                  />
                </div>
              </div>
              <div className="flex items-end">
                <div className="flex w-full items-center justify-between rounded-md border border-border px-3 py-2.5">
                  <Label>上架</Label>
                  <Switch checked={form.enabled} onCheckedChange={(checked) => updateForm("enabled", checked)} />
                </div>
              </div>
            </div>

            <div>
              <Label>欢迎语</Label>
              <Textarea
                className="mt-1.5"
                rows={2}
                placeholder="首次进入聊天页时展示"
                value={form.welcomeMessage || ""}
                onChange={(e) => updateForm("welcomeMessage", e.target.value)}
              />
            </div>

            <CapabilityEditor
              capabilities={form.capabilities}
              onChange={(capabilities) => updateForm("capabilities", capabilities)}
            />

            {formError ? <p className="text-sm text-destructive">{formError}</p> : null}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button type="button" disabled={submitting} onClick={handleSubmit}>
              {submitting ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>
              确定删除「{deleteTarget?.name}」吗？此操作不可撤销。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDeleteTarget(null)}>
              取消
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deletingId === deleteTarget?.id}
              onClick={confirmDelete}
            >
              {deletingId === deleteTarget?.id ? "删除中…" : "删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}
