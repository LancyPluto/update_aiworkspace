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
  const configs = await http.get<AgentModelConfig[]>(`${MODEL_CONFIG_PATH}/list`)
  return configs.map(normalizeSingleConfig)
}

export function saveAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.put<AgentModelConfig>(MODEL_CONFIG_PATH, payload)
}

export function createAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.post<AgentModelConfig>(MODEL_CONFIG_PATH, payload)
}

export function updateAgentModelConfig(id: number, payload: AgentModelConfigPayload) {
  return http.put<AgentModelConfig>(`${MODEL_CONFIG_PATH}/${id}`, payload)
}

export function setDefaultAgentModelConfig(id: number) {
  return http.post<AgentModelConfig>(`${MODEL_CONFIG_PATH}/${id}/default`)
}

export function deleteAgentModelConfig(id: number) {
  return http.delete<void>(`${MODEL_CONFIG_PATH}/${id}`)
}

export function testAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.post<AgentModelConfigTestResult>(`${MODEL_CONFIG_PATH}/test`, payload)
}

export async function testSavedAgentModelConfig() {
  const config = await fetchAgentModelConfig()
  return testAgentModelConfig({
    displayName: config.displayName || undefined,
    configCode: config.configCode || undefined,
    provider: config.provider,
    modelName: config.modelName,
    baseUrl: config.baseUrl || undefined,
    extraAuthJson: undefined,
    minimaxGroupId: config.minimaxGroupId || undefined,
    timeoutSeconds: config.timeoutSeconds,
    inputTokenPricePer1m: config.inputTokenPricePer1m ?? ((config.inputTokenPricePer1k || 0) * 1000),
    outputTokenPricePer1m: config.outputTokenPricePer1m ?? ((config.outputTokenPricePer1k || 0) * 1000),
    billingUnit: config.billingUnit || 'TOKEN_PER_M',
    unitPrice: config.unitPrice || 0,
    enabled: config.enabled,
    isDefault: config.isDefault ?? true,
    capabilities: config.capabilities ?? undefined,
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
