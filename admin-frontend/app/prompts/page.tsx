"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"
import { ApiError } from "@/lib/api/http"
import { fetchAdminTools } from "@/lib/api/tools"
import { createPrompt, createPromptVersion, fetchPrompts, fetchPromptVersions, publishPromptVersion, testGenerate } from "@/lib/api/prompts"
import type { PromptRecord, PromptVersionRecord, ToolSummary } from "@/lib/api/types"
import { Copy, Eye, FileText, History, Plus, RefreshCw, Rocket, Search, Sparkles } from "lucide-react"

interface PromptItem {
  prompt: PromptRecord
  tool: ToolSummary
  versions: PromptVersionRecord[]
}

interface VersionForm {
  versionNo: string
  systemPrompt: string
  userPromptTemplate: string
  outputFormat: string
}

function formatDate(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function extractVariables(content: string): string[] {
  const names = new Set<string>()
  const pattern = /\{\{\s*([\w.-]+)\s*\}\}/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(content)) !== null) {
    names.add(match[1])
  }
  return Array.from(names)
}

function activeVersion(item: PromptItem | null) {
  if (!item) return null
  return (
    item.versions.find((version) => version.id === item.prompt.activeVersionId) ||
    item.versions.find((version) => version.status === "ACTIVE") ||
    item.versions[0] ||
    null
  )
}

function defaultVersionNo() {
  return `v${new Date().toISOString().replace(/\D/g, "").slice(0, 12)}`
}

