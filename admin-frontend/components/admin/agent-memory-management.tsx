"use client"

import { useEffect, useState } from "react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import {
  approveAgentMemory,
  deleteAgentMemory,
  fetchAgentMemory,
  rejectAgentMemory,
  updateAgentMemory,
  type AgentMemoryItem,
} from "@/lib/api/agent-memory"
import { Check, RefreshCw, Save, Search, Trash2, X } from "lucide-react"

const typeLabels: Record<string, string> = {
  user_profile: "用户画像",
  workspace_fact: "工作区事实",
  preference: "偏好",
  tool_lesson: "工具经验",
  workflow_recipe: "工作流",
  custom: "自定义",
}

function normalizeType(type: string) {
  return type === "project_knowledge" || type === "PROJECT" ? "workspace_fact" : type.toLowerCase()
}

export function AgentMemoryManagement() {
  const [items, setItems] = useState<AgentMemoryItem[]>([])
  const [total, setTotal] = useState(0)
  const [pageNo, setPageNo] = useState(1)
  const [status, setStatus] = useState("CANDIDATE")
  const [memoryType, setMemoryType] = useState("ALL")
  const [keyword, setKeyword] = useState("")
  const [userId, setUserId] = useState("")
  const [workspaceId, setWorkspaceId] = useState("")
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<AgentMemoryItem | null>(null)
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())

  async function load(nextPage = pageNo) {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchAgentMemory({
        status,
        memoryType: memoryType === "ALL" ? "" : memoryType,
        keyword,
        userId,
        workspaceId,
        pageNo: nextPage,
        pageSize: 20,
      })
      setItems(data.list)
      setTotal(data.total)
      setPageNo(nextPage)
      setSelectedIds(new Set())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载记忆失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load(1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, memoryType])

  async function action(fn: () => Promise<unknown>) {
    setSaving(true)
    setError(null)
    try {
      await fn()
      await load(pageNo)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "操作失败")
    } finally {
      setSaving(false)
    }
  }

  async function saveEdit() {
    if (!editing) return
    await action(() =>
      updateAgentMemory(editing.id, {
        memoryType: normalizeType(editing.memoryType),
        title: editing.title,
        content: editing.content,
        importance: editing.importance ?? 5,
        confidence: editing.confidence ?? 0.7,
        pinned: editing.pinned,
        tagsJson: editing.tagsJson || "",
        metadataJson: editing.metadataJson || "",
        expiresAt: editing.expiresAt || "",
      }),
    )
    setEditing(null)
  }

  function toggleSelected(id: number, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })
  }

  async function bulkAction(kind: "approve" | "reject" | "delete") {
    const ids = Array.from(selectedIds)
    if (!ids.length) return
    await action(async () => {
      if (kind === "approve") await Promise.all(ids.map((id) => approveAgentMemory(id)))
      if (kind === "reject") await Promise.all(ids.map((id) => rejectAgentMemory(id)))
      if (kind === "delete") await Promise.all(ids.map((id) => deleteAgentMemory(id)))
    })
  }

  const totalPages = Math.max(1, Math.ceil(total / 20))

  return (
    <div className="space-y-5">
      {error ? <p className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">{error}</p> : null}

      <section className="rounded-lg border bg-card p-5">
        <div className="grid gap-3 md:grid-cols-[150px_150px_150px_minmax(0,1fr)_120px_120px]">
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="CANDIDATE">候选</SelectItem>
              <SelectItem value="ACTIVE">已生效</SelectItem>
              <SelectItem value="REJECTED">已拒绝</SelectItem>
              <SelectItem value="DELETED">已删除</SelectItem>
              <SelectItem value="ALL">全部</SelectItem>
            </SelectContent>
          </Select>
          <Select value={memoryType} onValueChange={setMemoryType}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部类型</SelectItem>
              {Object.entries(typeLabels).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}
            </SelectContent>
          </Select>
          <Input placeholder="用户 ID" value={userId} onChange={(event) => setUserId(event.target.value)} />
          <Input placeholder="搜索标题或内容" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <Input placeholder="工作区 ID" value={workspaceId} onChange={(event) => setWorkspaceId(event.target.value)} />
          <Button className="gap-2" disabled={loading} onClick={() => load(1)}>
            <Search className="h-4 w-4" />
            搜索
          </Button>
        </div>
      </section>

      <section className="rounded-lg border bg-card">
        <div className="flex items-center justify-between border-b p-4">
          <p className="text-sm text-muted-foreground">共 {total} 条，当前第 {pageNo} / {totalPages} 页</p>
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="outline" size="sm" disabled={!selectedIds.size || saving} onClick={() => bulkAction("approve")}>批量转正</Button>
            <Button variant="outline" size="sm" disabled={!selectedIds.size || saving} onClick={() => bulkAction("reject")}>批量拒绝</Button>
            <Button variant="destructive" size="sm" disabled={!selectedIds.size || saving} onClick={() => bulkAction("delete")}>批量删除</Button>
            <Button variant="outline" size="sm" className="gap-2" disabled={loading} onClick={() => load(pageNo)}>
              <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
              刷新
            </Button>
          </div>
        </div>
        <div className="divide-y">
          {items.map((item) => {
            const type = normalizeType(item.memoryType)
            return (
              <div key={item.id} className="p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        aria-label={`选择记忆 ${item.id}`}
                        type="checkbox"
                        className="h-4 w-4"
                        checked={selectedIds.has(item.id)}
                        onChange={(event) => toggleSelected(item.id, event.target.checked)}
                      />
                      <p className="font-medium">{item.title}</p>
                      <Badge variant="outline">{typeLabels[type] || type}</Badge>
                      <Badge variant={item.status === "CANDIDATE" ? "secondary" : "outline"}>{item.status}</Badge>
                      {item.pinned ? <Badge>置顶</Badge> : null}
                    </div>
                    <p className="mt-2 line-clamp-3 whitespace-pre-wrap text-sm text-muted-foreground">{item.content}</p>
                    <p className="mt-2 text-xs text-muted-foreground">
                      ID {item.id} · 用户 {item.userId} · 工作区 {item.workspaceId} · 重要度 {item.importance ?? "-"} · 置信度 {item.confidence ?? "-"}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {item.status === "CANDIDATE" ? (
                      <>
                        <Button size="sm" className="gap-2" disabled={saving} onClick={() => action(() => approveAgentMemory(item.id))}>
                          <Check className="h-4 w-4" />
                          转正
                        </Button>
                        <Button size="sm" variant="outline" className="gap-2" disabled={saving} onClick={() => action(() => rejectAgentMemory(item.id))}>
                          <X className="h-4 w-4" />
                          拒绝
                        </Button>
                      </>
                    ) : null}
                    <Button size="sm" variant="outline" onClick={() => setEditing(item)}>编辑</Button>
                    <Button size="sm" variant="destructive" className="gap-2" disabled={saving} onClick={() => action(() => deleteAgentMemory(item.id))}>
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </div>
                </div>
              </div>
            )
          })}
          {!loading && items.length === 0 ? <div className="p-8 text-center text-sm text-muted-foreground">暂无记忆</div> : null}
        </div>
        <div className="flex justify-end gap-2 border-t p-4">
          <Button variant="outline" disabled={pageNo <= 1 || loading} onClick={() => load(pageNo - 1)}>上一页</Button>
          <Button variant="outline" disabled={pageNo >= totalPages || loading} onClick={() => load(pageNo + 1)}>下一页</Button>
        </div>
      </section>

      <Dialog open={Boolean(editing)} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>编辑记忆</DialogTitle>
          </DialogHeader>
          {editing ? (
            <div className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>类型</Label>
                  <Select value={normalizeType(editing.memoryType)} onValueChange={(value) => setEditing({ ...editing, memoryType: value })}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {Object.entries(typeLabels).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label>标题</Label>
                  <Input value={editing.title} onChange={(event) => setEditing({ ...editing, title: event.target.value })} />
                </div>
              </div>
              <div className="space-y-2">
                <Label>内容</Label>
                <Textarea className="min-h-36" value={editing.content} onChange={(event) => setEditing({ ...editing, content: event.target.value })} />
              </div>
              <div className="grid gap-4 md:grid-cols-3">
                <div className="space-y-2">
                  <Label>重要度</Label>
                  <Input type="number" min={0} max={10} value={editing.importance ?? 5} onChange={(event) => setEditing({ ...editing, importance: Number(event.target.value) })} />
                </div>
                <div className="space-y-2">
                  <Label>置信度</Label>
                  <Input type="number" min={0} max={1} step={0.01} value={editing.confidence ?? 0.7} onChange={(event) => setEditing({ ...editing, confidence: Number(event.target.value) })} />
                </div>
                <div className="flex items-center justify-between rounded-md bg-secondary p-4">
                  <Label>置顶</Label>
                  <Switch checked={editing.pinned} onCheckedChange={(value) => setEditing({ ...editing, pinned: value })} />
                </div>
              </div>
            </div>
          ) : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>取消</Button>
            <Button className="gap-2" disabled={saving} onClick={saveEdit}>
              <Save className={saving ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
