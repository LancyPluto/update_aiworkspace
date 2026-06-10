import { http } from './http'
import type { AdminCommunityPost, AdminCommunityStats, PageResponse } from './types'

export interface AdminCommunityPostQuery {
  userId?: number
  status?: string
  modality?: string
  keyword?: string
  topic?: string
  featured?: boolean
  auditStatus?: string
  pageNo?: number
  pageSize?: number
}

function toCommunityQueryRecord(query: AdminCommunityPostQuery): Record<string, string | number | boolean | null | undefined> {
  return { ...query }
}

export function fetchAdminCommunityPosts(query: AdminCommunityPostQuery = {}) {
  return http.get<PageResponse<AdminCommunityPost>>('/api/admin/v1/community/posts', toCommunityQueryRecord(query))
}

export function fetchAdminCommunityStats() {
  return http.get<AdminCommunityStats>('/api/admin/v1/community/posts/stats')
}

export function hideAdminCommunityPost(postId: number, reason?: string) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/hide`, { reason })
}

export function restoreAdminCommunityPost(postId: number) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/restore`, {})
}

export function approveAdminCommunityPost(postId: number) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/approve`, {})
}

export function rejectAdminCommunityPost(postId: number, reason?: string) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/reject`, { reason })
}

export function featureAdminCommunityPost(postId: number, enabled: boolean) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/feature`, { enabled })
}

export function pinAdminCommunityPost(postId: number, enabled: boolean) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/pin`, { enabled })
}

export function annotateAdminCommunityPost(postId: number, body: { topic?: string | null; tags?: string[] }) {
  return http.post<AdminCommunityPost>(`/api/admin/v1/community/posts/${postId}/annotate`, body)
}
