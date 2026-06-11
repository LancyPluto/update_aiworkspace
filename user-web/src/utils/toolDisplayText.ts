import { repairMojibakeText } from "./displayEncoding"

/** 去掉 configNote 中的 HTML 注释块（ai-tool-ui、ppt-workflow 等），避免直接展示给用户 */
export function cleanToolDisplayText(value?: string | null): string {
  return repairMojibakeText(value || "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\s+/g, " ")
    .trim()
}

export function toolDisplayDescription(
  tool: { description?: string | null; configNote?: string | null },
  fallback = "",
): string {
  return (
    cleanToolDisplayText(tool.description) ||
    cleanToolDisplayText(tool.configNote) ||
    fallback
  )
}
