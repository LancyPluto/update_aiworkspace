import { http } from './http'
import type { PageResponse } from './types'

export interface AgentMemoryItem {
  id: number
  workspaceId: number
  userId: number
  memoryType: string
  title: string
  content: string
  sourceRunId?: number | null
  sourceMessageId?: number | null
  sourceToolCallId?: number | null
  importance?: number | null
  confidence?: number | null
  pinned: boolean
  tagsJson?: string | null
  metadataJson?: string | null
  lastAccessedAt?: string | null
  accessCount?: number | null
  expiresAt?: string | null
  status: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentMemoryQuery {
  userId?: string
  workspaceId?: string
  status?: string
  memoryType?: string
  keyword?: string
  pageNo?: number
  pageSize?: number
}

export interface AgentMemoryPayload {
  memoryType: string
  title: string
  content: string
  importance?: number
  confidence?: number
  pinned?: boolean
  tagsJson?: string
  metadataJson?: string
  expiresAt?: string
}

function params(query: AgentMemoryQuery) {
  const search = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value != null && String(value).trim() !== '') {
      search.set(key, String(value))
    }
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export function fetchAgentMemory(query: AgentMemoryQuery) {
  return http.get<PageResponse<AgentMemoryItem>>(`/api/admin/v1/agent/memory${params(query)}`)
}

export function updateAgentMemory(id: number, payload: AgentMemoryPayload) {
  return http.put<AgentMemoryItem>(`/api/admin/v1/agent/memory/${id}`, payload)
}

export function approveAgentMemory(id: number) {
  return http.post<AgentMemoryItem>(`/api/admin/v1/agent/memory/${id}/approve`, {})
}

export function rejectAgentMemory(id: number) {
  return http.post<AgentMemoryItem>(`/api/admin/v1/agent/memory/${id}/reject`, {})
}

export function deleteAgentMemory(id: number) {
  return http.delete<void>(`/api/admin/v1/agent/memory/${id}`)
}
