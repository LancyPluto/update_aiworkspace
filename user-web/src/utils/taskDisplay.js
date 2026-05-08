/**
 * @param {Record<string, unknown>} task
 */
export function pickTaskResult(task) {
  if (!task || typeof task !== 'object') return null
  const out =
    task.output ??
    task.result ??
    task.resultContent ??
    task.content ??
    task.data
  if (out != null && typeof out === 'object') return out
  if (typeof out === 'string') return out
  return task
}

/**
 * @param {unknown} result
 */
export function formatResultForDisplay(result) {
  if (result == null) return ''
  if (typeof result === 'string') return result
  try {
    return JSON.stringify(result, null, 2)
  } catch {
    return String(result)
  }
}

/**
 * @param {Record<string, unknown>} task
 */
export function pickFailureReason(task) {
  if (!task || typeof task !== 'object') return ''
  const r =
    task.failureReason ??
    task.errorMessage ??
    task.message ??
    task.error ??
    task.failReason
  return typeof r === 'string' ? r : r != null ? formatResultForDisplay(r) : ''
}

/**
 * @param {Record<string, unknown>} task
 */
export function pickTaskMeta(task) {
  const id =
    task.taskId ??
    task.id ??
    task.taskCode ??
    ''
  const toolName = String(task.toolName ?? task.tool?.toolName ?? '')
  const status = String(task.status ?? '')
  const createdAt = String(task.createdAt ?? task.createTime ?? '')
  const completedAt = String(
    task.completedAt ?? task.finishTime ?? task.updatedAt ?? '',
  )
  return { id, toolName, status, createdAt, completedAt }
}
