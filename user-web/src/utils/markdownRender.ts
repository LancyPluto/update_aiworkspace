import DOMPurify from "dompurify"
import type { Config } from "dompurify"
import hljs from "highlight.js"
import { marked } from "marked"

marked.use({
  gfm: true,
  breaks: true,
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : undefined
      const highlighted = language
        ? hljs.highlight(text, { language }).value
        : hljs.highlightAuto(text).value
      const langClass = language ? `hljs language-${language}` : "hljs"
      return `<pre class="code-block"><code class="${langClass}">${highlighted}</code></pre>`
    },
  },
})

const PURIFY_CONFIG: Config = {
  ALLOWED_TAGS: [
    "p",
    "br",
    "strong",
    "em",
    "del",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "ul",
    "ol",
    "li",
    "blockquote",
    "a",
    "code",
    "pre",
    "span",
    "hr",
    "table",
    "thead",
    "tbody",
    "tr",
    "th",
    "td",
  ],
  ALLOWED_ATTR: ["href", "title", "target", "rel", "class"],
  ALLOW_DATA_ATTR: false,
}

/** 将 Markdown 转为可安全用于 v-html 的 HTML */
export function renderMarkdown(source: string): string {
  const trimmed = source?.trim() ?? ""
  if (!trimmed) return ""
  const html = marked.parse(trimmed, { async: false }) as string
  return String(DOMPurify.sanitize(html, PURIFY_CONFIG))
}
