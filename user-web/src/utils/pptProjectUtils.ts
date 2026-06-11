import type { PptPage, PptPageOutline, PptProjectDetail } from "@/api/pptApi"
import { getRequestBaseUrl } from "@/api/client"
import { getSessionBearerJwt } from "@/api/sessionBearer"

export interface ParsedMarkdownPage {
  title: string
  points: string[]
  descriptionText?: string
  part?: string
}

export function resolvePptMediaUrl(url?: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith("http://") || url.startsWith("https://")) return url
  const base = getRequestBaseUrl().replace(/\/$/, "")
  return url.startsWith("/") ? `${base}${url}` : `${base}/${url}`
}

/** 引擎返回的 /files/{projectId}/... 转为 BFF 文件代理地址 */
export function resolvePptPageImageUrl(
  url: string | undefined | null,
  bindingId: string | number,
): string | undefined {
  if (!url) return undefined
  if (url.startsWith("http://") || url.startsWith("https://")) {
    const path = new URL(url).pathname
    if (path.startsWith("/files/")) {
      const rest = path.replace(/^\/files\//, "")
      return resolvePptMediaUrl(`/api/v1/ppt/files/${bindingId}/${rest}`)
    }
    return url
  }
  if (url.startsWith("/api/v1/ppt/files/")) return resolvePptMediaUrl(url)
  if (url.startsWith("/files/")) {
    const rest = url.replace(/^\/files\//, "")
    return resolvePptMediaUrl(`/api/v1/ppt/files/${bindingId}/${rest}`)
  }
  return resolvePptMediaUrl(url)
}

export function getPageDescriptionText(page: PptPage): string {
  const raw = page.descriptionContent as Record<string, unknown> | undefined
  if (!raw) return ""
  if (typeof raw.text === "string") return raw.text
  if (Array.isArray(raw.text_content)) {
    return raw.text_content
      .filter((line): line is string => typeof line === "string")
      .join("\n")
  }
  return ""
}

export function pageHasDescription(page: PptPage): boolean {
  return getPageDescriptionText(page).trim().length > 0
}

export function pageHasImage(page: PptPage): boolean {
  return Boolean(page.generatedImageUrl)
}

export function getTemplateStyle(project: PptProjectDetail | null | undefined): string {
  if (!project) return ""
  const raw = project as Record<string, unknown>
  const v = raw.template_style ?? raw.templateStyle
  return typeof v === "string" ? v.trim() : ""
}

export function getTemplateImageUrl(
  project: PptProjectDetail | null | undefined,
  bindingId?: string | number,
): string | undefined {
  if (!project) return undefined
  const raw = project as Record<string, unknown>
  const url =
    (typeof raw.template_image_url === "string" ? raw.template_image_url : undefined) ??
    (typeof raw.templateImageUrl === "string" ? raw.templateImageUrl : undefined)
  return bindingId != null ? resolvePptPageImageUrl(url, bindingId) : resolvePptMediaUrl(url)
}

/** 引擎出图前必须：已上传模板图，或已填写 template_style */
export function canGeneratePptImages(
  project: PptProjectDetail | null | undefined,
  bindingId?: string | number,
): boolean {
  return Boolean(getTemplateStyle(project) || getTemplateImageUrl(project, bindingId))
}

export const PPT_IMAGE_PREREQ_HINT =
  "出图前请先上传模板图，或填写页面风格描述（二选一即可）。"

function normalizeDescription(raw: unknown): { text?: string } | undefined {
  if (!raw || typeof raw !== "object") return undefined
  const d = raw as Record<string, unknown>
  if (typeof d.text === "string") return { text: d.text }
  if (Array.isArray(d.text_content)) {
    const text = d.text_content
      .filter((line): line is string => typeof line === "string")
      .join("\n")
    return text ? { text } : undefined
  }
  return undefined
}

export function normalizeOutline(raw: unknown): PptPageOutline | undefined {
  if (!raw || typeof raw !== "object") return undefined
  const o = raw as Record<string, unknown>
  const title = typeof o.title === "string" ? o.title : ""
  const points = Array.isArray(o.points)
    ? o.points.filter((p): p is string => typeof p === "string")
    : []
  return { title, points }
}

export function normalizePage(
  raw: unknown,
  fallbackIndex: number,
  bindingId?: string | number,
): PptPage | null {
  if (!raw || typeof raw !== "object") return null
  const r = raw as Record<string, unknown>
  const id = String(r.page_id ?? r.id ?? `page-${fallbackIndex}`)
  const outlineContent = normalizeOutline(r.outline_content ?? r.outlineContent)
  const descriptionContent = normalizeDescription(r.description_content ?? r.descriptionContent)
  const rawImage =
    typeof r.generated_image_url === "string"
      ? r.generated_image_url
      : typeof r.generatedImageUrl === "string"
        ? r.generatedImageUrl
        : undefined
  const imageUrl =
    bindingId != null ? resolvePptPageImageUrl(rawImage, bindingId) : resolvePptMediaUrl(rawImage)
  return {
    id,
    orderIndex: typeof r.order_index === "number" ? r.order_index : fallbackIndex,
    status: typeof r.status === "string" ? r.status : undefined,
    part: typeof r.part === "string" ? r.part : undefined,
    outlineContent,
    descriptionContent,
    generatedImageUrl: imageUrl,
  }
}

export function normalizeProjectDetail(raw: PptProjectDetail): PptProjectDetail {
  const bindingId = raw.bindingId ?? (raw as Record<string, unknown>).binding_id
  const pagesRaw = (raw.pages as unknown[]) ?? []
  const pages = pagesRaw
    .map((p, i) => normalizePage(p, i, bindingId as string | number | undefined))
    .filter((p): p is PptPage => p != null)
    .sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
  return {
    ...raw,
    creationType:
      (raw.creation_type as string | undefined) ??
      (raw.creationType as string | undefined),
    ideaPrompt:
      (raw.idea_prompt as string | undefined) ??
      (raw.ideaPrompt as string | undefined),
    outlineText:
      (raw.outline_text as string | undefined) ??
      (raw.outlineText as string | undefined),
    descriptionText:
      (raw.description_text as string | undefined) ??
      (raw.descriptionText as string | undefined),
    outlineRequirements:
      (raw.outline_requirements as string | undefined) ??
      (raw.outlineRequirements as string | undefined),
    templateStyle:
      (raw.template_style as string | undefined) ??
      (raw.templateStyle as string | undefined),
    templateImageUrl: getTemplateImageUrl(
      raw as PptProjectDetail,
      bindingId as string | number | undefined,
    ),
    pages,
  }
}

/** 简易 Markdown 分页解析（导入大纲用） */
export function parseMarkdownPages(markdown: string): ParsedMarkdownPage[] {
  const text = markdown.trim()
  if (!text) return []

  const sections = text.split(/\n(?=#{1,2}\s+|第\s*\d+\s*页)/).filter((s) => s.trim())
  return sections.map((section) => {
    const lines = section.split("\n").map((l) => l.trim()).filter(Boolean)
    const first = lines[0] ?? ""
    const title = first
      .replace(/^#{1,2}\s+/, "")
      .replace(/^第\s*\d+\s*页[：:]\s*/, "")
      .trim()
    const points = lines
      .slice(1)
      .map((l) => l.replace(/^[-*•]\s+/, "").trim())
      .filter(Boolean)
    return { title: title || "未命名页面", points }
  })
}

export function exportOutlineMarkdown(pages: PptPage[], projectTitle: string): void {
  const lines: string[] = [`# ${projectTitle}`, ""]
  pages.forEach((page, idx) => {
    const title = page.outlineContent?.title || `第 ${idx + 1} 页`
    lines.push(`## 第 ${idx + 1} 页: ${title}`)
    if (page.part) lines.push(`> 章节: ${page.part}`)
    lines.push("", "**大纲要点：**")
    const pts = page.outlineContent?.points ?? []
    if (pts.length) pts.forEach((p) => lines.push(`- ${p}`))
    else lines.push("*暂无要点*")
    lines.push("")
  })
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `${projectTitle.replace(/\s+/g, "_")}_outline.md`
  a.click()
  URL.revokeObjectURL(url)
}

export function exportDescriptionsMarkdown(pages: PptPage[], projectTitle: string) {
  const lines: string[] = [`# ${projectTitle} - 页面描述`, ""]
  pages.forEach((page, idx) => {
    const title = page.outlineContent?.title || `第 ${idx + 1} 页`
    lines.push(`## 第 ${idx + 1} 页: ${title}`, "")
    const text = getPageDescriptionText(page)
    lines.push(text || "*暂无描述*", "")
  })
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `${projectTitle.replace(/\s+/g, "_")}_descriptions.md`
  a.click()
  URL.revokeObjectURL(url)
}

/** 将引擎 /files/... 转为 BFF 文件代理地址（与图片 URL 规则一致） */
export function resolvePptDownloadUrl(
  url: string | undefined | null,
  bindingId: string | number,
): string | undefined {
  return resolvePptPageImageUrl(url, bindingId)
}

export function isPptBffDownloadUrl(url: string | undefined | null): boolean {
  if (!url) return false
  try {
    return new URL(url, getRequestBaseUrl()).pathname.startsWith("/api/v1/ppt/files/")
  } catch {
    return false
  }
}

function guessDownloadFilename(
  resolvedUrl: string,
  preferred?: string,
): string {
  const fromPath = resolvedUrl.split("/").pop()?.split("?")[0] ?? ""
  const extMatch = fromPath.match(/\.(pptx|pdf|zip|png|jpe?g|webp)$/i)
  const ext = extMatch?.[1]?.toLowerCase()
  let name = (preferred || fromPath || "download").replace(/[/\\?%*:|"<>]/g, "_")
  if (ext && !name.toLowerCase().endsWith(`.${ext}`)) {
    name = `${name}.${ext}`
  }
  return name
}

/** 带鉴权拉取文件再触发保存，避免 <a href> 落到前端 SPA 下载 index.html */
export async function downloadPptFile(
  downloadUrl: string,
  bindingId: string | number,
  options?: { filename?: string; token?: string | null },
) {
  const full = resolvePptDownloadUrl(downloadUrl, bindingId)
  if (!full) throw new Error("下载地址无效")

  const headers: Record<string, string> = { Accept: "*/*" }
  const token = options?.token ?? getSessionBearerJwt()
  if (token && isPptBffDownloadUrl(full)) headers.Authorization = `Bearer ${token}`

  const res = await fetch(full, { credentials: "include", headers })
  if (!res.ok) {
    const text = await res.text()
    try {
      const json = JSON.parse(text) as { message?: string; code?: string }
      if (json.message) throw new Error(json.message)
    } catch (e) {
      if (e instanceof Error && e.message && !e.message.includes("JSON")) throw e
    }
    if (text.includes("<!doctype html") || text.includes("<html")) {
      throw new Error("下载失败：拿到了前端页面而非文件，请确认后端与 PPT 引擎已启动")
    }
    throw new Error(`下载失败 (${res.status})`)
  }

  const blob = await res.blob()
  const name = guessDownloadFilename(full, options?.filename)
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = objectUrl
  a.download = name
  a.rel = "noopener"
  a.click()
  URL.revokeObjectURL(objectUrl)
}

/** @deprecated 请使用 downloadPptFile，需 bindingId 走 BFF 代理 */
export function downloadByUrl(downloadUrl: string, filename?: string) {
  const full = resolvePptMediaUrl(downloadUrl) ?? downloadUrl
  const a = document.createElement("a")
  a.href = full
  a.target = "_blank"
  if (filename) a.download = filename
  a.rel = "noopener"
  a.click()
}
