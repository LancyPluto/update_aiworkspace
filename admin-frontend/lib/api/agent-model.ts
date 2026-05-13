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

export function saveAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.put<AgentModelConfig>(MODEL_CONFIG_PATH, payload)
}

export function testAgentModelConfig(payload: AgentModelConfigPayload) {
  return http.post<AgentModelConfigTestResult>(`${MODEL_CONFIG_PATH}/test`, payload)
}
