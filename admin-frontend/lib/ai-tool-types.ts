export type CapabilityType =
  | 'imageGeneration'
  | 'fileReading'
  | 'webSearch'
  | 'codeExecution'
  | 'voiceInput'

export interface ImageGenerationConfig {
  aspectRatios: string[]
  defaultRatio: string
  maxImagesPerRequest?: number
}

export interface FileReadingConfig {
  supportedFileTypes: string[]
  maxSizeMB: number
}

export interface WebSearchConfig {
  enabled: boolean
  defaultEnabled?: boolean
}

export interface CodeExecutionConfig {
  supportedLanguages: string[]
}

export interface VoiceInputConfig {
  language?: string
}

export type CapabilityConfig =
  | ImageGenerationConfig
  | FileReadingConfig
  | WebSearchConfig
  | CodeExecutionConfig
  | VoiceInputConfig

export interface Capability {
  type: CapabilityType
  config: Record<string, unknown>
}

export interface AITool {
  id: string
  name: string
  iconUrl: string
  description?: string
  enabled: boolean
  order: number
  primaryColor?: string
  welcomeMessage?: string
  capabilities: Capability[]
}

export type UpsertAIToolPayload = Omit<AITool, 'id'> & { id?: string }

export const CAPABILITY_TYPE_OPTIONS: Array<{ value: CapabilityType; label: string }> = [
  { value: 'imageGeneration', label: '图片生成' },
  { value: 'fileReading', label: '文件阅读' },
  { value: 'webSearch', label: '联网搜索' },
  { value: 'codeExecution', label: '代码执行' },
  { value: 'voiceInput', label: '语音输入' },
]

export const ASPECT_RATIO_OPTIONS = ['1:1', '16:9', '9:16', '4:3', '3:4']

export const FILE_TYPE_OPTIONS = ['pdf', 'txt', 'doc', 'docx', 'png', 'jpg', 'jpeg', 'webp']

export const CODE_LANGUAGE_OPTIONS = ['python', 'javascript', 'typescript', 'java', 'go', 'rust']

export function capabilityTypeLabel(type: CapabilityType): string {
  return CAPABILITY_TYPE_OPTIONS.find((item) => item.value === type)?.label || type
}

export function defaultCapabilityConfig(type: CapabilityType): Record<string, unknown> {
  switch (type) {
    case 'imageGeneration':
      return { aspectRatios: ['1:1', '16:9'], defaultRatio: '1:1', maxImagesPerRequest: 1 }
    case 'fileReading':
      return { supportedFileTypes: ['pdf', 'txt', 'png'], maxSizeMB: 20 }
    case 'webSearch':
      return { enabled: true, defaultEnabled: false }
    case 'codeExecution':
      return { supportedLanguages: ['python', 'javascript'] }
    case 'voiceInput':
      return { language: 'zh-CN' }
    default:
      return {}
  }
}

export function createEmptyCapability(type: CapabilityType = 'imageGeneration'): Capability {
  return { type, config: defaultCapabilityConfig(type) }
}

export function createEmptyTool(order = 10): UpsertAIToolPayload {
  return {
    name: '',
    iconUrl: '',
    description: '',
    enabled: true,
    order,
    primaryColor: '#3b82f6',
    welcomeMessage: '',
    capabilities: [],
  }
}
