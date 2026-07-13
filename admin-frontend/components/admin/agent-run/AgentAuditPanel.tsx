"use client"

import { useEffect, useMemo, useState } from "react"
import { AlertTriangle, CheckCircle2, ChevronDown, Loader2, Save } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import {
  fetchAdminAgentModelRequests,
  fetchAdminAgentRunAudit,
  fetchAdminAgentSkillCoverage,
  updateAdminAgentAuditReview,
} from "@/lib/api/agent-runs"
import type {
  AgentAuditCategory,
  AgentAuditEvidenceEvent,
  AgentModelRequestSnapshot,
  AgentRunAudit,
  AgentSkillCoverage,
} from "@/lib/api/types"

type AuditView = "requests" | "skills" | "diagnosis"
const PAGE_SIZE = 50
const CATEGORIES: Array<{ value: AgentAuditCategory; label: string }> = [
  { value: "CONTEXT_INCOMPLETE", label: "上下文不完整" },
  { value: "TOOL_NOT_VISIBLE", label: "工具不可见" },
  { value: "DISCLOSURE_MISS", label: "渐进披露遗漏" },
  { value: "SKILL_MISSING", label: "Skill 缺失" },
  { value: "TOOL_SELECTION_MISMATCH", label: "工具选型错误" },
  { value: "ARGUMENT_SCHEMA_ERROR", label: "参数 Schema 错误" },
  { value: "EXECUTION_FAILURE", label: "执行失败" },
  { value: "UNDETERMINED", label: "证据不足" },
]

