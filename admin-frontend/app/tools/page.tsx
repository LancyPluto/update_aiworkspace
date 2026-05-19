"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import {
  Copy,
  FileText,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  ShoppingBag,
  Sparkles,
  Store,
  Trash2,
  Video,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import {
  createTool,
  fetchAdminTools,
  fetchToolCategories,
  fetchToolFields,
  offlineTool,
  publishTool,
  updateTool,
  updateToolFields,
} from "@/lib/api/tools"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { ApiError } from "@/lib/api/http"
import type { AgentModelConfig, ToolCategory, ToolField, ToolFieldPayload, ToolSummary } from "@/lib/api/types"

interface ToolRow {
  id: string
  rawId: number
  toolCode: string
  name: string
  description: string
  category: string
  categoryId: number | null
  icon: LucideIcon
  credits: number
  status: boolean
  rawStatus: string
  modelConfigId: number | null
  modelConfigName: string | null
  modelName: string | null
}

interface ToolForm {
  toolCode: string
  toolName: string
  description: string
  categoryId: string
  estimatedCreditCost: string
  modelConfigId: string
}

const initialForm: ToolForm = {
  toolCode: "",
  toolName: "",
  description: "",
  categoryId: "",
  estimatedCreditCost: "5",
  modelConfigId: "",
}

const CATEGORY_ICON_MAP: Record<string, LucideIcon> = {
  Copywriting: FileText,
  Content: FileText,
  Video,
  Ecommerce: ShoppingBag,
  Sales: MessageSquare,
  Store,
}

function pickIcon(categoryName?: string | null): LucideIcon {
  if (!categoryName) return Sparkles
  return CATEGORY_ICON_MAP[categoryName] || Sparkles
}

function mapTool(tool: ToolSummary): ToolRow {
  return {
    id: String(tool.id),
    rawId: tool.id,
    toolCode: tool.toolCode,
    name: tool.toolName,
    description: tool.description || "No description",
    category: tool.categoryName || "Uncategorized",
    categoryId: tool.categoryId ?? null,
    icon: pickIcon(tool.categoryName),
    credits: tool.estimatedCreditCost ?? 0,
    status: (tool.status || "").toUpperCase() === "ONLINE",
    rawStatus: tool.status,
    modelConfigId: tool.modelConfigId ?? null,
    modelConfigName: tool.modelConfigName || null,
    modelName: tool.modelName || null,
  }
}

