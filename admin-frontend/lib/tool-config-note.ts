/** 与 backend ToolIntegrationConstants / PptConstants 中的 HTML 注释块对齐 */

export const FRONTEND_STYLE_MARKER = "<!-- ai-tool-ui:"
export const FRONTEND_STYLE_PATTERN = /<!-- ai-tool-ui:(.*?) -->/s

const PPT_WORKFLOW_PATTERN = /<!--\s*ppt-workflow:\{.*?\}\s*-->/gs
const TOOL_INTEGRATION_PATTERN = /<!--\s*tool-integration:\{.*?\}\s*-->/gs

export interface FrontendStyleConfig {
  primaryColor: string
  welcomeMessage: string
  mediaDisplayMode: "icon" | "effect" | "comparison"
  modelIconUrl: string
  comparisonOriginalUrl: string
  comparisonEffectUrl: string
}

const defaultFrontendStyle: FrontendStyleConfig = {
  primaryColor: "#3b82f6",
  welcomeMessage: "",
  mediaDisplayMode: "icon",
  modelIconUrl: "",
  comparisonOriginalUrl: "",
  comparisonEffectUrl: "",
}

/** 从 config_note 抽出需原样保留的工作台集成块（ppt-workflow / tool-integration） */
export function extractIntegrationMarkers(configNote?: string | null): string[] {
  const raw = configNote || ""
  const markers: string[] = []
  for (const pattern of [PPT_WORKFLOW_PATTERN, TOOL_INTEGRATION_PATTERN]) {
    const matches = raw.match(pattern)
    if (matches) {
      for (const block of matches) {
        if (block.trim()) markers.push(block.trim())
      }
    }
  }
  return markers
}

export function hasIntegrationMarkers(configNote?: string | null): boolean {
  return extractIntegrationMarkers(configNote).length > 0
}

export function stripIntegrationMarkers(note: string): string {
  return note.replace(PPT_WORKFLOW_PATTERN, "").replace(TOOL_INTEGRATION_PATTERN, "").trim()
}

export function extractFrontendStyle(configNote?: string | null): { note: string; style: FrontendStyleConfig } {
  const raw = configNote || ""
  const withoutIntegration = stripIntegrationMarkers(raw)
  const match = withoutIntegration.match(FRONTEND_STYLE_PATTERN)
  if (!match) {
    return { note: withoutIntegration.trim(), style: { ...defaultFrontendStyle } }
  }

  try {
    const parsed = JSON.parse(match[1]) as Partial<FrontendStyleConfig>
    const mediaDisplayMode =
      parsed.mediaDisplayMode === "comparison" ? "comparison" : parsed.mediaDisplayMode === "effect" ? "effect" : "icon"
    return {
      note: withoutIntegration.replace(FRONTEND_STYLE_PATTERN, "").trim(),
      style: {
        primaryColor:
          typeof parsed.primaryColor === "string" && parsed.primaryColor
            ? parsed.primaryColor
            : defaultFrontendStyle.primaryColor,
        welcomeMessage: typeof parsed.welcomeMessage === "string" ? parsed.welcomeMessage : "",
        mediaDisplayMode,
        modelIconUrl: typeof parsed.modelIconUrl === "string" ? parsed.modelIconUrl : "",
        comparisonOriginalUrl: typeof parsed.comparisonOriginalUrl === "string" ? parsed.comparisonOriginalUrl : "",
        comparisonEffectUrl: typeof parsed.comparisonEffectUrl === "string" ? parsed.comparisonEffectUrl : "",
      },
    }
  } catch {
    return {
      note: withoutIntegration.replace(FRONTEND_STYLE_PATTERN, "").trim(),
      style: { ...defaultFrontendStyle },
    }
  }
}

export function serializeConfigNote(note: string, style: FrontendStyleConfig, preservedMarkers?: string[]): string {
  const cleanNote = note.trim()
  const styleJson = JSON.stringify({
    primaryColor: style.primaryColor || "#3b82f6",
    welcomeMessage: style.welcomeMessage || "",
    mediaDisplayMode:
      style.mediaDisplayMode === "comparison" ? "comparison" : style.mediaDisplayMode === "effect" ? "effect" : "icon",
    modelIconUrl: style.modelIconUrl || "",
    comparisonOriginalUrl: style.comparisonOriginalUrl || "",
    comparisonEffectUrl: style.comparisonEffectUrl || "",
  })
  const base = [cleanNote, `${FRONTEND_STYLE_MARKER}${styleJson} -->`].filter(Boolean).join("\n\n")
  const markers = preservedMarkers?.filter(Boolean) ?? []
  if (markers.length === 0) return base
  return [base, ...markers].join("\n\n")
}
