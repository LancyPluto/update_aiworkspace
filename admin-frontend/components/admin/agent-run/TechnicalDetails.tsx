"use client"

import { useState } from "react"
import { ChevronDown } from "lucide-react"
import type { AgentRunEvent, AgentToolCall } from "@/lib/api/types"
import { EventRow } from "@/components/admin/agent-run/EventRow"
import { ToolCallCard } from "@/components/admin/agent-run/ToolCallCard"

export function TechnicalDetails({
  events,
  toolCalls,
}: {
  events: AgentRunEvent[]
  toolCalls: AgentToolCall[]
}) {
  const [open, setOpen] = useState(false)

  return (
    <div className="rounded-lg border">
      <button
        type="button"
        className="flex w-full items-center justify-between p-4 text-sm font-medium"
        onClick={() => setOpen((v) => !v)}
      >
        原始数据（JSON 事件 / 工具参数）
        <ChevronDown className={open ? "h-4 w-4 rotate-180" : "h-4 w-4"} />
      </button>
      {open ? (
        <div className="space-y-4 border-t p-4">
          <section className="space-y-2">
            <h4 className="text-sm font-medium">工具调用</h4>
            {toolCalls.length === 0 ? (
              <p className="text-sm text-muted-foreground">暂无</p>
            ) : (
              toolCalls.map((call) => <ToolCallCard key={call.id} call={call} />)
            )}
          </section>
          <section className="space-y-2">
            <h4 className="text-sm font-medium">完整事件流</h4>
            {events.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </section>
        </div>
      ) : null}
    </div>
  )
}
