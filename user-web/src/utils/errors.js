/**
 * @param {unknown} err
 * @returns {string}
 */
export function requestErrorMessage(err) {
  const ax = /** @type {import('axios').AxiosError} */ (err)
  const data = ax.response?.data
  if (data && typeof data === 'object') {
    const msg =
      data.message ||
      data.msg ||
      data.error ||
      data.detail ||
      (Array.isArray(data.errors) ? data.errors.join('；') : '')
    if (typeof msg === 'string' && msg.trim()) return msg
  }
  if (ax.message) return ax.message
  return '请求失败，请稍后重试'
}

/**
 * @param {unknown} err
 * @returns {boolean}
 */
export function isInsufficientCredits(err) {
  const ax = /** @type {import('axios').AxiosError} */ (err)
  const status = ax.response?.status
  if (status === 402 || status === 403) return true
  const data = ax.response?.data
  if (data && typeof data === 'object') {
    const code = String(data.code || data.errorCode || '').toUpperCase()
    if (
      code.includes('CREDIT') ||
      code.includes('BALANCE') ||
      code === 'INSUFFICIENT_CREDITS'
    )
      return true
    const msg = String(data.message || data.msg || '')
    if (/算力|积分|余额|credit/i.test(msg)) return true
  }
  return false
}
