"use client"

import { useState } from "react"
import { Badge } from "@/components/ui/badge"
import { ChevronDown } from "lucide-react"
import type { TimelineStep } from "@/lib/agent-run-diagnostics"
import { compactJson, eventLabel, formatDateTime } from "@/lib/agent-run-utils"

export function RunTimeline({ steps }: { steps: TimelineStep[] }) {
  if (!steps.length) {
    return <p className="text-sm text-muted-foreground">暂无事件记录</p>
  }
  return (
    <div className="space-y-2">
      {steps.map((step) => (
        <TimelineRow key={step.id} step={step} />
      ))}
    </div>
  )
}

function TimelineRow({ step }: { step: TimelineStep }) {
  const [open, setOpen] = useState(false)
  const variant =
    step.severity === "error" ? "destructive" : step.severity === "warning" ? "outline" : "secondary"

  return (
    <div className="rounded-lg border p-3 text-sm" id={`timeline-${step.eventId}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Badge variant={variant}>{eventLabel(step.eventType)}</Badge>
          <span className="font-medium">{step.title}</span>
        </div>
        <span className="text-xs text-muted-foreground">{formatDateTime(step.createdAt)}</span>
      </div>
      {step.plainText ? <p className="mt-2 text-muted-foreground">{step.plainText}</p> : null}
      {step.technical ? (
        <p className="mt-1 text-xs text-muted-foreground">技术码：{step.technical}</p>
      ) : null}
      {step.payload && Object.keys(step.payload).length > 0 ? (
        <>
          <button
            type="button"
            className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => setOpen((v) => !v)}
          >
            <ChevronDown className={open ? "h-3 w-3 rotate-180" : "h-3 w-3"} />
            展开技术详情
          </button>
          {open ? (
            <pre className="mt-2 max-h-48 overflow-auto rounded bg-muted p-2 text-xs whitespace-pre-wrap">
              {compactJson(step.payload, 2000)}
            </pre>
          ) : null}
        </>
      ) : null}
    </div>
  )
}
