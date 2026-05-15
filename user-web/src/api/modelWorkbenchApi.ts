import { ApiBusinessError, apiRequest, getApiOrigin } from "./client"
import { getSessionBearerJwt } from "./sessionBearer"
import type {
  ApiResponse,
  ModelChatMessage,
  ModelChatSession,
  ModelNode,
  ModelPool,
  PaymentOrder,
  SubscriptionPlan,
  UserSubscription,
} from "./types"

export function fetchModelPlans(options?: { token?: string | null }) {
  return apiRequest<SubscriptionPlan[]>("GET", "/api/v1/model-workbench/plans", {
    ...options,
    query: { planType: "MODEL_CHANNEL" },
  })
}

export function fetchModelSubscriptions(options?: { token?: string | null }) {
  return apiRequest<UserSubscription[]>("GET", "/api/v1/model-workbench/subscriptions", options)
}

export function fetchModelPools(options?: { token?: string | null }) {
  return apiRequest<ModelPool[]>("GET", "/api/v1/model-workbench/pools", options)
}

export function fetchModelNodes(poolId: number, options?: { token?: string | null }) {
  return apiRequest<ModelNode[]>("GET", `/api/v1/model-workbench/pools/${poolId}/nodes`, options)
}

export function createModelChatSession(body: { poolId?: number; nodeId?: number; title?: string }, options?: { token?: string | null }) {
  return apiRequest<ModelChatSession>("POST", "/api/v1/model-workbench/sessions", { ...options, body })
}

export function fetchModelChatSessions(options?: { token?: string | null }) {
  return apiRequest<ModelChatSession[]>("GET", "/api/v1/model-workbench/sessions", options)
}

export function fetchModelChatMessages(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<ModelChatMessage[]>("GET", `/api/v1/model-workbench/sessions/${sessionId}/messages`, options)
}

export function sendModelChatMessage(sessionId: number, body: { content: string; nodeId?: number }, options?: { token?: string | null }) {
  return apiRequest<ModelChatMessage>("POST", `/api/v1/model-workbench/sessions/${sessionId}/messages`, { ...options, body })
}

export async function streamModelChatMessage(
  sessionId: number,
  body: { content: string; nodeId?: number },
  handlers: {
    token?: string | null
    signal?: AbortSignal
    onDelta?: (text: string) => void
    onCompleted?: (message: ModelChatMessage) => void
    onError?: (message: string) => void
  } = {},
) {
  const origin = getApiOrigin()
  const path = `/api/v1/model-workbench/sessions/${sessionId}/messages/stream`
  const url = origin ? `${origin}${path}` : path
  const headers: Record<string, string> = {
    Accept: "text/event-stream",
    "Content-Type": "application/json",
  }
  const token = handlers.token ?? getSessionBearerJwt()
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    credentials: "include",
    signal: handlers.signal,
  })
  if (!response.ok || !response.body) {
    const rawText = await response.text().catch(() => "")
    try {
      const json = JSON.parse(rawText) as ApiResponse<unknown>
      if (json.code && json.code !== "SUCCESS") {
        throw new ApiBusinessError(json.code, json.message ?? json.code, json.requestId ?? json.traceId)
      }
    } catch (error) {
      if (error instanceof ApiBusinessError) throw error
    }
    throw new ApiBusinessError("SYSTEM_ERROR", `模型流式请求失败 (${response.status})`, undefined)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split("\n\n")
    buffer = frames.pop() || ""
    for (const frame of frames) {
      handleSseFrame(frame, handlers)
    }
  }
  if (buffer.trim()) {
    handleSseFrame(buffer, handlers)
  }
}

function handleSseFrame(
  frame: string,
  handlers: {
    onDelta?: (text: string) => void
    onCompleted?: (message: ModelChatMessage) => void
    onError?: (message: string) => void
  },
) {
  let event = "message"
  const dataLines: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith("event:")) event = line.slice(6).trim()
    if (line.startsWith("data:")) dataLines.push(line.slice(5).trim())
  }
  if (dataLines.length === 0) return
  const data = JSON.parse(dataLines.join("\n")) as Record<string, unknown>
  if (event === "message.delta") {
    handlers.onDelta?.(String(data.text || ""))
  } else if (event === "message.completed") {
    handlers.onCompleted?.(data as unknown as ModelChatMessage)
  } else if (event === "error") {
    handlers.onError?.(String(data.message || "Stream failed"))
  }
}

export function createPaymentOrder(body: { productType: string; productId: number; channel: string }, options?: { token?: string | null }) {
  return apiRequest<PaymentOrder>("POST", "/api/v1/payments/orders", { ...options, body })
}

export function fetchPaymentOrders(options?: { token?: string | null }) {
  return apiRequest<PaymentOrder[]>("GET", "/api/v1/payments/orders", options)
}

export function mockPayOrder(orderId: number, options?: { token?: string | null }) {
  return apiRequest<PaymentOrder>("POST", `/api/v1/payments/orders/${orderId}/mock-pay`, options)
}

export function reportModelIssue(
  sessionId: number,
  body: { poolId?: number; nodeId?: number; message: string },
  options?: { token?: string | null },
) {
  return apiRequest<void>("POST", `/api/v1/model-workbench/sessions/${sessionId}/feedback`, { ...options, body })
}
