import type { ApiErrorCode } from "./types"

type JsonRecord = Record<string, unknown>

export interface ApiBusinessErrorOptions {
  errorCode: ApiErrorCode
  httpStatus?: number
  userMessage?: string
  traceId?: string
  fallbackMessage?: string
}

export class ApiBusinessError extends Error {
  readonly errorCode: ApiErrorCode
  /** Compatibility alias for existing error-code branches. */
  readonly code: ApiErrorCode
  readonly httpStatus?: number
  /** Compatibility alias used by some request libraries. */
  readonly status?: number
  readonly userMessage?: string
  readonly traceId?: string

  constructor(options: ApiBusinessErrorOptions) {
    const message = options.userMessage ?? options.fallbackMessage ?? "请求失败，请稍后重试"
    super(message)
    this.name = "ApiBusinessError"
    this.errorCode = options.errorCode
    this.code = options.errorCode
    this.httpStatus = options.httpStatus
    this.status = options.httpStatus
    this.userMessage = options.userMessage
    this.traceId = options.traceId
  }

  get requestId(): string | undefined {
    return this.traceId
  }
}

function asRecord(payload: unknown): JsonRecord | undefined {
  return payload !== null && typeof payload === "object" && !Array.isArray(payload)
    ? (payload as JsonRecord)
    : undefined
}

function nonBlankString(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined
  const normalized = value.trim()
  return normalized || undefined
}

const SAFE_LEGACY_USER_MESSAGES: Readonly<Record<string, string>> = {
  PARAM_ERROR: "请求参数有误，请检查后重试",
  NOT_FOUND: "请求的内容不存在",
  UNAUTHORIZED: "请先登录后再试",
  ADMIN_UNAUTHORIZED: "请先登录后再试",
  FORBIDDEN: "无权执行此操作",
  ADMIN_FORBIDDEN: "无权执行此操作",
  TOOL_NOT_FOUND: "工具不存在",
  TOOL_OFFLINE: "工具当前不可用",
  WORKFLOW_RUNTIME_BLOCKED: "工作流当前不可执行",
  CREDIT_NOT_ENOUGH: "可用算力不足，请充值后重试",
  IDEMPOTENCY_CONFLICT: "请求已处理，请勿重复提交",
  MEMBERSHIP_ACTIVE: "会员权益已生效",
  MEMBERSHIP_ORDER_PENDING: "已有待处理的会员订单",
  TASK_NOT_FOUND: "任务不存在",
  TASK_STATUS_INVALID: "当前任务状态不允许操作",
  AGENT_SESSION_NOT_FOUND: "会话不存在",
  AGENT_RUN_NOT_FOUND: "运行记录不存在",
  AGENT_RUN_NOT_CANCELLABLE: "当前运行状态无法取消",
  AGENT_RATE_LIMITED: "请求过于频繁，请稍后重试",
  AGENT_ACTIVE_RUN_LIMIT: "当前运行任务过多，请稍后重试",
  AGENT_TOOL_NOT_AVAILABLE: "所选工具当前不可用",
  AGENT_CREDIT_NOT_ENOUGH: "可用算力不足，请充值后重试",
  AGENT_RUN_BUDGET_EXCEEDED: "本次运行已达到预算上限",
  AGENT_TOOL_CALL_LIMIT: "工具调用次数已达到上限",
  AGENT_MODEL_CALL_LIMIT: "模型调用次数已达到上限",
  AGENT_SECURITY_REJECTED: "请求未通过安全检查",
  MODEL_CALL_FAILED: "模型服务暂不可用，请稍后重试",
  SESSION_NOT_FOUND: "会话不存在",
  FILE_TYPE_NOT_ALLOWED: "文件类型不支持",
  FILE_SIZE_EXCEEDED: "文件过大，请调整后重试",
  SYSTEM_ERROR: "请求失败，请稍后重试",
}

function safeLegacyUserMessage(errorCode?: string): string | undefined {
  if (!errorCode || errorCode === "SUCCESS") return undefined
  const exactMessage = SAFE_LEGACY_USER_MESSAGES[errorCode]
  if (exactMessage) return exactMessage

  const moduleName = errorCode.split("_", 1)[0]
  switch (moduleName) {
    case "AUTH":
      return "登录状态无效，请重新登录"
    case "TOOL":
      return "工具当前不可用"
    case "TASK":
      return "任务处理失败，请稍后重试"
    case "MODEL":
      return "模型服务暂不可用，请稍后重试"
    case "AGENT":
      return "Agent 请求失败，请稍后重试"
    case "WORKFLOW":
      return "工作流处理失败，请稍后重试"
    case "API":
      return "请求处理失败，请稍后重试"
    case "SYSTEM":
      return "请求失败，请稍后重试"
    default:
      return undefined
  }
}

export function parseJsonBody(rawText: string): unknown {
  if (!rawText) return undefined
  try {
    return JSON.parse(rawText) as unknown
  } catch {
    return undefined
  }
}

export function isSuccessResponse(payload: unknown): payload is JsonRecord & { code: "SUCCESS"; data: unknown } {
  const record = asRecord(payload)
  return record?.code === "SUCCESS" && "data" in record
}

export function isErrorResponse(payload: unknown): boolean {
  const record = asRecord(payload)
  if (!record) return false
  if (nonBlankString(record.errorCode)) return true
  const legacyCode = nonBlankString(record.code)
  return Boolean(legacyCode && legacyCode !== "SUCCESS")
}

export function toUserApiError(
  payload: unknown,
  httpStatus?: number,
  fallbackMessage?: string,
): ApiBusinessError {
  const record = asRecord(payload)
  const envelopeCode = nonBlankString(record?.errorCode)
  const legacyCode = nonBlankString(record?.code)
  const errorCode = (envelopeCode || (legacyCode !== "SUCCESS" ? legacyCode : undefined) || "SYSTEM_ERROR") as ApiErrorCode
  const userMessage = nonBlankString(record?.userMessage) ?? safeLegacyUserMessage(legacyCode ?? envelopeCode)
  const traceId = nonBlankString(record?.traceId) ?? nonBlankString(record?.requestId)

  return new ApiBusinessError({
    errorCode,
    httpStatus,
    userMessage,
    traceId,
    fallbackMessage,
  })
}
