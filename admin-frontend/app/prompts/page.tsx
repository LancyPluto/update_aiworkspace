"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Textarea } from "@/components/ui/textarea"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Search,
  Pencil,
  History,
  Copy,
  Eye,
  Code,
  FileText,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { fetchAdminTools } from "@/lib/api/tools"
import {
  createPromptVersion,
  fetchPrompts,
  fetchPromptVersions,
  publishPromptVersion,
} from "@/lib/api/prompts"
import { ApiError } from "@/lib/api/http"
import type {
  PromptRecord,
  PromptVersionRecord,
  ToolSummary,
} from "@/lib/api/types"

interface PromptListItem {
  id: string
  rawId: number
  toolId: number
  name: string
  tool: string
  version: string
  updatedAt: string
  status: string
  content: string
  systemPrompt: string
  outputFormat: string
}

function formatDate(iso?: string | null): string {
  if (!iso) return "-"
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toISOString().slice(0, 10)
}

function buildPromptItem(
  prompt: PromptRecord,
  versions: PromptVersionRecord[],
  toolName: string,
): PromptListItem {
  const active =
    versions.find((v) => v.id === prompt.activeVersionId) || versions[0]
  return {
    id: String(prompt.id),
    rawId: prompt.id,
    toolId: prompt.toolId,
    name: prompt.promptName,
    tool: toolName,
    version: active?.versionNo || "-",
    updatedAt: formatDate(active?.publishedAt || active?.createdAt),
    status: (prompt.status || "").toUpperCase() === "ACTIVE" ? "active" : "draft",
    content: active?.userPromptTemplate || "（暂无 Prompt 内容）",
    systemPrompt: active?.systemPrompt || "",
    outputFormat: active?.outputFormat || "text",
  }
}

function extractVariables(content: string): string[] {
  const set = new Set<string>()
  const re = /\{\{\s*([\w.-]+)\s*\}\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(content)) !== null) {
    set.add(m[1])
  }
  return Array.from(set)
}

