export type ResultBlock =
  | { type: "text"; title: string; content: string }
  | { type: "video"; title: string; url: string; downloadName?: string }
  | { type: "report"; title: string; content: string; filename: string }
  | { type: "list"; title: string; items: string[] }
