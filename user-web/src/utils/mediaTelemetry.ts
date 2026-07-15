import { getRequestBaseUrl } from "@/api/client"

export type MediaTelemetryKind = "image" | "video" | "audio"
export type MediaTelemetryStage = "derivative" | "original" | "poster" | "preview"
export type MediaTelemetryOutcome = "loaded" | "fallback" | "failed"

const counters = new Map<string, number>()
let flushTimer: number | null = null

function normalizedPage() {
  const path = window.location.pathname.toLowerCase()
  for (const page of ["/home", "/dashboard", "/community", "/library", "/tools"] as const) {
    if (path === page || path.startsWith(`${page}/`)) return page
  }
  return "/other"
}

export function recordMediaDeliveryEvent(
  kind: MediaTelemetryKind,
  stage: MediaTelemetryStage,
  outcome: MediaTelemetryOutcome,
) {
  if (typeof window === "undefined") return
  const key = `${normalizedPage()}|${kind}|${stage}|${outcome}`
  counters.set(key, (counters.get(key) || 0) + 1)
  if (flushTimer == null) flushTimer = window.setTimeout(flushMediaDeliveryEvents, 10_000)
}

export function flushMediaDeliveryEvents() {
  if (typeof window === "undefined" || counters.size === 0) return
  if (flushTimer != null) window.clearTimeout(flushTimer)
  flushTimer = null
  const events = [...counters].map(([key, count]) => {
    const [page, mediaKind, stage, outcome] = key.split("|")
    return { page, mediaKind, stage, outcome, count }
  })
  counters.clear()
  const body = JSON.stringify({ events })
  const url = new URL("/api/v1/observability/media-events", getRequestBaseUrl()).toString()
  if (navigator.sendBeacon?.(url, new Blob([body], { type: "application/json" }))) return
  fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    keepalive: true,
    body,
  }).catch(() => undefined)
}

export function registerMediaDeliveryTelemetry() {
  if (typeof window === "undefined") return
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flushMediaDeliveryEvents()
  })
  window.addEventListener("pagehide", flushMediaDeliveryEvents)
}
