/** 与 backend ToolIntegrationConstants / PptConstants 中的 HTML 注释块对齐 */

export const FRONTEND_STYLE_MARKER = "<!-- ai-tool-ui:"
export const FRONTEND_STYLE_PATTERN = /<!-- ai-tool-ui:([\s\S]*?) -->/

const PPT_WORKFLOW_PATTERN = /<!--\s*ppt-workflow:\{[\s\S]*?\}\s*-->/g
const TOOL_INTEGRATION_PATTERN = /<!--\s*tool-integration:\{[\s\S]*?\}\s*-->/g

export interface FrontendStyleConfig {
  primaryColor: string
  welcomeMessage: string
  mediaDisplayMode: "icon" | "effect" | "comparison"
  modelIconUrl: string
  comparisonOriginalUrl: string
  comparisonEffectUrl: string
  heroTitle: string
  heroSubtitle: string
  demoThumbnails: string[]
  useCases: string[]
  steps: string[]
  recommendedToolCodes: string[]
  beforeVideoUrl: string
  afterVideoUrl: string
}

const defaultFrontendStyle: FrontendStyleConfig = {
  primaryColor: "#3b82f6",
  welcomeMessage: "",
  mediaDisplayMode: "icon",
  modelIconUrl: "",
  comparisonOriginalUrl: "",
  comparisonEffectUrl: "",
  heroTitle: "",
  heroSubtitle: "",
  demoThumbnails: [],
  useCases: [],
  steps: [],
  recommendedToolCodes: [],
  beforeVideoUrl: "",
  afterVideoUrl: "",
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
        heroTitle: typeof parsed.heroTitle === "string" ? parsed.heroTitle : "",
        heroSubtitle: typeof parsed.heroSubtitle === "string" ? parsed.heroSubtitle : "",
        demoThumbnails: stringList(parsed.demoThumbnails),
        useCases: stringList(parsed.useCases),
        steps: stringList(parsed.steps),
        recommendedToolCodes: stringList(parsed.recommendedToolCodes),
        beforeVideoUrl: typeof parsed.beforeVideoUrl === "string" ? parsed.beforeVideoUrl : "",
        afterVideoUrl: typeof parsed.afterVideoUrl === "string" ? parsed.afterVideoUrl : "",
      },
    }
  } catch {
    return {
      note: withoutIntegration.replace(FRONTEND_STYLE_PATTERN, "").trim(),
      style: { ...defaultFrontendStyle },
    }
  }
}

export function serializeConfigNote(note: string, style: Partial<FrontendStyleConfig>, preservedMarkers?: string[]): string {
  const cleanNote = note.trim()
  const styleJson = JSON.stringify({
    mediaDisplayMode:
      style.mediaDisplayMode === "comparison" ? "comparison" : style.mediaDisplayMode === "effect" ? "effect" : "icon",
    modelIconUrl: style.modelIconUrl || "",
    comparisonOriginalUrl: style.comparisonOriginalUrl || "",
    comparisonEffectUrl: style.comparisonEffectUrl || "",
    heroTitle: style.heroTitle || "",
    heroSubtitle: style.heroSubtitle || "",
    demoThumbnails: cleanStringList(style.demoThumbnails),
    useCases: cleanStringList(style.useCases),
    steps: cleanStringList(style.steps),
    recommendedToolCodes: cleanStringList(style.recommendedToolCodes),
    beforeVideoUrl: style.beforeVideoUrl || "",
    afterVideoUrl: style.afterVideoUrl || "",
  })
  const base = [cleanNote, `${FRONTEND_STYLE_MARKER}${styleJson} -->`].filter(Boolean).join("\n\n")
  const markers = preservedMarkers?.filter(Boolean) ?? []
  if (markers.length === 0) return base
  return [base, ...markers].join("\n\n")
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((item) => (typeof item === "string" ? item.trim() : "")).filter(Boolean)
}

function cleanStringList(value: string[] | undefined): string[] {
  return Array.isArray(value) ? value.map((item) => item.trim()).filter(Boolean) : []
}
