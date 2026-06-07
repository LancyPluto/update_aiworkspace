"use client"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { AgentRun } from "@/lib/api/types"
import type { AgentRunEvent } from "@/lib/api/types"
import { formatDateTime, objectList, objectPayload, textValue } from "@/lib/agent-run-utils"
import { ExternalLink } from "lucide-react"
import Link from "next/link"

export function MemoryHitPanel({ events, run }: { events: AgentRunEvent[]; run: AgentRun }) {
  const memoryEvents = events.filter((e) => e.eventType.startsWith("memory."))
  if (!memoryEvents.length) {
    return <p className="text-sm text-muted-foreground">本次运行未记录记忆检索</p>
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          以下为本次运行实际读入的长期记忆（不是用户画像的全部内容）
        </p>
        <Button variant="outline" size="sm" asChild>
          <Link href={`/settings?tab=agent-memory&userId=${run.userId}`}>
            查看该用户全部记忆
            <ExternalLink className="ml-1 h-3 w-3" />
          </Link>
        </Button>
      </div>
      {memoryEvents.map((event) => {
        const payload = objectPayload(event.eventJson)
        const items = objectList(payload.items)
        return (
          <div key={event.id} className="rounded-lg border p-3 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Badge variant="outline">{event.eventType.replace("memory.", "")}</Badge>
              <span className="text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
            </div>
            <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted-foreground">
              {payload.view ? <span>视图: {textValue(payload.view)}</span> : null}
              {payload.count != null ? <span>条数: {textValue(payload.count)}</span> : null}
            </div>
            {items.length > 0 ? (
              <div className="mt-3 grid gap-2">
                {items.map((item, index) => (
                  <div key={textValue(item.id) || index} className="rounded border bg-muted/30 p-2">
                    <div className="flex flex-wrap gap-2">
                      <Badge variant="secondary">#{textValue(item.id)}</Badge>
                      <Badge variant="outline">{textValue(item.type || item.memoryType)}</Badge>
                    </div>
                    <p className="mt-1 font-medium">{textValue(item.title) || "未命名"}</p>
                    <p className="mt-1 text-xs text-muted-foreground whitespace-pre-wrap">
                      {textValue(item.preview || item.content)}
                    </p>
                    {textValue(item.reason) ? (
                      <p className="mt-1 text-xs">命中：{textValue(item.reason)}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}