export default function PromptsPage() {
  const [tools, setTools] = useState<ToolSummary[]>([])
  const [items, setItems] = useState<PromptItem[]>([])
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null)
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [versionOpen, setVersionOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [publishingId, setPublishingId] = useState<number | null>(null)
  const [selectedToolId, setSelectedToolId] = useState("")
  const [promptCode, setPromptCode] = useState("")
  const [promptName, setPromptName] = useState("")
  const [form, setForm] = useState<VersionForm>({
    versionNo: defaultVersionNo(),
    systemPrompt: "",
    userPromptTemplate: "",
    outputFormat: "MARKDOWN",
  })
  const [previewParams, setPreviewParams] = useState<Record<string, string>>({})
  const [previewOutput, setPreviewOutput] = useState("")

  async function loadAll(preferredPromptId?: number) {
    setLoading(true)
    setError(null)
    try {
      const toolsResp = await fetchAdminTools()
      setTools(toolsResp.list)
      const loaded = await Promise.all(
        toolsResp.list.map(async (tool) => {
          const prompts = await fetchPrompts(tool.id).catch(() => [] as PromptRecord[])
          const promptItems = await Promise.all(
            prompts.map(async (prompt) => ({
              prompt,
              tool,
              versions: await fetchPromptVersions(prompt.id).catch(() => [] as PromptVersionRecord[]),
            })),
          )
          return promptItems
        }),
      )
      const flattened = loaded.flat()
      setItems(flattened)
      const nextSelected = preferredPromptId ?? selectedPromptId ?? flattened[0]?.prompt.id ?? null
      setSelectedPromptId(flattened.some((item) => item.prompt.id === nextSelected) ? nextSelected : flattened[0]?.prompt.id ?? null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Prompt 数据失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const filteredItems = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return items
    return items.filter((item) =>
      [item.prompt.promptName, item.prompt.promptCode, item.tool.toolName, item.tool.toolCode]
        .some((value) => value.toLowerCase().includes(keyword)),
    )
  }, [items, searchQuery])

  const selectedItem = useMemo(
    () => items.find((item) => item.prompt.id === selectedPromptId) ?? null,
    [items, selectedPromptId],
  )
  const selectedVersion = activeVersion(selectedItem)
  const variables = useMemo(
    () => extractVariables(selectedVersion?.userPromptTemplate ?? form.userPromptTemplate),
    [selectedVersion, form.userPromptTemplate],
  )

  function updateVersionForm<K extends keyof VersionForm>(key: K, value: VersionForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function openVersionDialog() {
    setForm({
      versionNo: defaultVersionNo(),
      systemPrompt: selectedVersion?.systemPrompt ?? "",
      userPromptTemplate: selectedVersion?.userPromptTemplate ?? "",
      outputFormat: selectedVersion?.outputFormat ?? "MARKDOWN",
    })
    setVersionOpen(true)
  }

  async function handleCreatePrompt() {
    const toolId = Number(selectedToolId)
    if (!toolId || !promptCode.trim() || !promptName.trim()) {
      setError("请选择工具，并填写 Prompt 编码和名称")
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const created = await createPrompt(toolId, {
        promptCode: promptCode.trim(),
        promptName: promptName.trim(),
      })
      setCreateOpen(false)
      setSelectedToolId("")
      setPromptCode("")
      setPromptName("")
      await loadAll(created.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "创建 Prompt 失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function handleCreateVersion() {
    if (!selectedItem) return
    if (!form.versionNo.trim() || !form.userPromptTemplate.trim()) {
      setError("请填写版本号和用户 Prompt 模板")
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await createPromptVersion(selectedItem.prompt.id, {
        versionNo: form.versionNo.trim(),
        systemPrompt: form.systemPrompt.trim() || undefined,
        userPromptTemplate: form.userPromptTemplate,
        outputFormat: form.outputFormat,
      })
      setVersionOpen(false)
      await loadAll(selectedItem.prompt.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "创建版本失败")
    } finally {
      setSubmitting(false)
    }
  }

  async function handlePublish(versionId: number) {
    if (!selectedItem) return
    setPublishingId(versionId)
    setError(null)
    try {
      await publishPromptVersion(versionId)
      await loadAll(selectedItem.prompt.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "发布版本失败")
    } finally {
      setPublishingId(null)
    }
  }

  async function handlePreview(versionId: number) {
    setSubmitting(true)
    setPreviewOutput("")
    setError(null)
    try {
      const result = await testGenerate(versionId, previewParams)
      setPreviewOutput(result.output)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "预览生成失败")
    } finally {
      setSubmitting(false)
    }
  }

  function copyContent() {
    const content = selectedVersion?.userPromptTemplate
    if (!content) return
    navigator.clipboard?.writeText(content).catch(() => undefined)
  }

  const description = error
    ? `联调异常：${error}`
    : loading
      ? "正在从后端加载工具、Prompt 与版本数据"
      : "创建 Prompt、管理版本、发布生效版本并进行模板预览"

  return (
    <AdminLayout>
      <AdminHeader title="Prompt 管理" description={description} />

      <div className="grid min-h-[calc(100vh-5rem)] grid-cols-[320px_1fr] border-t border-border">
        <aside className="border-r border-border p-4">
          <div className="mb-4 flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="搜索 Prompt 或工具"
                className="pl-9"
              />
            </div>
            <Button variant="outline" size="icon" onClick={() => loadAll(selectedPromptId ?? undefined)} disabled={loading}>
              <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
            </Button>
          </div>

          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger asChild>
              <Button className="mb-4 w-full gap-2">
                <Plus className="h-4 w-4" />
                新建 Prompt
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>新建 Prompt</DialogTitle>
                <DialogDescription>选择一个 AI 工具，为它创建可版本化的 Prompt。</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-2">
                <div className="space-y-2">
                  <Label>所属工具</Label>
                  <Select value={selectedToolId} onValueChange={setSelectedToolId}>
                    <SelectTrigger>
                      <SelectValue placeholder="选择工具" />
                    </SelectTrigger>
                    <SelectContent>
                      {tools.map((tool) => (
                        <SelectItem key={tool.id} value={String(tool.id)}>
                          {tool.toolName}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label>Prompt 编码</Label>
                  <Input value={promptCode} onChange={(event) => setPromptCode(event.target.value)} placeholder="default" />
                </div>
                <div className="space-y-2">
                  <Label>Prompt 名称</Label>
                  <Input value={promptName} onChange={(event) => setPromptName(event.target.value)} placeholder="默认生成 Prompt" />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setCreateOpen(false)} disabled={submitting}>取消</Button>
                <Button onClick={handleCreatePrompt} disabled={submitting}>{submitting ? "创建中..." : "创建"}</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>

          <div className="space-y-2">
            {filteredItems.length === 0 ? (
              <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
                {loading ? "加载中..." : "暂无 Prompt"}
              </div>
            ) : (
              filteredItems.map((item) => {
                const active = activeVersion(item)
                const isSelected = item.prompt.id === selectedPromptId
                return (
                  <button
                    key={item.prompt.id}
                    onClick={() => setSelectedPromptId(item.prompt.id)}
                    className={cn(
                      "w-full rounded-lg border p-3 text-left transition",
                      isSelected ? "border-primary bg-primary/10" : "border-border bg-card hover:border-primary/40",
                    )}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold">{item.prompt.promptName}</p>
                        <p className="mt-1 truncate text-xs text-muted-foreground">{item.tool.toolName}</p>
                      </div>
                      <Badge variant={active?.status === "ACTIVE" ? "default" : "secondary"}>
                        {active?.status === "ACTIVE" ? "已发布" : "草稿"}
                      </Badge>
                    </div>
                    <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground">
                      <span>{item.prompt.promptCode}</span>
                      <span>{active?.versionNo ?? "无版本"}</span>
                    </div>
                  </button>
                )
              })
            )}
          </div>
        </aside>

        <main className="p-6">
          {!selectedItem ? (
            <div className="flex h-full items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted-foreground">
              请选择或创建一个 Prompt
            </div>
          ) : (
            <div className="space-y-6">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="text-xl font-semibold">{selectedItem.prompt.promptName}</h2>
                    <Badge variant="secondary">{selectedItem.prompt.promptCode}</Badge>
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">
                    工具：{selectedItem.tool.toolName} · 当前版本：{selectedVersion?.versionNo ?? "未创建"}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button variant="outline" className="gap-2" onClick={copyContent} disabled={!selectedVersion}>
                    <Copy className="h-4 w-4" />
                    复制
                  </Button>
                  <Button className="gap-2" onClick={openVersionDialog}>
                    <Plus className="h-4 w-4" />
                    新建版本
                  </Button>
                </div>
              </div>

              <Tabs defaultValue="content">
                <TabsList>
                  <TabsTrigger value="content" className="gap-2"><FileText className="h-4 w-4" />内容</TabsTrigger>
                  <TabsTrigger value="preview" className="gap-2"><Sparkles className="h-4 w-4" />预览</TabsTrigger>
                  <TabsTrigger value="versions" className="gap-2"><History className="h-4 w-4" />版本</TabsTrigger>
                </TabsList>

                <TabsContent value="content" className="mt-4 space-y-4">
                  <section className="rounded-lg border border-border bg-card p-5">
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="font-semibold">System Prompt</h3>
                      <span className="text-xs text-muted-foreground">{formatDate(selectedVersion?.publishedAt ?? selectedVersion?.createdAt)}</span>
                    </div>
                    <pre className="min-h-20 whitespace-pre-wrap rounded-md bg-secondary p-4 text-sm">
                      {selectedVersion?.systemPrompt || "未配置 System Prompt"}
                    </pre>
                  </section>
                  <section className="rounded-lg border border-border bg-card p-5">
                    <h3 className="mb-3 font-semibold">User Prompt 模板</h3>
                    <pre className="min-h-60 whitespace-pre-wrap rounded-md bg-secondary p-4 text-sm">
                      {selectedVersion?.userPromptTemplate || "请先创建 Prompt 版本"}
                    </pre>
                  </section>
                </TabsContent>

                <TabsContent value="preview" className="mt-4">
                  <section className="rounded-lg border border-border bg-card p-5">
                    <div className="mb-4 flex items-center justify-between">
                      <div>
                        <h3 className="font-semibold">模板变量预览</h3>
                        <p className="text-sm text-muted-foreground">变量来自模板中的 {`{{变量名}}`}，提交后调用后端预览接口。</p>
                      </div>
                      <Button
                        className="gap-2"
                        disabled={!selectedVersion || submitting}
                        onClick={() => selectedVersion && handlePreview(selectedVersion.id)}
                      >
                        <Eye className="h-4 w-4" />
                        {submitting ? "生成中..." : "生成预览"}
                      </Button>
                    </div>
                    <div className="grid gap-4 lg:grid-cols-2">
                      <div className="space-y-3">
                        {variables.length === 0 ? (
                          <p className="rounded-md bg-secondary p-4 text-sm text-muted-foreground">当前模板没有变量。</p>
                        ) : (
                          variables.map((name) => (
                            <div key={name} className="space-y-2">
                              <Label>{`{{${name}}}`}</Label>
                              <Input
                                value={previewParams[name] ?? ""}
                                onChange={(event) => setPreviewParams((current) => ({ ...current, [name]: event.target.value }))}
                                placeholder={`输入 ${name}`}
                              />
                            </div>
                          ))
                        )}
                      </div>
                      <pre className="min-h-52 whitespace-pre-wrap rounded-md bg-secondary p-4 text-sm">
                        {previewOutput || "预览结果会显示在这里"}
                      </pre>
                    </div>
                  </section>
                </TabsContent>

                <TabsContent value="versions" className="mt-4">
                  <div className="overflow-hidden rounded-lg border border-border bg-card">
                    {selectedItem.versions.length === 0 ? (
                      <div className="p-6 text-sm text-muted-foreground">暂无版本，点击右上角新建版本。</div>
                    ) : (
                      <div className="divide-y divide-border">
                        {selectedItem.versions.map((version) => {
                          const isActive = version.id === selectedItem.prompt.activeVersionId || version.status === "ACTIVE"
                          return (
                            <div key={version.id} className="flex items-center justify-between gap-4 p-4">
                              <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                  <Badge variant={isActive ? "default" : "secondary"}>{version.versionNo}</Badge>
                                  <span className="text-sm text-muted-foreground">{version.status}</span>
                                  <span className="text-sm text-muted-foreground">{version.outputFormat}</span>
                                </div>
                                <p className="mt-2 truncate text-sm text-muted-foreground">{version.userPromptTemplate}</p>
                              </div>
                              <div className="flex items-center gap-2">
                                <Button variant="outline" size="sm" onClick={() => {
                                  setPreviewParams({})
                                  setPreviewOutput("")
                                  setPreviewOpen(true)
                                }}>
                                  查看
                                </Button>
                                <Button
                                  size="sm"
                                  className="gap-2"
                                  disabled={isActive || publishingId === version.id}
                                  onClick={() => handlePublish(version.id)}
                                >
                                  <Rocket className="h-4 w-4" />
                                  {publishingId === version.id ? "发布中..." : isActive ? "已发布" : "发布"}
                                </Button>
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                </TabsContent>
              </Tabs>
            </div>
          )}
        </main>
      </div>

      <Dialog open={versionOpen} onOpenChange={setVersionOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>新建 Prompt 版本</DialogTitle>
            <DialogDescription>保存后会生成草稿版本，需要发布后才会成为工具生效版本。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label>版本号</Label>
                <Input value={form.versionNo} onChange={(event) => updateVersionForm("versionNo", event.target.value)} />
              </div>
              <div className="space-y-2">
                <Label>输出格式</Label>
                <Select value={form.outputFormat} onValueChange={(value) => updateVersionForm("outputFormat", value)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="MARKDOWN">Markdown</SelectItem>
                    <SelectItem value="TEXT">纯文本</SelectItem>
                    <SelectItem value="JSON">JSON</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <Label>System Prompt</Label>
              <Textarea value={form.systemPrompt} onChange={(event) => updateVersionForm("systemPrompt", event.target.value)} className="min-h-24 font-mono text-sm" />
            </div>
            <div className="space-y-2">
              <Label>User Prompt 模板</Label>
              <Textarea value={form.userPromptTemplate} onChange={(event) => updateVersionForm("userPromptTemplate", event.target.value)} className="min-h-72 font-mono text-sm" />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setVersionOpen(false)} disabled={submitting}>取消</Button>
            <Button onClick={handleCreateVersion} disabled={submitting}>{submitting ? "保存中..." : "保存草稿版本"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={previewOpen} onOpenChange={setPreviewOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>版本内容</DialogTitle>
            <DialogDescription>可在内容页复制当前生效模板，历史版本可通过发布切换。</DialogDescription>
          </DialogHeader>
          <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-md bg-secondary p-4 text-sm">
            {selectedVersion?.userPromptTemplate || "暂无内容"}
          </pre>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}
