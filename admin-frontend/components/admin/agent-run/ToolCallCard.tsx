"use client"

import { useState } from "react"
import { Badge } from "@/components/ui/badge"
import type { AgentToolCall } from "@/lib/api/types"
import {
  parseAgentFileRefs,
  prettyJson,
  runDuration,
  statusVariant,
  taskIdFromCall,
} from "@/lib/agent-run-utils"
import { ChevronDown, ExternalLink } from "lucide-react"

export function ToolCallCard({ call }: { call: AgentToolCall }) {
  const [open, setOpen] = useState(false)
  const taskId = taskIdFromCall(call)
  let badRefs: ReturnType<typeof parseAgentFileRefs> = []
  try {
    badRefs = parseAgentFileRefs(JSON.parse(call.argumentsJson || "{}"))
  } catch {
    badRefs = []
  }
  const attachmentError = (call.errorMessage || "").toLowerCase().includes("attachment")

  return (
    <div className="rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{call.toolName || call.toolCode}</p>
          <p className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <span>call #{call.id}</span>
            {taskId ? (
              <>
                <span>/</span>
                <a
                  className="inline-flex items-center gap-1 text-primary hover:underline"
                  href={`/tasks?taskId=${encodeURIComponent(taskId)}`}
                >
                  task #{taskId}
                  <ExternalLink className="h-3 w-3" />
                </a>
              </>
            ) : null}
            <span>/ {runDuration(call.startedAt || call.createdAt, call.finishedAt)}</span>
          </p>
        </div>
        <Badge variant={statusVariant(call.status)}>{call.status}</Badge>
      </div>
      {call.errorMessage ? (
        <div className="mt-3 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
          {call.errorCode ? `${call.errorCode}: ` : ""}
          {call.errorMessage}
        </div>
      ) : null}
      {attachmentError && badRefs.length > 0 ? (
        <div className="mt-2 text-xs text-destructive">
          失效附件：{badRefs.map((r) => `session#${r.sessionId} file#${r.fileId}`).join("、")}
        </div>
      ) : null}
      <button
        type="button"
        className="mt-3 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        onClick={() => setOpen((v) => !v)}
      >
        <ChevronDown className={open ? "h-3 w-3 rotate-180" : "h-3 w-3"} />
        参数与结果
      </button>
      {open ? (
        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">参数</p>
            <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-xs">
              {prettyJson(call.argumentsJson) || "-"}
            </pre>
          </div>
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">结果</p>
            <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-xs">
              {prettyJson(call.resultJson) || "-"}
            </pre>
          </div>
        </div>
      ) : null}
    </div>
  )
}
