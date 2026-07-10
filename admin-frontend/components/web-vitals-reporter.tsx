'use client'

import { useReportWebVitals } from 'next/web-vitals'

function report(metric: { id: string; name: string; value: number; rating?: string; navigationType?: string }) {
  if (typeof window === 'undefined') return
  const payload = JSON.stringify({
    id: metric.id,
    name: metric.name,
    value: metric.value,
    rating: metric.rating ?? 'unknown',
    navigationType: metric.navigationType ?? 'unknown',
    page: window.location.pathname,
  })
  const url = '/api/v1/observability/web-vitals'
  if (navigator.sendBeacon) {
    const blob = new Blob([payload], { type: 'application/json' })
    if (navigator.sendBeacon(url, blob)) return
  }
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: payload,
    credentials: 'include',
    keepalive: true,
  }).catch(() => {})
}

export function WebVitalsReporter() {
  useReportWebVitals(report)
  return null
}
