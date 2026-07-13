import DOMPurify from "dompurify"
import type { Config } from "dompurify"
import hljs from "highlight.js/lib/core"
import bash from "highlight.js/lib/languages/bash"
import css from "highlight.js/lib/languages/css"
import java from "highlight.js/lib/languages/java"
import javascript from "highlight.js/lib/languages/javascript"
import json from "highlight.js/lib/languages/json"
import markdown from "highlight.js/lib/languages/markdown"
import python from "highlight.js/lib/languages/python"
import sql from "highlight.js/lib/languages/sql"
import typescript from "highlight.js/lib/languages/typescript"
import xml from "highlight.js/lib/languages/xml"
import { marked } from "marked"

hljs.registerLanguage("bash", bash)
hljs.registerLanguage("css", css)
hljs.registerLanguage("java", java)
hljs.registerLanguage("javascript", javascript)
hljs.registerLanguage("json", json)
hljs.registerLanguage("markdown", markdown)
hljs.registerLanguage("python", python)
hljs.registerLanguage("sql", sql)
hljs.registerLanguage("typescript", typescript)
hljs.registerLanguage("xml", xml)
hljs.registerAliases(["sh", "shell"], { languageName: "bash" })
hljs.registerAliases(["js", "jsx"], { languageName: "javascript" })
hljs.registerAliases(["md"], { languageName: "markdown" })
hljs.registerAliases(["py"], { languageName: "python" })
hljs.registerAliases(["ts", "tsx"], { languageName: "typescript" })
hljs.registerAliases(["html", "vue"], { languageName: "xml" })

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
