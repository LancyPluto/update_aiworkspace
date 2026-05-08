import { http, unwrap } from './http'
import type { PromptDraft } from '@/types'

export interface PromptRecord {
  id: number
  promptCode: string
  promptName: string
  status: string
  activeVersionId?: number
}

export interface PromptVersion {
  id: number
  versionNo: string
  content: string
  status: string
}

export function fetchPrompts(toolId: number) {
  return unwrap<PromptRecord[]>(http.get(`/api/admin/v1/tools/${toolId}/prompts`))
}

export function createPrompt(toolId: number, payload: PromptDraft) {
  return unwrap<PromptRecord>(http.post(`/api/admin/v1/tools/${toolId}/prompts`, payload))
}

export function createPromptVersion(promptId: number, payload: { versionNo: string; content: string }) {
  return unwrap<PromptVersion>(http.post(`/api/admin/v1/prompts/${promptId}/versions`, payload))
}

export function testGenerate(promptVersionId: number, payload: Record<string, unknown>) {
  return unwrap<{ output: string }>(http.post(`/api/admin/v1/prompt-versions/${promptVersionId}/test-generate`, payload))
}

export function publishPromptVersion(promptVersionId: number) {
  return unwrap<PromptVersion>(http.post(`/api/admin/v1/prompt-versions/${promptVersionId}/publish`))
}
