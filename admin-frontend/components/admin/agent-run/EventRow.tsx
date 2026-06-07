"use client"

import { useState } from "react"
import { Badge } from "@/components/ui/badge"
import type { AgentRunEvent } from "@/lib/api/types"
import {
  eventLabel,
  eventTone,
  formatDateTime,
  objectPayload,
  prettyJson,
  textValue,
} from "@/lib/agent-run-utils"
import { ChevronDown } from "lucide-react"

export function EventRow({ event }: { event: AgentRunEvent }) {
  const [open, setOpen] = useState(false)
  const payload = objectPayload(event.eventJson)
  const detail =
    textValue(payload.errorMessage || payload.progressMessage || payload.reason || payload.taskDescription) ||
    event.eventText ||
    ""
  const json = prettyJson(event.eventJson)

  return (
    <div className="rounded-lg border p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <Badge variant={eventTone(event)}>{event.eventType}</Badge>
          <span className="truncate font-medium">{eventLabel(event.eventType)}</span>
        </div>
        <span className="text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
      </div>
      {detail ? <p className="mt-2 whitespace-pre-wrap text-muted-foreground">{detail}</p> : null}
      {json ? (
        <>
          <button
            type="button"
            className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => setOpen((v) => !v)}
          >
            <ChevronDown className={open ? "h-3 w-3 rotate-180" : "h-3 w-3"} />
            JSON 详情
          </button>
          {open ? <pre className="mt-2 max-h-72 overflow-auto rounded bg-muted p-3 text-xs">{json}</pre> : null}
        </>
      ) : null}
    </div>
  )
}
