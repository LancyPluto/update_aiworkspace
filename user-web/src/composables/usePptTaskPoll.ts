import { ApiBusinessError } from "@/api/client"
import { pollPptTask } from "@/api/pptApi"

const TERMINAL_OK = new Set(["COMPLETED", "SUCCESS", "DONE"])
const TERMINAL_FAIL = new Set(["FAILED", "CANCELLED", "TIMEOUT"])

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function waitForPptTask(
  bindingId: string | number,
  taskId: string,
  options?: {
    token?: string | null
    intervalMs?: number
    maxAttempts?: number
    onProgress?: (status: string) => void
    signal?: AbortSignal
  },
) {
  const interval = options?.intervalMs ?? 2000
  const maxAttempts = options?.maxAttempts ?? 300

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (options?.signal?.aborted) {
      throw new DOMException("已取消等待", "AbortError")
    }
    try {
      const res = await pollPptTask(bindingId, taskId, { token: options?.token })
      const status = (res.status ?? "PENDING").toUpperCase()
      options?.onProgress?.(status)
      if (TERMINAL_OK.has(status)) return res
      if (TERMINAL_FAIL.has(status)) {
        throw new ApiBusinessError(
          "PPT_TASK_FAILED",
          res.errorMessage ?? "任务失败",
        )
      }
    } catch (e) {
      if ((e as Error).name === "AbortError") throw e
      if (e instanceof ApiBusinessError) {
        if (e.code === "PPT_TASK_FAILED") throw e
        if (TERMINAL_FAIL.has((e.message ?? "").toUpperCase())) throw e
      }
      throw e
    }
    await sleep(interval)
    if (options?.signal?.aborted) {
      throw new DOMException("已取消等待", "AbortError")
    }
  }
  throw new Error("任务等待超时，请稍后刷新页面查看结果")
}
