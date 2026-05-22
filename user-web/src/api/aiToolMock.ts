import type { AITool, ChatMessage, ChatRequest, ChatResponse, ChatSession, FileUploadResult } from "./aiToolTypes"

const MOCK_TOOLS: AITool[] = [
  {
    id: "doubao",
    name: "豆包",
    iconUrl: "https://api.dicebear.com/7.x/shapes/svg?seed=doubao",
    description: "生图 + 文件阅读，适合日常创作",
    enabled: true,
    order: 10,
    primaryColor: "#f97316",
    welcomeMessage: "你好，我是豆包~ 有什么可以帮你？",
    capabilities: [
      {
        type: "imageGeneration",
        config: { aspectRatios: ["1:1", "16:9", "9:16"], defaultRatio: "1:1", maxImagesPerRequest: 1 },
      },
      { type: "fileReading", config: { supportedFileTypes: ["pdf", "txt", "png"], maxSizeMB: 20 } },
    ],
  },
  {
    id: "wenxin",
    name: "文心一言",
    iconUrl: "https://api.dicebear.com/7.x/shapes/svg?seed=wenxin",
    description: "联网搜索 + 代码执行",
    enabled: true,
    order: 20,
    primaryColor: "#3b82f6",
    welcomeMessage: "你好，我是文心一言，很高兴为你服务。",
    capabilities: [
      { type: "webSearch", config: { enabled: true, defaultEnabled: false } },
      { type: "codeExecution", config: { supportedLanguages: ["python", "javascript"] } },
    ],
  },
]

const sessions = new Map<string, ChatSession[]>()
const messages = new Map<string, ChatMessage[]>()

function delay(ms = 200) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function isMockMode(): boolean {
  return import.meta.env.VITE_AI_TOOL_MOCK === "1"
}

function ensureSession(toolId: string): ChatSession[] {
  if (!sessions.has(toolId)) {
    const now = Date.now()
    sessions.set(toolId, [
      {
        id: `sess-${toolId}-1`,
        title: "新对话",
        toolId,
        createdAt: now,
        updatedAt: now,
      },
    ])
    messages.set(`sess-${toolId}-1`, [])
  }
  return sessions.get(toolId)!
}

export async function mockFetchEnabledAITools(): Promise<AITool[]> {
  await delay()
  return MOCK_TOOLS.filter((t) => t.enabled).sort((a, b) => a.order - b.order)
}

export async function mockFetchAIToolById(toolId: string): Promise<AITool> {
  await delay()
  const tool = MOCK_TOOLS.find((t) => t.id === toolId)
  if (!tool || !tool.enabled) throw new Error("该模型已不可用")
  return tool
}

export async function mockFetchChatSessions(toolId: string): Promise<ChatSession[]> {
  await delay()
  return [...ensureSession(toolId)].sort((a, b) => b.updatedAt - a.updatedAt)
}

export async function mockCreateChatSession(toolId: string): Promise<ChatSession> {
  await delay()
  const now = Date.now()
  const session: ChatSession = {
    id: `sess-${toolId}-${now}`,
    title: "新对话",
    toolId,
    createdAt: now,
    updatedAt: now,
  }
  const list = ensureSession(toolId)
  list.unshift(session)
  messages.set(session.id, [])
  return session
}

export async function mockDeleteChatSession(sessionId: string): Promise<void> {
  await delay()
  for (const [toolId, list] of sessions.entries()) {
    sessions.set(
      toolId,
      list.filter((s) => s.id !== sessionId),
    )
  }
  messages.delete(sessionId)
}

export async function mockFetchChatMessages(sessionId: string): Promise<ChatMessage[]> {
  await delay()
  return [...(messages.get(sessionId) || [])]
}

export async function mockSendChatMessage(payload: ChatRequest): Promise<ChatResponse> {
  await delay(600)
  const now = Date.now()
  const userMessage: ChatMessage = {
    id: `msg-u-${now}`,
    role: "user",
    content: payload.content,
    timestamp: now,
    params: payload.params,
  }
  const assistantMessage: ChatMessage = {
    id: `msg-a-${now + 1}`,
    role: "assistant",
    content: `（Mock 回复）收到你的消息：「${payload.content}」\n\n当前参数：${JSON.stringify(payload.params || {}, null, 2)}`,
    timestamp: now + 1,
  }
  const list = messages.get(payload.sessionId) || []
  list.push(userMessage, assistantMessage)
  messages.set(payload.sessionId, list)

  for (const [, sessionList] of sessions.entries()) {
    const session = sessionList.find((s) => s.id === payload.sessionId)
    if (session) {
      session.updatedAt = now
      if (session.title === "新对话") session.title = payload.content.slice(0, 20)
    }
  }

  return { userMessage, assistantMessage }
}

export async function mockUploadChatFile(file: File): Promise<FileUploadResult> {
  await delay(400)
  return { fileId: `file-${Date.now()}`, url: URL.createObjectURL(file) }
}
