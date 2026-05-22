"use client"

import { useEffect, useMemo, useRef, useState } from "react"
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
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ApiError, getBaseUrl } from "@/lib/api/http"
import {
  createAITool,
  deleteAITool,
  fetchAdminAITools,
  updateAITool,
  uploadAIToolIcon,
} from "@/lib/api/ai-tools"
import {
  capabilityTypeLabel,
  createEmptyTool,
  type AITool,
  type UpsertAIToolPayload,
} from "@/lib/ai-tool-types"
import { cn } from "@/lib/utils"
import { MoreHorizontal, Pencil, Plus, Search, Sparkles, Trash2, UploadCloud } from "lucide-react"

function normalizeMediaUrl(url?: string | null): string {
  const raw = url?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) {
    return raw
  }
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const baseUrl = getBaseUrl().replace(/\/$/, "")
  return baseUrl ? `${baseUrl}${path}` : path
}

function validateForm(form: UpsertAIToolPayload & { id?: string }, isEdit: boolean): string | null {
  const id = form.id?.trim() || ""
  if (!isEdit) {
    if (!id) return "请填写模型 ID"
    if (!/^[a-z0-9-]{1,32}$/.test(id)) return "模型 ID 仅支持小写字母、数字、连字符，最长 32 位"
  }
  if (!form.name.trim()) return "请填写显示名称"
  if (form.name.trim().length > 20) return "显示名称不超过 20 字"
  if (!form.iconUrl.trim()) return "请上传或填写图标 URL"
  if (form.description && form.description.length > 100) return "描述不超过 100 字"
  if (!Number.isFinite(form.order) || form.order < 1) return "排序需为正整数"
  return null
}