export function AgentAuditPanel({ runId, view }: { runId: number; view: AuditView }) {
  const [audit, setAudit] = useState<AgentRunAudit | null>(null)
  const [requests, setRequests] = useState<AgentModelRequestSnapshot[]>([])
  const [coverage, setCoverage] = useState<AgentSkillCoverage[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [hasMore, setHasMore] = useState(false)
  const [saving, setSaving] = useState(false)
  const [expectedToolCode, setExpectedToolCode] = useState("")
  const [finalCategory, setFinalCategory] = useState<AgentAuditCategory>("UNDETERMINED")
  const [reviewNote, setReviewNote] = useState("")

  useEffect(() => {
    let active = true
    setLoading(true)
    setError("")
    Promise.all([
      fetchAdminAgentRunAudit(runId),
      view === "requests" ? fetchAdminAgentModelRequests(runId, undefined, PAGE_SIZE) : Promise.resolve([]),
      view === "skills" ? fetchAdminAgentSkillCoverage() : Promise.resolve([]),
    ])
      .then(([nextAudit, nextRequests, nextCoverage]) => {
        if (!active) return
        setAudit(nextAudit)
        setRequests(nextRequests)
        setCoverage(nextCoverage)
        setHasMore(nextRequests.length === PAGE_SIZE)
        setExpectedToolCode(nextAudit.review?.expectedToolCode || "")
        setFinalCategory(nextAudit.review?.finalCategory || nextAudit.diagnosis.category)
        setReviewNote(nextAudit.review?.reviewNote || "")
      })
      .catch((reason: Error) => active && setError(reason.message || "稽查数据加载失败"))
      .finally(() => active && setLoading(false))
    return () => { active = false }
  }, [runId, view])

  const effectiveDiagnosis = audit?.review?.finalCategory || audit?.diagnosis.category
  const selectedRequestIds = useMemo(() => new Set(requests.map((item) => item.id)), [requests])

  async function loadMore() {
    const afterId = requests.at(-1)?.id
    const next = await fetchAdminAgentModelRequests(runId, afterId, PAGE_SIZE)
    setRequests((current) => [...current, ...next.filter((item) => !selectedRequestIds.has(item.id))])
    setHasMore(next.length === PAGE_SIZE)
  }

  async function saveReview() {
    setSaving(true)
    setError("")
    try {
      const review = await updateAdminAgentAuditReview(runId, { expectedToolCode, finalCategory, reviewNote })
      setAudit((current) => current ? { ...current, review } : current)
    } catch (reason) {
      setError((reason as Error).message || "人工复核保存失败")
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" />加载稽查证据</div>
  if (error && !audit) return <p className="text-sm text-destructive">{error}</p>
  if (!audit) return <p className="text-sm text-muted-foreground">暂无稽查记录</p>

  if (view === "requests") {
    return (
      <div className="space-y-4">
        <AuditChain audit={audit} />
        <section className="space-y-3">
          <div className="flex items-center justify-between gap-3">
            <h3 className="text-sm font-medium">模型请求快照</h3>
            <Badge variant="outline">{audit.modelRequestCount} 次</Badge>
          </div>
          {requests.length === 0 ? <p className="text-sm text-muted-foreground">该 Run 尚无模型请求快照</p> : requests.map((request, index) => (
            <RequestSnapshot key={request.id} request={request} previous={requests[index - 1]} />
          ))}
          {hasMore ? <Button variant="outline" size="sm" onClick={loadMore}><ChevronDown />加载更多</Button> : null}
        </section>
      </div>
    )
  }

  if (view === "skills") {
    return (
      <div className="space-y-5">
        <EvidenceList title="本次渐进披露" events={audit.disclosureEvents} />
        <EvidenceList title="本次 Skill 水合" events={audit.skillEvents} />
        <section>
          <h3 className="mb-3 text-sm font-medium">Skill 覆盖矩阵</h3>
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full min-w-[680px] text-left text-sm">
              <thead className="bg-muted/40 text-muted-foreground"><tr><th className="p-3">Skill</th><th className="p-3">版本/状态</th><th className="p-3">关联工具</th><th className="p-3 text-right">近期水合</th></tr></thead>
              <tbody>{coverage.map((item) => <tr key={item.skillCode} className="border-t"><td className="p-3"><p className="font-medium">{item.displayName || item.skillCode}</p>{item.status === "UNMAPPED" ? <Badge variant="destructive">未配置 Skill</Badge> : <code className="text-xs text-muted-foreground">{item.skillCode}</code>}</td><td className="p-3">{item.status === "UNMAPPED" ? "-" : `v${item.version || "-"} · ${item.status}`}</td><td className="p-3">{item.toolCodes.join(", ") || "-"}</td><td className="p-3 text-right tabular-nums">{item.recentHydrationCount}</td></tr>)}</tbody>
            </table>
          </div>
        </section>
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <section className="rounded-md border p-4">
        <div className="flex flex-wrap items-center gap-2"><Badge variant={effectiveDiagnosis === "UNDETERMINED" ? "outline" : "secondary"}>{effectiveDiagnosis}</Badge><span className="font-medium">{audit.review?.finalCategory ? "人工结论" : audit.diagnosis.summary}</span></div>
        <ul className="mt-3 space-y-1 text-sm text-muted-foreground">{audit.diagnosis.evidence.length ? audit.diagnosis.evidence.map((item) => <li key={item}>• {item}</li>) : <li>暂无足够证据，建议结合请求快照人工复核。</li>}</ul>
      </section>
      <section className="space-y-3 rounded-md border p-4">
        <h3 className="text-sm font-medium">人工复核</h3>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">预期工具</span><input className="h-9 w-full rounded-md border bg-transparent px-3" value={expectedToolCode} onChange={(event) => setExpectedToolCode(event.target.value)} placeholder="例如 gpt_image2" /></label>
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">最终分类</span><Select value={finalCategory} onValueChange={(value) => setFinalCategory(value as AgentAuditCategory)}><SelectTrigger className="w-full"><SelectValue /></SelectTrigger><SelectContent>{CATEGORIES.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}</SelectContent></Select></label>
        </div>
        <Textarea value={reviewNote} onChange={(event) => setReviewNote(event.target.value)} placeholder="记录判断依据和后续处理建议" rows={4} />
        {error ? <p className="text-sm text-destructive">{error}</p> : null}
        <Button size="sm" onClick={saveReview} disabled={saving}>{saving ? <Loader2 className="animate-spin" /> : <Save />}保存复核</Button>
      </section>
    </div>
  )
}

function AuditChain({ audit }: { audit: AgentRunAudit }) {
  const steps = [
    ["输入事实", !audit.inputSnapshotExpired], ["可见工具", Boolean(audit.inputSnapshot)], ["渐进披露", audit.disclosureEvents.length > 0],
    ["Skill 水合", audit.skillEvents.length > 0], ["模型请求", audit.modelRequestCount > 0], ["工具参数", true], ["执行结果", true],
  ] as const
  return <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-7">{steps.map(([label, present]) => <div key={label} className="rounded-md border p-3 text-sm"><div className="flex items-center gap-2">{present ? <CheckCircle2 className="size-4 text-emerald-500" /> : <AlertTriangle className="size-4 text-amber-500" />}<span>{label}</span></div></div>)}</div>
}

function RequestSnapshot({ request, previous }: { request: AgentModelRequestSnapshot; previous?: AgentModelRequestSnapshot }) {
  const delta = previous ? request.estimatedInputTokens - previous.estimatedInputTokens : null
  return <details className="rounded-md border"><summary className="flex cursor-pointer list-none flex-wrap items-center gap-2 p-3"><Badge variant="outline">#{request.requestSequence}</Badge><span className="font-medium">{request.requestStage}</span><span className="text-xs text-muted-foreground">{request.modelProviderCode}/{request.modelName} · {request.messageCount} 消息 · {request.toolCount} 工具 · 约 {request.estimatedInputTokens} tokens</span>{delta !== null ? <Badge variant="secondary">较上次 {delta >= 0 ? "+" : ""}{delta}</Badge> : null}{request.payloadExpired ? <Badge variant="destructive">正文已过期</Badge> : null}</summary><div className="border-t p-3"><pre className="max-h-[420px] overflow-auto whitespace-pre-wrap break-all rounded bg-muted/30 p-3 text-xs">{request.payload ? JSON.stringify(request.payload, null, 2) : `正文已清理，SHA-256: ${request.payloadSha256}`}</pre></div></details>
}

function EvidenceList({ title, events }: { title: string; events: AgentAuditEvidenceEvent[] }) {
  return <section><div className="mb-3 flex items-center gap-2"><h3 className="text-sm font-medium">{title}</h3><Badge variant="outline">{events.length}</Badge></div>{events.length === 0 ? <p className="text-sm text-muted-foreground">无事件记录</p> : <div className="space-y-2">{events.map((event) => <details key={event.id} className="rounded-md border p-3"><summary className="cursor-pointer text-sm font-medium">{event.eventType} · #{event.id}</summary><pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-all bg-muted/30 p-3 text-xs">{JSON.stringify(event.payload, null, 2)}</pre></details>)}</div>}</section>
}
