import { http } from './http'
import type {
  AgentModelConfig,
  AgentModelConfigPayload,
  AgentModelConfigTestResult,
} from './types'

const MODEL_CONFIG_PATH = '/api/admin/v1/agent/model-config'

export function fetchAgentModelConfig() {
  return http.get<AgentModelConfig>(MODEL_CONFIG_PATH)
}

export async function fetchAgentModelConfigs() {
  const config = await fetchAgentModelConfig()
  return [normalizeSingleConfig(config)]
}

export function saveAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.put<AgentModelConfig>(MODEL_CONFIG_PATH, payload)
}

export function createAgentModelConfig(payload: AgentModelConfigPayload) {
  return saveAgentModelConfig(payload)
}

export function updateAgentModelConfig(_id: number, payload: AgentModelConfigPayload) {
  return saveAgentModelConfig(payload)
}

export async function setDefaultAgentModelConfig(_id: number) {
  return fetchAgentModelConfig()
}

export async function deleteAgentModelConfig(_id: number) {
  throw new Error('当前后端暂不支持删除模型配置')
}

export function testAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.post<AgentModelConfigTestResult>(`${MODEL_CONFIG_PATH}/test`, payload)
}

export async function testSavedAgentModelConfig(_id: number) {
  const config = await fetchAgentModelConfig()
  return testAgentModelConfig({
    provider: config.provider,
    modelName: config.modelName,
    baseUrl: config.baseUrl || undefined,
    timeoutSeconds: config.timeoutSeconds,
    enabled: config.enabled,
  })
}

function normalizeSingleConfig(config: AgentModelConfig): AgentModelConfig {
  return {
    ...config,
    displayName: config.displayName || config.modelName,
    configCode: config.configCode || String(config.id || 'default'),
    isDefault: config.isDefault ?? true,
  }
}
