export type AudioTrackItem = {
  url: string
  title?: string
  coverUrl?: string
  duration?: number
  downloadName?: string
}

export type ResultBlock =
  | { type: "text"; title: string; content: string }
  | { type: "json"; title: string; content: string }
  | { type: "image"; title: string; images: Array<{ url: string; label?: string }> }
  | { type: "audio"; title: string; url: string; downloadName?: string; tracks?: AudioTrackItem[] }
  | { type: "video"; title: string; url: string; downloadName?: string }
  | { type: "report"; title: string; content: string; filename: string }
  | { type: "list"; title: string; items: string[] }
