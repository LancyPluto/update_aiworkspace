/** @type {Record<string, string>} */
export const TASK_STATUS_LABEL = {
  CREATED: '任务已创建',
  QUEUED: '排队中',
  PROCESSING: 'AI 正在生成',
  RETRYING: '生成遇到问题，正在重试',
  SUCCESS: '生成完成',
  FAILED: '生成失败',
  TIMEOUT: '任务超时',
  CANCELLED: '任务已取消',
}

export const TERMINAL_STATUSES = new Set([
  'SUCCESS',
  'FAILED',
  'TIMEOUT',
  'CANCELLED',
])

export const FAST_POLL_STATUSES = new Set(['CREATED', 'QUEUED'])
export const SLOW_POLL_STATUSES = new Set(['PROCESSING', 'RETRYING'])

export function pollIntervalMs(status) {
  if (FAST_POLL_STATUSES.has(status)) return 3000
  if (SLOW_POLL_STATUSES.has(status)) return 5000
  return null
}
