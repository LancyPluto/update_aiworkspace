import type { AITool, UpsertAIToolPayload } from '../ai-tool-types'

const MOCK_TOOLS: AITool[] = [
  {
    id: 'doubao',
    name: '豆包',
    iconUrl: 'https://api.dicebear.com/7.x/shapes/svg?seed=doubao',
    description: '生图 + 文件阅读，适合日常创作',
    enabled: true,
    order: 10,
    primaryColor: '#f97316',
    welcomeMessage: '你好，我是豆包~ 有什么可以帮你？',
    capabilities: [
      {
        type: 'imageGeneration',
        config: { aspectRatios: ['1:1', '16:9', '9:16'], defaultRatio: '1:1', maxImagesPerRequest: 1 },
      },
      { type: 'fileReading', config: { supportedFileTypes: ['pdf', 'txt', 'png'], maxSizeMB: 20 } },
    ],
  },
  {
    id: 'wenxin',
    name: '文心一言',
    iconUrl: 'https://api.dicebear.com/7.x/shapes/svg?seed=wenxin',
    description: '联网搜索 + 代码执行',
    enabled: true,
    order: 20,
    primaryColor: '#3b82f6',
    welcomeMessage: '你好，我是文心一言，很高兴为你服务。',
    capabilities: [
      { type: 'webSearch', config: { enabled: true, defaultEnabled: false } },
      { type: 'codeExecution', config: { supportedLanguages: ['python', 'javascript'] } },
    ],
  },
  {
    id: 'qwen',
    name: '阿里云百炼',
    iconUrl: 'https://api.dicebear.com/7.x/shapes/svg?seed=qwen',
    description: '多模态对话（已下架示例）',
    enabled: false,
    order: 30,
    primaryColor: '#8b5cf6',
    welcomeMessage: '你好，我是阿里云百炼。',
    capabilities: [{ type: 'voiceInput', config: { language: 'zh-CN' } }],
  },
]

let store = [...MOCK_TOOLS]

function delay(ms = 200) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function isMockMode(): boolean {
  return process.env.NEXT_PUBLIC_AI_TOOL_MOCK !== '0'
}

export async function mockFetchAdminAITools(): Promise<AITool[]> {
  await delay()
  return [...store].sort((a, b) => a.order - b.order)
}

export async function mockCreateAITool(payload: UpsertAIToolPayload & { id?: string }): Promise<AITool> {
  await delay()
  const id = payload.id || `tool-${Date.now()}`
  const tool: AITool = { ...payload, id } as AITool
  store.push(tool)
  return tool
}

export async function mockUpdateAITool(id: string, payload: UpsertAIToolPayload): Promise<AITool> {
  await delay()
  const index = store.findIndex((t) => t.id === id)
  if (index < 0) throw new Error('工具不存在')
  const updated = { ...store[index], ...payload, id }
  store[index] = updated
  return updated
}

export async function mockDeleteAITool(id: string): Promise<void> {
  await delay()
  store = store.filter((t) => t.id !== id)
}

export async function mockUploadAIToolIcon(file: File): Promise<{ url: string }> {
  await delay(300)
  return { url: URL.createObjectURL(file) }
}

export function mockEnabledTools(): AITool[] {
  return store.filter((t) => t.enabled).sort((a, b) => a.order - b.order)
}

export function mockGetTool(id: string): AITool | undefined {
  return store.find((t) => t.id === id)
}