export default function ToolsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [editingTool, setEditingTool] = useState<ToolRow | null>(null)
  const [toolList, setToolList] = useState<ToolRow[]>([])
  const [categories, setCategories] = useState<ToolCategory[]>([])
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [togglingId, setTogglingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState<ToolForm>(initialForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldDialogOpen, setFieldDialogOpen] = useState(false)
  const [fieldTool, setFieldTool] = useState<ToolRow | null>(null)
  const [fieldJson, setFieldJson] = useState("[]")
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [fieldLoading, setFieldLoading] = useState(false)
  const [fieldSaving, setFieldSaving] = useState(false)

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const [toolsResp, cats] = await Promise.all([
        fetchAdminTools(),
        fetchToolCategories().catch(() => [] as ToolCategory[]),
      ])
      setToolList(toolsResp.list.map(mapTool))
      setCategories(cats)
      const configs = await fetchAgentModelConfigs().catch(() => [] as AgentModelConfig[])
      setModelConfigs(configs.filter((config) => config.enabled !== false))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load tools")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filteredTools = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return toolList
    return toolList.filter((tool) =>
      [tool.name, tool.description, tool.category].some((value) =>
        value.toLowerCase().includes(keyword),
      ),
    )
  }, [toolList, searchQuery])

  async function toggleToolStatus(id: string) {
    const target = toolList.find((tool) => tool.id === id)
    if (!target) return
    setTogglingId(target.rawId)
    try {
      const updated = target.status
        ? await offlineTool(target.rawId)
        : await publishTool(target.rawId)
      setToolList((prev) => prev.map((tool) => (tool.id === id ? mapTool(updated) : tool)))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "Failed to update tool status"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setTogglingId(null)
    }
  }

  function updateForm<K extends keyof ToolForm>(key: K, value: ToolForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function openEditDialog(tool: ToolRow) {
    setEditingTool(tool)
    setForm({
      toolCode: tool.toolCode,
      toolName: tool.name,
      description: tool.description === "No description" ? "" : tool.description,
      categoryId: tool.categoryId ? String(tool.categoryId) : "",
      estimatedCreditCost: String(tool.credits),
      modelConfigId: tool.modelConfigId ? String(tool.modelConfigId) : "",
    })
    setFormError(null)
    setIsAddDialogOpen(true)
  }

  async function handleSaveTool() {
    setFormError(null)
    if (!form.toolName.trim()) {
      setFormError("Tool name is required")
      return
    }
    if (!form.categoryId) {
      setFormError("Category is required")
      return
    }
    const credits = Number(form.estimatedCreditCost)
    if (!Number.isFinite(credits) || credits < 0) {
      setFormError("Credit cost must be a non-negative number")
      return
    }
    setSubmitting(true)
    try {
      const payload = {
        toolCode: form.toolCode.trim() || undefined,
        toolName: form.toolName.trim(),
        categoryId: Number(form.categoryId),
        description: form.description.trim() || undefined,
        estimatedCreditCost: Math.floor(credits),
        modelConfigId: form.modelConfigId ? Number(form.modelConfigId) : null,
      }
      if (editingTool) {
        const updated = await updateTool(editingTool.rawId, payload)
        setToolList((prev) => prev.map((tool) => (tool.rawId === editingTool.rawId ? mapTool(updated) : tool)))
      } else {
        const created = await createTool(payload)
        const published = await publishTool(created.id)
        setToolList((prev) => [mapTool(published), ...prev])
      }
      setForm(initialForm)
      setEditingTool(null)
      setIsAddDialogOpen(false)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "Failed to save tool")
    } finally {
      setSubmitting(false)
    }
  }

  async function openFieldDialog(tool: ToolRow) {
    setFieldTool(tool)
    setFieldDialogOpen(true)
    setFieldError(null)
    setFieldLoading(true)
    try {
      const fields = await fetchToolFields(tool.rawId)
      setFieldJson(JSON.stringify(fields.map(fieldToPayload), null, 2))
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "Failed to load fields")
      setFieldJson("[]")
    } finally {
      setFieldLoading(false)
    }
  }

  async function saveFields() {
    if (!fieldTool) return
    setFieldError(null)
    let fields: ToolFieldPayload[]
    try {
      const parsed = JSON.parse(fieldJson)
      if (!Array.isArray(parsed)) throw new Error("Field schema must be a JSON array")
      fields = parsed.map(normalizeFieldPayload)
    } catch (err) {
      setFieldError(err instanceof Error ? err.message : "Invalid field JSON")
      return
    }
    setFieldSaving(true)
    try {
      const saved = await updateToolFields(fieldTool.rawId, fields)
      setFieldJson(JSON.stringify(saved.map(fieldToPayload), null, 2))
      setFieldDialogOpen(false)
    } catch (err) {
      setFieldError(err instanceof ApiError ? err.message : "Failed to save fields")
    } finally {
      setFieldSaving(false)
    }
  }

  const headerDescription = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载工具列表..."
      : "管理 AI 工具、表单字段、模型绑定和发布状态。"

  return (
    <AdminLayout>
      <AdminHeader title="AI 工具管理" description={headerDescription} />

      <div className="space-y-6 p-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索工具..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <Dialog
            open={isAddDialogOpen}
            onOpenChange={(open) => {
              setIsAddDialogOpen(open)
              if (!open) {
                setForm(initialForm)
                setEditingTool(null)
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
                <DialogTitle>{editingTool ? "编辑 AI 工具" : "新增 AI 工具"}</DialogTitle>
                <DialogDescription>{formError || "配置工具信息和模型绑定。"}</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>工具名称</Label>
                  <Input value={form.toolName} onChange={(event) => updateForm("toolName", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>描述</Label>
                  <Textarea value={form.description} onChange={(event) => updateForm("description", event.target.value)} />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>分类</Label>
                    <Select value={form.categoryId} onValueChange={(value) => updateForm("categoryId", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="选择分类" />
                      </SelectTrigger>
                      <SelectContent>
                        {categories.map((cat) => (
                          <SelectItem key={cat.id} value={String(cat.id)}>
                            {cat.categoryName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>算力消耗</Label>
                    <Input
                      type="number"
                      value={form.estimatedCreditCost}
                      onChange={(event) => updateForm("estimatedCreditCost", event.target.value)}
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label>工具编码</Label>
                  <Input
                    value={form.toolCode}
                    onChange={(event) => updateForm("toolCode", event.target.value)}
                    placeholder="可选"
                    disabled={Boolean(editingTool)}
                  />
                </div>
                <div className="space-y-2">
                  <Label>模型配置</Label>
                  <Select value={form.modelConfigId || "default"} onValueChange={(value) => updateForm("modelConfigId", value === "default" ? "" : value)}>
                    <SelectTrigger>
                      <SelectValue placeholder="使用默认模型配置" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="default">使用默认模型配置</SelectItem>
                      {modelConfigs.map((config) => (
                        <SelectItem key={config.id} value={String(config.id)}>
                          {config.displayName || config.modelName} · {config.provider}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setIsAddDialogOpen(false)} disabled={submitting}>
                  取消
                </Button>
                <Button onClick={handleSaveTool} disabled={submitting}>
                  {submitting ? "保存中..." : editingTool ? "保存工具" : "创建工具"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

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
                    <h3 className="font-semibold text-card-foreground">{tool.name}</h3>
                    <Badge variant="secondary" className="mt-1 text-xs font-normal">{tool.category}</Badge>
                  </div>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-8 w-8 opacity-0 transition-opacity group-hover:opacity-100">
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="bg-card border-border">
                    <DropdownMenuItem className="gap-2" onClick={() => openEditDialog(tool)}>
                      <Pencil className="h-4 w-4" /> 编辑
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" onClick={() => openFieldDialog(tool)}>
                      <FileText className="h-4 w-4" /> 字段
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

              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{tool.description}</p>

              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-1 text-muted-foreground">
                    <Sparkles className="h-4 w-4" />
                    <span>{tool.credits} 算力</span>
                  </div>
                  <div className="text-muted-foreground">{tool.rawStatus}</div>
                </div>
                <Switch checked={tool.status} disabled={togglingId === tool.rawId} onCheckedChange={() => toggleToolStatus(tool.id)} />
              </div>
              <div className="mt-3 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
                模型：{tool.modelConfigName || tool.modelName || "默认模型配置"}
              </div>
            </div>
          ))}
        </div>
      </div>

      <Dialog open={fieldDialogOpen} onOpenChange={setFieldDialogOpen}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto bg-card border-border">
          <DialogHeader>
            <DialogTitle>字段 Schema</DialogTitle>
            <DialogDescription>
              {fieldTool ? `${fieldTool.name} 的用户端表单字段。` : "配置工具字段。"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            {fieldError ? (
              <div className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                {fieldError}
              </div>
            ) : null}
            <Textarea
              value={fieldJson}
              onChange={(event) => setFieldJson(event.target.value)}
              className="min-h-[420px] font-mono text-xs"
              disabled={fieldLoading || fieldSaving}
              placeholder='[{"fieldKey":"productName","fieldName":"商品名称","fieldType":"TEXT","required":true,"sortOrder":1}]'
            />
            <p className="text-xs text-muted-foreground">
              支持字段：fieldKey、fieldName、fieldType、placeholder、optionsJson、required、sortOrder。
            </p>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFieldDialogOpen(false)} disabled={fieldSaving}>
              取消
            </Button>
            <Button onClick={saveFields} disabled={fieldLoading || fieldSaving}>
              {fieldSaving ? "保存中..." : "保存字段"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}

function fieldToPayload(field: ToolField): ToolFieldPayload {
  return {
    fieldKey: field.fieldKey,
    fieldName: field.fieldName,
    fieldType: field.fieldType,
    placeholder: field.placeholder || "",
    optionsJson: field.optionsJson || "",
    required: field.required !== false,
    sortOrder: field.sortOrder ?? 1,
  }
}

function normalizeFieldPayload(field: Partial<ToolFieldPayload>, index: number): ToolFieldPayload {
  if (!field.fieldKey || !field.fieldName || !field.fieldType) {
    throw new Error(`Field ${index + 1} must include fieldKey, fieldName, and fieldType`)
  }
  return {
    fieldKey: String(field.fieldKey).trim(),
    fieldName: String(field.fieldName).trim(),
    fieldType: String(field.fieldType).trim(),
    placeholder: field.placeholder ? String(field.placeholder) : "",
    optionsJson: field.optionsJson ? String(field.optionsJson) : undefined,
    required: field.required !== false,
    sortOrder: Number(field.sortOrder ?? index + 1),
  }
}
