"use client"

import { Badge } from "@/components/ui/badge"
import type { AgentRunEvent } from "@/lib/api/types"
import {
  VALID_INTENTS,
  explainValidationFailure,
  formatDateTime,
  objectPayload,
  percentValue,
  textValue,
} from "@/lib/agent-run-utils"

export function RouterRulesPanel({ events }: { events: AgentRunEvent[] }) {
  const started = events.find((e) => e.eventType === "router.started")
  const fallback = events.find((e) => e.eventType === "router.fallback")
  const selected = events.find((e) => e.eventType === "router.selected")
  const startedPayload = started ? objectPayload(started.eventJson) : {}
  const fallbackPayload = fallback ? objectPayload(fallback.eventJson) : {}
  const parsed =
    fallbackPayload.parsed && typeof fallbackPayload.parsed === "object"
      ? (fallbackPayload.parsed as Record<string, unknown>)
      : null
  const vf = textValue(fallbackPayload.validationFailure)
  const minConfidence = startedPayload.minConfidence

  return (
    <div className="space-y-4">
      <section className="rounded-lg border p-4">
        <h4 className="mb-3 font-medium">路由规则（系统固定）</h4>
        <ul className="space-y-2 text-sm text-muted-foreground">
          <li>
            <span className="font-medium text-foreground">合法意图：</span>
            {VALID_INTENTS.map((intent) => (
              <Badge key={intent} className="ml-1" variant="outline">
                {intent}
              </Badge>
            ))}
          </li>
          <li>
            <span className="font-medium text-foreground">最低置信度：</span>
            {minConfidence != null ? percentValue(minConfidence) : "默认 70%（未记录则以配置为准）"}
          </li>
          <li>若 AI 返回的 intent 不在上表、或分数不够、或工具不可用 → 写入 router.fallback，改走备用方案</li>
          <li>general_chat 模式下，工具循环只允许 memory_add / memory_replace / memory_remove</li>
        </ul>
      </section>

      {selected ? (
        <section className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 p-4 text-sm">
          <p className="font-medium">本次：路由成功</p>
          <p className="mt-1 text-muted-foreground">
            工具 {textValue(objectPayload(selected.eventJson).selectedToolCode)} ·{" "}
            {formatDateTime(selected.createdAt)}
          </p>
        </section>
      ) : null}

      {fallback ? (
        <section className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4 text-sm">
          <p className="font-medium">本次：路由回退</p>
          <p className="mt-1 text-muted-foreground">{explainValidationFailure(vf) || textValue(fallbackPayload.reason)}</p>
          {parsed ? (
            <div className="mt-3 rounded border bg-background/60 p-3">
              <p className="text-xs font-medium">AI 原始输出（被丢弃部分）</p>
              <p className="mt-1">意图：{textValue(parsed.intent)}</p>
              <p>工具：{textValue(parsed.selectedToolCode)}</p>
              <p>置信度：{textValue(parsed.confidence)}</p>
              {textValue(parsed.reason) ? <p className="mt-1 text-muted-foreground">{textValue(parsed.reason)}</p> : null}
            </div>
          ) : null}
        </section>
      ) : null}

      {!started && !fallback && !selected ? (
        <p className="text-sm text-muted-foreground">本次运行未记录路由事件（可能是旧数据或未走路由）</p>
      ) : null}
    </div>
  )
}