export default function MarketplaceToolsPage() {
  const [tools, setTools] = useState<AITool[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<AITool | null>(null)
  const [form, setForm] = useState<UpsertAIToolPayload & { id?: string }>(createEmptyTool())
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [iconUploading, setIconUploading] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [togglingId, setTogglingId] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  async function loadTools() {
    setLoading(true)
    setError(null)
    try {
      const list = await fetchAdminAITools()
      setTools(list)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载大模型列表失败")
      setTools([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadTools()
  }, [])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    const sorted = [...tools].sort((a, b) => a.order - b.order)
    if (!keyword) return sorted
    return sorted.filter((tool) =>
      [tool.id, tool.name, tool.description || ""].some((value) => value.toLowerCase().includes(keyword)),
    )
  }, [tools, searchQuery])

  const headerDescription = error
    ? `操作失败：${error}`
    : loading
      ? "正在加载大模型列表..."
      : "配置 AI 模型图标、能力与上架状态，C 端聊天页将自动适配"

  function openCreateDialog() {
    setEditingTool(null)
    setForm(createEmptyTool(tools.length > 0 ? Math.max(...tools.map((t) => t.order)) + 10 : 10))
    setFormError(null)
    setDialogOpen(true)
  }

  function openEditDialog(tool: AITool) {
    setEditingTool(tool)
    setForm({
      id: tool.id,
      name: tool.name,
      iconUrl: tool.iconUrl,
      description: tool.description || "",
      enabled: tool.enabled,
      order: tool.order,
      primaryColor: tool.primaryColor || "#3b82f6",
      welcomeMessage: tool.welcomeMessage || "",
      capabilities: [...tool.capabilities],
    })
    setFormError(null)
    setDialogOpen(true)
  }

  function updateForm<K extends keyof (UpsertAIToolPayload & { id?: string })>(
    key: K,
    value: (UpsertAIToolPayload & { id?: string })[K],
  ) {
    setForm((current) => ({ ...current, [key]: value }))
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

  async function handleSave() {
    const validationError = validateForm(form, Boolean(editingTool))
    if (validationError) {
      setFormError(validationError)
      return
    }

    const payload: UpsertAIToolPayload & { id?: string } = {
      name: form.name.trim(),
      iconUrl: form.iconUrl.trim(),
      description: form.description?.trim() || undefined,
      enabled: form.enabled,
      order: form.order,
      primaryColor: form.primaryColor?.trim() || undefined,
      welcomeMessage: form.welcomeMessage?.trim() || undefined,
      capabilities: form.capabilities,
    }

    setSubmitting(true)
    setFormError(null)
    try {
      if (editingTool) {
        await updateAITool(editingTool.id, payload)
      } else {
        await createAITool({ ...payload, id: form.id!.trim() })
      }
      setDialogOpen(false)
      await loadTools()
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "保存失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function handleToggleEnabled(tool: AITool, enabled: boolean) {
    setTogglingId(tool.id)
    setError(null)
    try {
      await updateAITool(tool.id, {
        name: tool.name,
        iconUrl: tool.iconUrl,
        description: tool.description,
        enabled,
        order: tool.order,
        primaryColor: tool.primaryColor,
        welcomeMessage: tool.welcomeMessage,
        capabilities: tool.capabilities,
      })
      setTools((current) => current.map((item) => (item.id === tool.id ? { ...item, enabled } : item)))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "更新上架状态失败")
    } finally {
      setTogglingId(null)
    }
  }

  async function handleDelete(tool: AITool) {
    if (!window.confirm(`确定删除「${tool.name}」？删除后 C 端将无法新建该模型的会话。`)) return
    setDeletingId(tool.id)
    setError(null)
    try {
      await deleteAITool(tool.id)
      setTools((current) => current.filter((item) => item.id !== tool.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "删除失败")
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <AdminLayout>
      <AdminHeader title="大模型管理" description={headerDescription} />

      <div className="space-y-6 p-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索模型名称或 ID..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="border-0 bg-secondary pl-9"
            />
          </div>
          <Dialog
            open={dialogOpen}
            onOpenChange={(open) => {
              setDialogOpen(open)
              if (!open) {
                setEditingTool(null)
                setFormError(null)
              }
            }}
          >
            <DialogTrigger asChild>
              <Button className="gap-2" onClick={openCreateDialog}>
                <Plus className="h-4 w-4" />
                新建大模型
              </Button>
            </DialogTrigger>
            <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto border-border bg-card">
              <DialogHeader>
                <DialogTitle>{editingTool ? "编辑大模型" : "新建大模型"}</DialogTitle>
                <DialogDescription className={formError ? "text-destructive" : undefined}>
                  {formError || "配置模型基础信息；C 端聊天页将根据能力自动渲染控件。"}
                </DialogDescription>
              </DialogHeader>

              <div className="space-y-4 py-2">
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="tool-id">模型 ID</Label>
                    <Input
                      id="tool-id"
                      value={form.id || ""}
                      disabled={Boolean(editingTool)}
                      placeholder="如 doubao"
                      onChange={(event) => updateForm("id", event.target.value)}
                    />
                    <p className="text-xs text-muted-foreground">小写字母、数字、连字符，创建后不可修改</p>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="tool-name">显示名称</Label>
                    <Input
                      id="tool-name"
                      value={form.name}
                      maxLength={20}
                      placeholder="如 豆包"
                      onChange={(event) => updateForm("name", event.target.value)}
                    />
                  </div>
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="tool-order">排序</Label>
                    <Input
                      id="tool-order"
                      type="number"
                      min={1}
                      value={form.order}
                      onChange={(event) => updateForm("order", Number(event.target.value) || 1)}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="tool-color">主题色</Label>
                    <div className="flex gap-2">
                      <Input
                        id="tool-color"
                        value={form.primaryColor || "#3b82f6"}
                        onChange={(event) => updateForm("primaryColor", event.target.value)}
                      />
                      <input
                        type="color"
                        aria-label="选择主题色"
                        value={form.primaryColor || "#3b82f6"}
                        onChange={(event) => updateForm("primaryColor", event.target.value)}
                        className="h-10 w-12 cursor-pointer rounded-md border border-border bg-transparent"
                      />
                    </div>
                  </div>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="tool-description">描述</Label>
                  <Textarea
                    id="tool-description"
                    value={form.description || ""}
                    maxLength={100}
                    rows={2}
                    placeholder="简短介绍，C 端超市卡片展示"
                    onChange={(event) => updateForm("description", event.target.value)}
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="tool-welcome">欢迎语</Label>
                  <Textarea
                    id="tool-welcome"
                    value={form.welcomeMessage || ""}
                    rows={2}
                    placeholder="用户首次进入聊天页时展示"
                    onChange={(event) => updateForm("welcomeMessage", event.target.value)}
                  />
                </div>

                <div className="space-y-2">
                  <Label>图标</Label>
                  <div className="flex items-start gap-4">
                    <div
                      className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-xl border border-border bg-secondary/40"
                      style={{ backgroundColor: form.primaryColor ? `${form.primaryColor}18` : undefined }}
                    >
                      {form.iconUrl ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={normalizeMediaUrl(form.iconUrl)} alt="" className="h-full w-full object-cover" />
                      ) : (
                        <Sparkles className="h-7 w-7 text-muted-foreground" />
                      )}
                    </div>
                    <div className="flex-1 space-y-2">
                      <Input
                        value={form.iconUrl}
                        placeholder="图标 URL 或上传图片"
                        onChange={(event) => updateForm("iconUrl", event.target.value)}
                      />
                      <div className="flex gap-2">
                        <input
                          ref={fileInputRef}
                          type="file"
                          accept="image/jpeg,image/png,image/svg+xml,image/webp"
                          className="hidden"
                          onChange={(event) => {
                            const file = event.target.files?.[0]
                            if (file) void handleIconUpload(file)
                            event.target.value = ""
                          }}
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={iconUploading}
                          onClick={() => fileInputRef.current?.click()}
                        >
                          <UploadCloud className="mr-1.5 h-4 w-4" />
                          {iconUploading ? "上传中..." : "上传图标"}
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>

                <div className="flex items-center justify-between rounded-lg border border-border px-4 py-3">
                  <div>
                    <p className="text-sm font-medium">C 端上架</p>
                    <p className="text-xs text-muted-foreground">关闭后用户无法在 AI 超市看到该模型</p>
                  </div>
                  <Switch checked={form.enabled} onCheckedChange={(checked) => updateForm("enabled", checked)} />
                </div>

                <CapabilityEditor
                  capabilities={form.capabilities}
                  onChange={(capabilities) => updateForm("capabilities", capabilities)}
                />
              </div>

              <DialogFooter>
                <Button variant="outline" onClick={() => setDialogOpen(false)} disabled={submitting}>
                  取消
                </Button>
                <Button onClick={() => void handleSave()} disabled={submitting}>
                  {submitting ? "保存中..." : editingTool ? "保存" : "创建"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-24 text-sm text-muted-foreground">加载中...</div>
        ) : filteredTools.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-border py-24 text-center">
            <Sparkles className="mb-4 h-12 w-12 text-muted-foreground/40" />
            <p className="text-sm text-muted-foreground">
              {searchQuery.trim() ? "没有匹配的模型" : "暂无大模型，点击「新建大模型」开始配置"}
            </p>
          </div>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {filteredTools.map((tool) => (
              <article
                key={tool.id}
                className="rounded-2xl border border-border bg-card p-5 transition hover:border-primary/30"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <div
                      className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-xl ring-1 ring-border"
                      style={{ backgroundColor: tool.primaryColor ? `${tool.primaryColor}18` : undefined }}
                    >
                      {tool.iconUrl ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={normalizeMediaUrl(tool.iconUrl)} alt="" className="h-full w-full object-cover" />
                      ) : (
                        <Sparkles className="h-5 w-5 text-primary" />
                      )}
                    </div>
                    <div className="min-w-0">
                      <h2 className="truncate font-semibold">{tool.name}</h2>
                      <p className="truncate text-xs text-muted-foreground">{tool.id}</p>
                    </div>
                  </div>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="shrink-0">
                        <MoreHorizontal className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onClick={() => openEditDialog(tool)}>
                        <Pencil className="mr-2 h-4 w-4" />
                        编辑
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        className="text-destructive focus:text-destructive"
                        disabled={deletingId === tool.id}
                        onClick={() => void handleDelete(tool)}
                      >
                        <Trash2 className="mr-2 h-4 w-4" />
                        {deletingId === tool.id ? "删除中..." : "删除"}
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>

                <p className="mt-3 line-clamp-2 min-h-[40px] text-sm text-muted-foreground">
                  {tool.description || "暂无描述"}
                </p>

                <div className="mt-3 flex flex-wrap gap-1.5">
                  {tool.capabilities.length > 0 ? (
                    tool.capabilities.map((capability, index) => (
                      <Badge key={`${capability.type}-${index}`} variant="secondary" className="text-xs">
                        {capabilityTypeLabel(capability.type)}
                      </Badge>
                    ))
                  ) : (
                    <Badge variant="outline" className="text-xs">
                      未配置能力
                    </Badge>
                  )}
                </div>

                <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                  <div className="text-xs text-muted-foreground">排序 {tool.order}</div>
                  <div className="flex items-center gap-2">
                    <span className={cn("text-xs", tool.enabled ? "text-emerald-600" : "text-muted-foreground")}>
                      {tool.enabled ? "已上架" : "已下架"}
                    </span>
                    <Switch
                      checked={tool.enabled}
                      disabled={togglingId === tool.id}
                      onCheckedChange={(checked) => void handleToggleEnabled(tool, checked)}
                    />
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}
      </div>
    </AdminLayout>
  )
}
