import { getRequestBaseUrl } from "@/api/client"

type VitalMetric = {
  id: string
  name: string
  value: number
  rating?: string
  navigationType?: string
}

function postMetric(metric: VitalMetric) {
  if (typeof window === "undefined") return
  const payload = JSON.stringify({
    id: metric.id,
    name: metric.name,
    value: metric.value,
    rating: metric.rating ?? "unknown",
    navigationType: metric.navigationType ?? "unknown",
    page: window.location.pathname,
  })
  const url = new URL("/api/v1/observability/web-vitals", getRequestBaseUrl()).toString()
  if (navigator.sendBeacon) {
    const blob = new Blob([payload], { type: "application/json" })
    if (navigator.sendBeacon(url, blob)) return
  }
  fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: payload,
    credentials: "include",
    keepalive: true,
  }).catch(() => {})
}

export async function registerWebVitals() {
  if (typeof window === "undefined") return
  try {
    const { onCLS, onFCP, onINP, onLCP, onTTFB } = await import("web-vitals")
    const handler = (metric: VitalMetric) => postMetric(metric)
    onCLS(handler)
    onFCP(handler)
    onINP(handler)
    onLCP(handler)
    onTTFB(handler)
  } catch {
    // Web Vitals is best-effort telemetry and must never affect app startup.
  }
}
