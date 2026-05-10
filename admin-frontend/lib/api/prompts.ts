import { http } from './http'
import type {
  CreatePromptPayload,
  CreatePromptVersionPayload,
  PromptRecord,
  PromptVersionRecord,
  TestGenerateResult,
} from './types'

export function fetchPrompts(toolId: number) {
  return http.get<PromptRecord[]>(`/api/admin/v1/tools/${toolId}/prompts`)
}

export function createPrompt(toolId: number, payload: CreatePromptPayload) {
  return http.post<PromptRecord>(`/api/admin/v1/tools/${toolId}/prompts`, payload)
}

export function fetchPromptVersions(promptId: number) {
  return http.get<PromptVersionRecord[]>(`/api/admin/v1/prompts/${promptId}/versions`)
}

export function createPromptVersion(
  promptId: number,
  payload: CreatePromptVersionPayload,
) {
  return http.post<PromptVersionRecord>(
    `/api/admin/v1/prompts/${promptId}/versions`,
    payload,
  )
}

export function publishPromptVersion(promptVersionId: number) {
  return http.post<PromptVersionRecord>(
    `/api/admin/v1/prompt-versions/${promptVersionId}/publish`,
  )
}

export function testGenerate(
  promptVersionId: number,
  params: Record<string, unknown>,
) {
  return http.post<TestGenerateResult>(
    `/api/admin/v1/prompt-versions/${promptVersionId}/test-generate`,
    { params },
  )
}
