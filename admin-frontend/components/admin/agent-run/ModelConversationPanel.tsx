"use client"

import { useState } from "react"
import { ChevronDown } from "lucide-react"
import type { AgentRunEvent } from "@/lib/api/types"
import { mergeMessageDeltas, mergeReasoningDeltas } from "@/lib/agent-run-utils"

export function ModelConversationPanel({ events }: { events: AgentRunEvent[] }) {
  const [reasoningOpen, setReasoningOpen] = useState(false)
  const answer = mergeMessageDeltas(events)
  const reasoning = mergeReasoningDeltas(events)
  const deltaCount = events.filter((e) => e.eventType === "message.delta").length

  if (!answer && !reasoning) {
    return <p className="text-sm text-muted-foreground">本次未记录模型回复（可能仍在运行或未走流式）</p>
  }

  return (
    <div className="space-y-4">
      {reasoning ? (
        <div className="rounded-lg border p-4">
          <button
            type="button"
            className="flex w-full items-center justify-between text-sm font-medium"
            onClick={() => setReasoningOpen((v) => !v)}
          >
            思考过程（可展开）
            <ChevronDown className={reasoningOpen ? "h-4 w-4 rotate-180" : "h-4 w-4"} />
          </button>
          {reasoningOpen ? (
            <pre className="mt-3 max-h-64 overflow-auto whitespace-pre-wrap rounded bg-muted p-3 text-xs">
              {reasoning}
            </pre>
          ) : (
            <p className="mt-2 text-xs text-muted-foreground">已记录思考内容，点击展开查看</p>
          )}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">本次未记录思考过程（模型/配置未返回 reasoning）</p>
      )}

      <div className="rounded-lg border p-4">
        <p className="text-sm font-medium">模型最终回复</p>
        {deltaCount > 0 ? (
          <p className="mt-1 text-xs text-muted-foreground">已合并 {deltaCount} 个流式片段</p>
        ) : null}
        <pre className="mt-3 max-h-96 overflow-auto whitespace-pre-wrap rounded bg-muted p-3 text-sm">
          {answer || "—"}
        </pre>
      </div>
    </div>
  )
}
