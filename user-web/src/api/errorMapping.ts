import { ApiBusinessError } from "./client"

function withTraceId(message: string, traceId?: string): string {
  return traceId ? `${message}（追踪号：${traceId}）` : message
}

export function formatAgentRequestError(error: unknown): string {
  if (error instanceof ApiBusinessError && error.code === "MODEL_CALL_FAILED") {
    const detail = error.message ? `后端返回：${error.message}` : "后端没有返回更多细节。"
    return withTraceId(
      `模型连接验证失败。请在管理端检查 provider、baseUrl、API Key、模型名称和 MiniMax Group ID 后重试。${detail}`,
      error.traceId,
    )
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_ACTIVE_RUN_LIMIT") {
    return withTraceId("当前已有 Agent 在运行，请等待上一次执行结束后再试。", error.traceId)
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_RATE_LIMITED") {
    return withTraceId("Agent 请求过于频繁，请稍后再试。", error.traceId)
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_CREDIT_NOT_ENOUGH") {
    return withTraceId("可用算力不足，暂时无法启动 Agent。请先补充或释放算力后再试。", error.traceId)
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_RUN_NOT_CANCELLABLE") {
    return withTraceId("当前 Agent 状态已变化，这次操作没有生效。请刷新后重试。", error.traceId)
  }
  if (error instanceof ApiBusinessError) {
    return withTraceId(error.message || error.code, error.traceId)
  }
  return error instanceof Error ? error.message : "Agent 请求失败，请稍后重试"
}

export function formatAgentRunFailure(errorCode?: string, errorMessage?: string): string {
  if (errorCode === "AGENT_SECURITY_REJECTED") {
    return errorMessage || "这条请求包含敏感指令或内部信息索取要求，Agent 已拒绝执行。"
  }
  if (errorCode === "AGENT_RUN_BUDGET_EXCEEDED") {
    return errorMessage || "这次 Agent 运行超出了当前算力预算。"
  }
  if (errorCode === "AGENT_MODEL_CALL_LIMIT" || errorCode === "AGENT_TOOL_CALL_LIMIT") {
    return errorMessage || "这次 Agent 运行已达到安全限制，系统已停止继续执行。"
  }
  if (errorCode === "AGENT_SERVICE_NOTIFY_FAILED") {
    return errorMessage || "Agent 服务暂时不可用，请稍后重试。"
  }
  if (errorCode === "MODEL_RISK_CONTROL_REJECTED") {
    return "您的提示词包含违禁词"
  }
  if (errorCode === "MODEL_AUTH_FAILED") {
    return "模型认证失败，请联系管理员检查 API Key"
  }
  if (errorCode === "MODEL_CREDIT_INSUFFICIENT") {
    return "模型账户余额不足，请联系管理员充值"
  }
  if (errorCode === "MODEL_RATE_LIMITED") {
    return errorMessage || "请求过于频繁，请稍后重试"
  }
  if (errorCode === "MODEL_TIMEOUT") {
    return errorMessage || "模型响应超时，请稍后重试"
  }
  if (errorCode === "MODEL_CALL_FAILED") {
    return errorMessage || "模型调用失败，请稍后重试。"
  }
  if (errorCode === "TOOL_CALL_FAILED" || errorCode === "TOOL_TASK_FAILED" || errorCode === "TOOL_TASK_TIMEOUT") {
    return errorMessage || "工具执行失败，请稍后再试，或换一种更明确的描述。"
  }
  if (errorCode === "BACKEND_CALL_FAILED") {
    return errorMessage || "系统内部链路暂时异常，请稍后重试。"
  }
  return errorMessage || "Agent 运行失败，请稍后再试。"
}
