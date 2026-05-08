import { http, unwrap } from './http'
import type {
  CreatePromptPayload,
  CreatePromptVersionPayload,
  PromptRecord,
  PromptVersionRecord,
  TestGenerateResult
} from '@/types'

export function fetchPrompts(toolId: number) {
  return unwrap<PromptRecord[]>(http.get(`/api/admin/v1/tools/${toolId}/prompts`))
}

export function createPrompt(toolId: number, payload: CreatePromptPayload) {
  return unwrap<PromptRecord>(http.post(`/api/admin/v1/tools/${toolId}/prompts`, payload))
}

export function fetchPromptVersions(promptId: number) {
  return unwrap<PromptVersionRecord[]>(http.get(`/api/admin/v1/prompts/${promptId}/versions`))
}

export function createPromptVersion(promptId: number, payload: CreatePromptVersionPayload) {
  return unwrap<PromptVersionRecord>(
    http.post(`/api/admin/v1/prompts/${promptId}/versions`, payload)
  )
}

export function testGenerate(promptVersionId: number, params: Record<string, unknown>) {
  return unwrap<TestGenerateResult>(
    http.post(`/api/admin/v1/prompt-versions/${promptVersionId}/test-generate`, { params })
  )
}

export function publishPromptVersion(promptVersionId: number) {
  return unwrap<PromptVersionRecord>(
    http.post(`/api/admin/v1/prompt-versions/${promptVersionId}/publish`)
  )
}
