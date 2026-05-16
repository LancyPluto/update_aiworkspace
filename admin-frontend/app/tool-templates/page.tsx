"use client"

import { useEffect, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Badge } from "@/components/ui/badge"
import { fetchToolTemplateDetail, fetchToolTemplates } from "@/lib/api/tool-templates"
import { ApiError } from "@/lib/api/http"
import type { ToolTemplateDetail, ToolTemplateSummary } from "@/lib/api/tool-templates"

export default function ToolTemplatesPage() {
  const [templates, setTemplates] = useState<ToolTemplateSummary[]>([])
  const [selected, setSelected] = useState<ToolTemplateDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const list = await fetchToolTemplates()
        if (!cancelled) {
          setTemplates(list)
          if (list.length > 0) {
            const detail = await fetchToolTemplateDetail(list[0].templateCode)
            if (!cancelled) setSelected(detail)
          }
        }
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : "加载模板失败")
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  async function selectTemplate(code: string) {
    try {
      const detail = await fetchToolTemplateDetail(code)
      setSelected(detail)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载模板详情失败")
    }
  }

  return (
    <AdminLayout>
      <AdminHeader
        title="工具模板"
        description={error || (loading ? "加载中..." : "可复用蓝图：字段、模态、Prompt 与 Worker 路由")}
      />
      <div className="grid gap-6 p-6 lg:grid-cols-[320px_1fr]">
        <div className="space-y-2 rounded-xl border border-border bg-card p-3">
          {templates.map((item) => (
            <button
              key={item.templateCode}
              type="button"
              onClick={() => selectTemplate(item.templateCode)}
              className={`w-full rounded-lg px-3 py-2 text-left text-sm transition-colors ${
                selected?.templateCode === item.templateCode
                  ? "bg-secondary text-foreground"
                  : "hover:bg-secondary/60 text-muted-foreground"
              }`}
            >
              <p className="font-medium text-foreground">{item.templateName}</p>
              <p className="mt-1 text-xs text-muted-foreground">{item.templateCode}</p>
              <div className="mt-2 flex flex-wrap gap-1">
                <Badge variant="outline">{item.executionHandler}</Badge>
                {item.systemTemplate ? <Badge variant="secondary">系统</Badge> : null}
              </div>
            </button>
          ))}
        </div>
        {selected ? (
          <div className="space-y-4 rounded-xl border border-border bg-card p-6">
            <div>
              <h2 className="text-lg font-semibold">{selected.templateName}</h2>
              <p className="text-sm text-muted-foreground">{selected.configNote}</p>
            </div>
            <div className="grid gap-2 text-sm sm:grid-cols-2">
              <p>toolType: {selected.toolType}</p>
              <p>executionHandler: {selected.executionHandler}</p>
              <p>input: {selected.inputModality}</p>
              <p>output: {selected.outputModality}</p>
            </div>
            <div>
              <h3 className="mb-2 text-sm font-medium">字段 ({selected.fields.length})</h3>
              <pre className="max-h-72 overflow-auto rounded-lg bg-secondary/40 p-3 text-xs">
                {JSON.stringify(selected.fields, null, 2)}
              </pre>
            </div>
            {selected.defaultUserPromptTemplate ? (
              <div>
                <h3 className="mb-2 text-sm font-medium">默认 User Prompt</h3>
                <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-lg bg-secondary/40 p-3 text-xs">
                  {selected.defaultUserPromptTemplate}
                </pre>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </AdminLayout>
  )
}
