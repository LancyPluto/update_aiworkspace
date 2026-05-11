export type ResultBlock =
  | { type: "text"; title: string; content: string }
  | { type: "list"; title: string; items: string[] }
