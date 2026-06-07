"use client"

import { Badge } from "@/components/ui/badge"
import type { AdminAgentRunDetail } from "@/lib/api/types"
import { percentValue, runDuration, statusVariant, textValue } from "@/lib/agent-run-utils"

function latestIntentPayload(events: AdminAgentRunDetail["events"]) {
  const event = [...events].reverse().find((item) => item.eventType === "intent.detected")
  if (!event?.eventJson) return {}
  try {
    const parsed = JSON.parse(event.eventJson)
    return typeof parsed === "object" && parsed ? parsed : {}
  } catch {
    return {}
  }
}

export function RunOverview({ detail }: { detail: AdminAgentRunDetail }) {
  const intentPayload = latestIntentPayload(detail.events) as Record<string, unknown>
  const selectedTool = textValue(intentPayload.selectedToolCode)
  const intent = textValue(intentPayload.intent || detail.run.intent)
  const decisionSource = textValue(intentPayload.decisionSource)
  const confidence = percentValue(intentPayload.confidence)

  return (
    <div className="grid gap-3 rounded-lg border p-4 md:grid-cols-4">
      <div>
        <p className="text-xs text-muted-foreground">状态</p>
        <Badge variant={statusVariant(detail.run.status)}>{detail.run.status}</Badge>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">模型</p>
        <p className="font-medium">{detail.run.modelName || detail.run.modelProviderCode || "-"}</p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">耗时</p>
        <p className="font-medium">
          {runDuration(detail.run.startedAt || detail.run.createdAt, detail.run.finishedAt)}
        </p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">算力</p>
        <p className="font-medium">
          {detail.run.consumedCredits ?? 0} / {detail.run.estimatedCredits ?? "-"}
        </p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">最终意图</p>
        <p className="font-medium">{intent || "-"}</p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">选中工具</p>
        <p className="font-medium">{selectedTool || "-"}</p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">决策来源</p>
        <p className="font-medium">{decisionSource || "-"}</p>
      </div>
      <div>
        <p className="text-xs text-muted-foreground">置信度</p>
        <p className="font-medium">{confidence || "-"}</p>
      </div>
    </div>
  )
}