export default function PromptsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [prompts, setPrompts] = useState<PromptListItem[]>([])
  const [versions, setVersions] = useState<PromptVersionRecord[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [versionLoading, setVersionLoading] = useState(false)
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false)
  const [editForm, setEditForm] = useState({
    versionNo: "",
    systemPrompt: "",
    userPromptTemplate: "",
    outputFormat: "text",
  })
  const [editError, setEditError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [publishingId, setPublishingId] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const toolsResp = await fetchAdminTools()
        const tools = toolsResp.list
        const promptResults = await Promise.all(
          tools.map(async (t: ToolSummary) => {
            try {
              const pr = await fetchPrompts(t.id)
              return { tool: t, prompts: pr }
            } catch {
              return { tool: t, prompts: [] as PromptRecord[] }
            }
          }),
        )
        const list: PromptListItem[] = []
        for (const { tool, prompts: pr } of promptResults) {
          for (const p of pr) {
            try {
              const vers = await fetchPromptVersions(p.id)
              list.push(buildPromptItem(p, vers, tool.toolName))
            } catch {
              list.push(buildPromptItem(p, [], tool.toolName))
            }
          }
        }
        if (cancelled) return
        setPrompts(list)
        if (list.length > 0) {
          setSelectedId(list[0].id)
        }
      } catch (err) {
        if (cancelled) return
        const message =
          err instanceof ApiError ? err.message : "加载 Prompt 列表失败"
        setError(message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  const selectedPrompt = useMemo(
    () => prompts.find((p) => p.id === selectedId) || null,
    [prompts, selectedId],
  )

  useEffect(() => {
    if (!selectedPrompt) {
      setVersions([])
      return
    }
    let cancelled = false
    async function loadVersions(promptId: number) {
      setVersionLoading(true)
      try {
        const v = await fetchPromptVersions(promptId)
        if (!cancelled) setVersions(v)
      } catch {
        if (!cancelled) setVersions([])
      } finally {
        if (!cancelled) setVersionLoading(false)
      }
    }
    loadVersions(selectedPrompt.rawId)
    return () => {
      cancelled = true
    }
  }, [selectedPrompt])

  const filteredPrompts = useMemo(() => {
    return prompts.filter(
      (prompt) =>
        prompt.name.includes(searchQuery) || prompt.tool.includes(searchQuery),
    )
  }, [prompts, searchQuery])

  const variables = useMemo(
    () => (selectedPrompt ? extractVariables(selectedPrompt.content) : []),
    [selectedPrompt],
  )

  const openEdit = () => {
    if (!selectedPrompt) return
    setEditForm({
      versionNo: `v${Date.now().toString().slice(-6)}`,
      systemPrompt: selectedPrompt.systemPrompt,
      userPromptTemplate: selectedPrompt.content,
      outputFormat: selectedPrompt.outputFormat || "text",
    })
    setEditError(null)
    setIsEditDialogOpen(true)
  }

  const handleSave = async () => {
    if (!selectedPrompt) return
    setEditError(null)
    if (!editForm.versionNo.trim()) {
      setEditError("请填写版本号")
      return
    }
    if (!editForm.userPromptTemplate.trim()) {
      setEditError("用户 Prompt 模板不能为空")
      return
    }
    setSubmitting(true)
    try {
      await createPromptVersion(selectedPrompt.rawId, {
        versionNo: editForm.versionNo.trim(),
        systemPrompt: editForm.systemPrompt || undefined,
        userPromptTemplate: editForm.userPromptTemplate,
        outputFormat: editForm.outputFormat || undefined,
      })
      const fresh = await fetchPromptVersions(selectedPrompt.rawId)
      setVersions(fresh)
      setIsEditDialogOpen(false)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "保存失败"
      setEditError(message)
    } finally {
      setSubmitting(false)
    }
  }

  const handlePublish = async (versionId: number) => {
    setPublishingId(versionId)
    try {
      await publishPromptVersion(versionId)
      if (selectedPrompt) {
        const fresh = await fetchPromptVersions(selectedPrompt.rawId)
        setVersions(fresh)
      }
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "发布失败"
      if (typeof window !== "undefined") window.alert(message)
    } finally {
      setPublishingId(null)
    }
  }

  const handleCopy = () => {
    if (!selectedPrompt || typeof navigator === "undefined") return
    navigator.clipboard?.writeText(selectedPrompt.content).catch(() => {})
  }

  const headerDescription = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载 Prompt 列表..."
      : "管理 AI 工具的 Prompt 模板和版本"

  return (
    <AdminLayout>
      <AdminHeader title="Prompt 管理" description={headerDescription} />

      <div className="flex h-[calc(100vh-4rem)]">
        {/* Left Panel - Prompt List */}
        <div className="w-80 border-r border-border p-4 space-y-4 overflow-auto">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索 Prompt..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>

          <div className="space-y-2">
            {filteredPrompts.length === 0 && !loading ? (
              <p className="text-sm text-muted-foreground px-2">暂无 Prompt</p>
            ) : null}
            {filteredPrompts.map((prompt) => (
              <button
                key={prompt.id}
                onClick={() => setSelectedId(prompt.id)}
                className={cn(
                  "w-full rounded-xl p-4 text-left transition-all duration-200",
                  selectedId === prompt.id
                    ? "bg-primary/10 border border-primary/30"
                    : "bg-card border border-border hover:border-primary/20",
                )}
              >
                <div className="flex items-start justify-between">
                  <h4 className="font-medium text-card-foreground">
                    {prompt.name}
                  </h4>
                  <Badge
                    variant="secondary"
                    className={cn(
                      "text-xs",
                      prompt.status === "active"
                        ? "bg-accent/10 text-accent"
                        : "bg-muted text-muted-foreground",
                    )}
                  >
                    {prompt.status === "active" ? "启用" : "草稿"}
                  </Badge>
                </div>
                <p className="mt-1 text-sm text-muted-foreground line-clamp-1">
                  {prompt.tool}
                </p>
                <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                  <span>{prompt.version}</span>
                  <span>·</span>
                  <span>{prompt.updatedAt}</span>
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* Right Panel - Prompt Detail */}
        <div className="flex-1 p-6 overflow-auto">
          <div className="max-w-4xl">
            {/* Header */}
            <div className="flex items-start justify-between mb-6">
              <div>
                <h2 className="text-xl font-semibold text-foreground">
                  {selectedPrompt?.name || "请选择左侧 Prompt"}
                </h2>
                <p className="text-sm text-muted-foreground mt-1">
                  {selectedPrompt
                    ? `关联工具：${selectedPrompt.tool}`
                    : "暂未选择 Prompt"}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-2"
                  disabled={!selectedPrompt || versionLoading}
                >
                  <History className="h-4 w-4" />
                  版本历史
                </Button>
                <Dialog
                  open={isEditDialogOpen}
                  onOpenChange={(open) => {
                    setIsEditDialogOpen(open)
                    if (!open) setEditError(null)
                  }}
                >
                  <DialogTrigger asChild>
                    <Button
                      size="sm"
                      className="gap-2"
                      disabled={!selectedPrompt}
                      onClick={openEdit}
                    >
                      <Pencil className="h-4 w-4" />
                      编辑
                    </Button>
                  </DialogTrigger>
                  <DialogContent className="bg-card border-border max-w-3xl">
                    <DialogHeader>
                      <DialogTitle>编辑 Prompt</DialogTitle>
                      <DialogDescription>
                        {editError
                          ? editError
                          : "修改 Prompt 内容，保存后将创建新版本"}
                      </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-4 py-4">
                      <div className="space-y-2">
                        <Label>版本号</Label>
                        <Input
                          value={editForm.versionNo}
                          onChange={(e) =>
                            setEditForm((s) => ({
                              ...s,
                              versionNo: e.target.value,
                            }))
                          }
                          className="bg-secondary border-0"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label>System Prompt（可选）</Label>
                        <Textarea
                          value={editForm.systemPrompt}
                          onChange={(e) =>
                            setEditForm((s) => ({
                              ...s,
                              systemPrompt: e.target.value,
                            }))
                          }
                          className="bg-secondary border-0 min-h-[80px] font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label>用户 Prompt 模板</Label>
                        <Textarea
                          value={editForm.userPromptTemplate}
                          onChange={(e) =>
                            setEditForm((s) => ({
                              ...s,
                              userPromptTemplate: e.target.value,
                            }))
                          }
                          className="bg-secondary border-0 min-h-[260px] font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label>输出格式</Label>
                        <Select
                          value={editForm.outputFormat}
                          onValueChange={(v) =>
                            setEditForm((s) => ({ ...s, outputFormat: v }))
                          }
                        >
                          <SelectTrigger className="bg-secondary border-0">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent className="bg-card border-border">
                            <SelectItem value="text">纯文本</SelectItem>
                            <SelectItem value="json">JSON</SelectItem>
                            <SelectItem value="markdown">Markdown</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                    </div>
                    <DialogFooter>
                      <Button
                        variant="outline"
                        onClick={() => setIsEditDialogOpen(false)}
                        disabled={submitting}
                      >
                        取消
                      </Button>
                      <Button onClick={handleSave} disabled={submitting}>
                        {submitting ? "保存中..." : "保存并创建新版本"}
                      </Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
              </div>
            </div>

            {/* Tabs */}
            <Tabs defaultValue="content" className="space-y-4">
              <TabsList className="bg-secondary">
                <TabsTrigger value="content" className="gap-2">
                  <FileText className="h-4 w-4" />
                  Prompt 内容
                </TabsTrigger>
                <TabsTrigger value="variables" className="gap-2">
                  <Code className="h-4 w-4" />
                  变量配置
                </TabsTrigger>
                <TabsTrigger value="versions" className="gap-2">
                  <History className="h-4 w-4" />
                  版本记录
                </TabsTrigger>
              </TabsList>

              <TabsContent value="content" className="space-y-4">
                <div className="rounded-xl border border-border bg-card p-6">
                  <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center gap-2">
                      <Badge variant="secondary">
                        {selectedPrompt?.version || "-"}
                      </Badge>
                      <span className="text-sm text-muted-foreground">
                        更新于 {selectedPrompt?.updatedAt || "-"}
                      </span>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="gap-2"
                      onClick={handleCopy}
                      disabled={!selectedPrompt}
                    >
                      <Copy className="h-4 w-4" />
                      复制
                    </Button>
                  </div>
                  <pre className="whitespace-pre-wrap text-sm text-muted-foreground font-mono bg-secondary rounded-lg p-4">
                    {selectedPrompt?.content || "（请选择左侧 Prompt 查看内容）"}
                  </pre>
                </div>
              </TabsContent>

              <TabsContent value="variables" className="space-y-4">
                <div className="rounded-xl border border-border bg-card p-6">
                  <h4 className="font-medium mb-4">变量列表</h4>
                  <div className="space-y-3">
                    {variables.length === 0 ? (
                      <p className="text-sm text-muted-foreground">
                        Prompt 中暂未发现 {`{{变量}}`} 占位符
                      </p>
                    ) : (
                      variables.map((variable) => (
                        <div
                          key={variable}
                          className="flex items-center justify-between rounded-lg bg-secondary p-3"
                        >
                          <div className="flex items-center gap-3">
                            <code className="rounded bg-primary/10 px-2 py-1 text-sm text-primary">
                              {`{{${variable}}}`}
                            </code>
                            <span className="text-sm text-muted-foreground">
                              用户输入字段
                            </span>
                          </div>
                          <Button variant="ghost" size="sm" disabled>
                            配置
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </TabsContent>

              <TabsContent value="versions" className="space-y-4">
                <div className="rounded-xl border border-border bg-card overflow-hidden">
                  <div className="divide-y divide-border">
                    {versionLoading ? (
                      <div className="p-4 text-sm text-muted-foreground">
                        正在加载版本…
                      </div>
                    ) : versions.length === 0 ? (
                      <div className="p-4 text-sm text-muted-foreground">
                        暂无版本记录
                      </div>
                    ) : (
                      versions.map((ver) => {
                        const isCurrent =
                          selectedPrompt?.version === ver.versionNo &&
                          (ver.status || "").toUpperCase() === "ACTIVE"
                        return (
                          <div
                            key={ver.id}
                            className="flex items-center justify-between p-4 hover:bg-secondary/50 transition-colors"
                          >
                            <div className="flex items-center gap-4">
                              <Badge
                                variant={isCurrent ? "default" : "secondary"}
                              >
                                {ver.versionNo}
                              </Badge>
                              <span className="text-sm text-muted-foreground">
                                {formatDate(ver.publishedAt || ver.createdAt)}
                              </span>
                              <span className="text-sm text-muted-foreground">
                                {ver.status}
                              </span>
                            </div>
                            <div className="flex items-center gap-2">
                              <Button
                                variant="ghost"
                                size="sm"
                                className="gap-2"
                                disabled
                              >
                                <Eye className="h-4 w-4" />
                                查看
                              </Button>
                              {!isCurrent && (
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={publishingId === ver.id}
                                  onClick={() => handlePublish(ver.id)}
                                >
                                  {publishingId === ver.id ? "发布中..." : "发布"}
                                </Button>
                              )}
                            </div>
                          </div>
                        )
                      })
                    )}
                  </div>
                </div>
              </TabsContent>
            </Tabs>
          </div>
        </div>
      </div>
    </AdminLayout>
  )
}
