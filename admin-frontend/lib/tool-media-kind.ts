export type ToolMediaKind = "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other"

export interface ToolMediaShape {
  toolCode?: string | null
  name?: string | null
  toolName?: string | null
  category?: string | null
  categoryName?: string | null
  toolType?: string | null
  inputModality?: string | null
  outputModality?: string | null
  executionHandler?: string | null
}

export interface ToolMediaDefaults {
  toolType: string
  inputModality: string
  outputModality: string
  mediaDisplayMode: "icon" | "effect" | "comparison"
  templateCode: string
}

function upper(value?: string | null): string {
  return (value || "").trim().toUpperCase()
}

export function resolveToolMediaKind(tool: ToolMediaShape): ToolMediaKind {
  const type = upper(tool.toolType)
  const input = upper(tool.inputModality)
  const output = upper(tool.outputModality)
  const handler = upper(tool.executionHandler)
  const searchable = [
    tool.toolCode,
    tool.name,
    tool.toolName,
    tool.category,
    tool.categoryName,
    tool.toolType,
    tool.executionHandler,
  ].map((item) => (item || "").trim().toLowerCase()).join(" ")

  if (type === "AGENT") return "agent"
  if (
    handler.includes("DIGITAL_HUMAN") ||
    type.includes("DIGITAL_HUMAN") ||
    searchable.includes("digital_human") ||
    searchable.includes("digital-human") ||
    searchable.includes("数字人") ||
    searchable.includes("口播")
  ) {
    return "digitalHuman"
  }
  if (output === "AUDIO" || input === "AUDIO" || type.includes("AUDIO") || type.includes("SPEECH") || handler.includes("AUDIO")) {
    return "audio"
  }
  if (output === "VIDEO" || type.includes("VIDEO")) return "video"
  if (output === "IMAGE" || input === "IMAGE" || type.includes("IMAGE")) return "image"
  if (output === "TEXT" || input === "TEXT" || type.includes("TEXT")) return "text"
  return "other"
}

export function defaultsForMediaKind(kind: ToolMediaKind): ToolMediaDefaults {
  if (kind === "image") {
    return {
      toolType: "IMAGE_TO_IMAGE",
      inputModality: "IMAGE",
      outputModality: "IMAGE",
      mediaDisplayMode: "comparison",
      templateCode: "image_generation_default",
    }
  }
  if (kind === "video") {
    return {
      toolType: "VIDEO_GENERATION",
      inputModality: "IMAGE",
      outputModality: "VIDEO",
      mediaDisplayMode: "effect",
      templateCode: "video_generation_default",
    }
  }
  if (kind === "digitalHuman") {
    return {
      toolType: "VIDEO_GENERATION",
      inputModality: "MULTIMODAL",
      outputModality: "VIDEO",
      mediaDisplayMode: "effect",
      templateCode: "video_generation_default",
    }
  }
  if (kind === "audio") {
    return {
      toolType: "TEXT_TO_SPEECH",
      inputModality: "TEXT",
      outputModality: "AUDIO",
      mediaDisplayMode: "icon",
      templateCode: "",
    }
  }
  if (kind === "agent") {
    return {
      toolType: "AGENT",
      inputModality: "MULTIMODAL",
      outputModality: "TEXT",
      mediaDisplayMode: "icon",
      templateCode: "",
    }
  }
  if (kind === "other") {
    return {
      toolType: "CUSTOM",
      inputModality: "MULTIMODAL",
      outputModality: "FILE",
      mediaDisplayMode: "icon",
      templateCode: "",
    }
  }
  return {
    toolType: "TEXT_GENERATION",
    inputModality: "TEXT",
    outputModality: "TEXT",
    mediaDisplayMode: "icon",
    templateCode: "text_generation_default",
  }
}
