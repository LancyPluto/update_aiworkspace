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
  updateToolFields,
} from "@/lib/api/tools"
import { ApiError } from "@/lib/api/http"
import type { ToolCategory, ToolField, ToolFieldPayload, ToolSummary } from "@/lib/api/types"

interface ToolRow {
  id: string
  rawId: number
  name: string
  description: string
  category: string
  categoryId: number | null
  icon: LucideIcon
  credits: number
  status: boolean
  rawStatus: string
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
    name: tool.toolName,
    description: tool.description || "No description",
    category: tool.categoryName || "Uncategorized",
    categoryId: tool.categoryId ?? null,
    icon: pickIcon(tool.categoryName),
    credits: tool.estimatedCreditCost ?? 0,
    status: (tool.status || "").toUpperCase() === "ONLINE",
    rawStatus: tool.status,
  }
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

  function updateForm<K extends keyof CreateForm>(key: K, value: CreateForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleCreate() {
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
      const created = await createTool({
        toolCode: form.toolCode.trim() || undefined,
        toolName: form.toolName.trim(),
        categoryId: Number(form.categoryId),
        description: form.description.trim() || undefined,
        estimatedCreditCost: Math.floor(credits),
      })
      const published = await publishTool(created.id)
      setToolList((prev) => [mapTool(published), ...prev])
      setForm(initialForm)
      setIsAddDialogOpen(false)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "Failed to create tool")
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
    ? `Load failed: ${error}`
    : loading
      ? "Loading tool list..."
      : "Manage AI tools, tool fields, and publishing status."

  return (
    <AdminLayout>
      <AdminHeader title="AI Tool Management" description={headerDescription} />

      <div className="space-y-6 p-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search tools..."
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
                setFormError(null)
              }
            }}
          >
            <DialogTrigger asChild>
              <Button className="gap-2">
                <Plus className="h-4 w-4" />
                Add Tool
              </Button>
            </DialogTrigger>
            <DialogContent className="bg-card border-border max-w-lg">
              <DialogHeader>
                <DialogTitle>Add AI Tool</DialogTitle>
                <DialogDescription>{formError || "Create a new AI tool."}</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>Tool name</Label>
                  <Input value={form.toolName} onChange={(event) => updateForm("toolName", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>Description</Label>
                  <Textarea value={form.description} onChange={(event) => updateForm("description", event.target.value)} />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>Category</Label>
                    <Select value={form.categoryId} onValueChange={(value) => updateForm("categoryId", value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="Select category" />
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
                    <Label>Credits</Label>
                    <Input
                      type="number"
                      value={form.estimatedCreditCost}
                      onChange={(event) => updateForm("estimatedCreditCost", event.target.value)}
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label>Tool code</Label>
                  <Input value={form.toolCode} onChange={(event) => updateForm("toolCode", event.target.value)} placeholder="Optional" />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setIsAddDialogOpen(false)} disabled={submitting}>
                  Cancel
                </Button>
                <Button onClick={handleCreate} disabled={submitting}>
                  {submitting ? "Creating..." : "Create Tool"}
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
                    <DropdownMenuItem className="gap-2" disabled>
                      <Pencil className="h-4 w-4" /> Edit
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" onClick={() => openFieldDialog(tool)}>
                      <FileText className="h-4 w-4" /> Fields
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2" disabled>
                      <Copy className="h-4 w-4" /> Duplicate
                    </DropdownMenuItem>
                    <DropdownMenuItem className="gap-2 text-destructive" disabled>
                      <Trash2 className="h-4 w-4" /> Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>

              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{tool.description}</p>

              <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-1 text-muted-foreground">
                    <Sparkles className="h-4 w-4" />
                    <span>{tool.credits} credits</span>
                  </div>
                  <div className="text-muted-foreground">{tool.rawStatus}</div>
                </div>
                <Switch checked={tool.status} disabled={togglingId === tool.rawId} onCheckedChange={() => toggleToolStatus(tool.id)} />
              </div>
            </div>
          ))}
        </div>
      </div>

      <Dialog open={fieldDialogOpen} onOpenChange={setFieldDialogOpen}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto bg-card border-border">
          <DialogHeader>
            <DialogTitle>Field Schema</DialogTitle>
            <DialogDescription>
              {fieldTool ? `${fieldTool.name} user-facing form fields.` : "Configure tool fields."}
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
              placeholder='[{"fieldKey":"productName","fieldName":"Product name","fieldType":"TEXT","required":true,"sortOrder":1}]'
            />
            <p className="text-xs text-muted-foreground">
              Supported keys: fieldKey, fieldName, fieldType, placeholder, optionsJson, required, sortOrder.
            </p>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFieldDialogOpen(false)} disabled={fieldSaving}>
              Cancel
            </Button>
            <Button onClick={saveFields} disabled={fieldLoading || fieldSaving}>
              {fieldSaving ? "Saving..." : "Save fields"}
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
