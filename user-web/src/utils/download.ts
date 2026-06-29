/**
 * 共享下载工具：通过 fetch → blob → objectURL → anchor.click()
 * 强制浏览器下载文件，绕过跨域 download 属性被忽略的问题。
 *
 * 原理：即使目标 URL 是跨域 OSS/CDN 地址（或经过后端 302 重定向到 OSS），
 * fetch() 会跟随重定向并获取 blob 数据，然后将 blob 转为同源的 objectURL，
 * 此时 anchor 的 download 属性能正常生效。
 *
 * 如果 fetch 失败（CORS 等），则回退到 window.open 让浏览器处理。
 */

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

export async function forceDownload(url: string, filename: string) {
  if (!url) return
  try {
    const res = await fetch(url, { redirect: "follow" })
    if (!res.ok) throw new Error(`Download failed: ${res.status}`)
    const blob = await res.blob()
    downloadBlob(blob, filename)
  } catch {
    // fetch 失败（跨域 CORS 限制等），回退到新窗口打开
    // 后端 download 代理已设置 Content-Disposition: attachment，部分浏览器仍可触发下载
    window.open(url, "_blank")
  }
}
