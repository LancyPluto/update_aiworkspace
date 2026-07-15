export type CreditRefreshReason = "event" | "open" | "online" | "visible" | "poll"

interface EventSourceLike extends EventTarget {
  close(): void
}

interface VisibilityTarget extends EventTarget {
  readonly visibilityState: string
}

export interface CreditRealtimeOptions {
  url: string
  refresh: (reason: CreditRefreshReason) => void | Promise<void>
  createEventSource?: (url: string, options: EventSourceInit) => EventSourceLike
  windowTarget?: EventTarget
  documentTarget?: VisibilityTarget
  setTimeoutFn?: (callback: () => void, delay: number) => unknown
  clearTimeoutFn?: (handle: unknown) => void
  setIntervalFn?: (callback: () => void, delay: number) => unknown
  clearIntervalFn?: (handle: unknown) => void
  debounceMs?: number
  pollingMs?: number
}

export interface CreditRealtimeController {
  start(): void
  stop(): void
}

export function createCreditRealtime(options: CreditRealtimeOptions): CreditRealtimeController {
  const createEventSource = options.createEventSource
    ?? ((url: string, init: EventSourceInit) => new EventSource(url, init))
  const windowTarget = options.windowTarget ?? window
  const documentTarget = options.documentTarget ?? document
  const setTimeoutFn = options.setTimeoutFn
    ?? ((callback: () => void, delay: number) => window.setTimeout(callback, delay))
  const clearTimeoutFn = options.clearTimeoutFn
    ?? ((handle: unknown) => window.clearTimeout(handle as number))
  const setIntervalFn = options.setIntervalFn
    ?? ((callback: () => void, delay: number) => window.setInterval(callback, delay))
  const clearIntervalFn = options.clearIntervalFn
    ?? ((handle: unknown) => window.clearInterval(handle as number))
  const debounceMs = options.debounceMs ?? 200
  const pollingMs = options.pollingMs ?? 60_000

  let started = false
  let source: EventSourceLike | null = null
  let debounceHandle: unknown = null
  let pollingHandle: unknown = null

  const refresh = (reason: CreditRefreshReason) => {
    void Promise.resolve(options.refresh(reason)).catch(() => undefined)
  }

  const queueEventRefresh = () => {
    if (debounceHandle != null) clearTimeoutFn(debounceHandle)
    debounceHandle = setTimeoutFn(() => {
      debounceHandle = null
      refresh("event")
    }, debounceMs)
  }

  const stopPolling = () => {
    if (pollingHandle == null) return
    clearIntervalFn(pollingHandle)
    pollingHandle = null
  }

  const startPolling = () => {
    stopPolling()
    if (documentTarget.visibilityState !== "visible") return
    pollingHandle = setIntervalFn(() => refresh("poll"), pollingMs)
  }

  const handleOpen = () => refresh("open")
  const handleOnline = () => refresh("online")
  const handleVisibilityChange = () => {
    if (documentTarget.visibilityState !== "visible") {
      stopPolling()
      return
    }
    refresh("visible")
    startPolling()
  }

  return {
    start() {
      if (started) return
      started = true
      source = createEventSource(options.url, { withCredentials: true })
      source.addEventListener("open", handleOpen)
      source.addEventListener("credit-account-changed", queueEventRefresh)
      windowTarget.addEventListener("online", handleOnline)
      documentTarget.addEventListener("visibilitychange", handleVisibilityChange)
      startPolling()
    },
    stop() {
      if (!started) return
      started = false
      if (debounceHandle != null) {
        clearTimeoutFn(debounceHandle)
        debounceHandle = null
      }
      stopPolling()
      windowTarget.removeEventListener("online", handleOnline)
      documentTarget.removeEventListener("visibilitychange", handleVisibilityChange)
      source?.removeEventListener("open", handleOpen)
      source?.removeEventListener("credit-account-changed", queueEventRefresh)
      source?.close()
      source = null
    },
  }
}
