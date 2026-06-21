"use client"

import { Badge } from "@/components/ui/badge"
import { AlertTriangle, CheckCircle2, Lightbulb } from "lucide-react"
import type { RunDiagnosis } from "@/lib/agent-run-diagnostics"

export function RunDiagnosisHero({ diagnosis }: { diagnosis: RunDiagnosis }) {
  const Icon =
    diagnosis.severity === "error"
      ? AlertTriangle
      : diagnosis.severity === "warning"
        ? AlertTriangle
        : CheckCircle2
  const tone =
    diagnosis.severity === "error"
      ? "border-destructive/30 bg-destructive/5 text-destructive"
      : diagnosis.severity === "warning"
        ? "border-amber-500/30 bg-amber-500/5 text-amber-800 dark:text-amber-200"
        : "border-emerald-500/30 bg-emerald-500/5 text-emerald-700 dark:text-emerald-300"

  return (
    <div className="space-y-4">
      <div className={`rounded-lg border p-4 ${tone}`}>
        <div className="flex items-start gap-3">
          <Icon className="mt-0.5 h-5 w-5 shrink-0" />
          <div className="min-w-0 space-y-2">
            <p className="text-sm font-medium">一句话结论</p>
            <p className="text-sm leading-relaxed">{diagnosis.summary}</p>
          </div>
        </div>
      </div>

      {diagnosis.steps.length > 0 ? (
        <div className="rounded-lg border p-4">
          <p className="mb-3 text-sm font-medium">问题链（按发生顺序）</p>
          <ol className="space-y-2">
            {diagnosis.steps.map((step, index) => (
              <li key={step.id} className="flex gap-3 text-sm">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium">
                  {index + 1}
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{step.title}</span>
                    <Badge
                      variant={
                        step.severity === "error"
                          ? "destructive"
                          : step.severity === "warning"
                            ? "outline"
                            : "secondary"
                      }
                    >
                      {step.severity === "error" ? "异常" : step.severity === "warning" ? "注意" : "正常"}
                    </Badge>
                  </div>
                  <p className="mt-1 text-muted-foreground">{step.plainText}</p>
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}

      {diagnosis.metrics ? (
        <div className="rounded-lg border p-4">
          <p className="mb-3 text-sm font-medium">Harness 指标</p>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {typeof diagnosis.metrics.compactionSavedPercent === "number" ? (
              <MetricPill label="上下文压缩" value={`${diagnosis.metrics.compactionSavedPercent}%`} />
            ) : null}
            {typeof diagnosis.metrics.disclosureSavedPercent === "number" ? (
              <MetricPill label="工具披露节省" value={`${diagnosis.metrics.disclosureSavedPercent}%`} />
            ) : null}
            {diagnosis.metrics.routerFailureClass ? (
              <MetricPill label="路由失败分类" value={diagnosis.metrics.routerFailureClass} />
            ) : null}
            {typeof diagnosis.metrics.toolRejectCount === "number" && diagnosis.metrics.toolRejectCount > 0 ? (
              <MetricPill
                label="工具拒绝恢复"
                value={`${diagnosis.metrics.toolRejectRecoveredCount ?? 0}/${diagnosis.metrics.toolRejectCount}`}
              />
            ) : null}
            {diagnosis.metrics.pruningApplied ? <MetricPill label="Session pruning" value="已应用" /> : null}
            {diagnosis.metrics.memoryFlushTriggered ? <MetricPill label="Memory flush" value="已触发" /> : null}
          </div>
        </div>
      ) : null}

      {diagnosis.suggestions.length > 0 ? (
        <div className="rounded-lg border border-primary/20 bg-primary/5 p-4">
          <div className="mb-2 flex items-center gap-2 text-sm font-medium">
            <Lightbulb className="h-4 w-4" />
            怎么办
          </div>
          <ul className="list-inside list-disc space-y-1 text-sm text-muted-foreground">
            {diagnosis.suggestions.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}

function MetricPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-background px-3 py-2">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-sm font-medium">{value}</p>
    </div>
  )
}
