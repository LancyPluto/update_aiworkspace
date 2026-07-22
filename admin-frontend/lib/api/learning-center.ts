import { http } from './http'

export type LearningCategory = {
  id: number
  name: string
  sortOrder: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export type LearningTutorial = {
  id: number
  categoryId: number
  title: string
  summary: string
  coverImageUrl: string
  videoUrl: string
  sortOrder: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export type CategoryPayload = Pick<LearningCategory, 'name' | 'sortOrder' | 'enabled'>
export type TutorialPayload = Pick<
  LearningTutorial,
  'categoryId' | 'title' | 'summary' | 'coverImageUrl' | 'videoUrl' | 'sortOrder' | 'enabled'
>

export function fetchLearningCategories() {
  return http.get<LearningCategory[]>('/api/admin/v1/learning-center/categories')
}

export function createLearningCategory(payload: CategoryPayload) {
  return http.post<LearningCategory>('/api/admin/v1/learning-center/categories', payload)
}

export function updateLearningCategory(id: number, payload: CategoryPayload) {
  return http.put<LearningCategory>(`/api/admin/v1/learning-center/categories/${id}`, payload)
}

export function deleteLearningCategory(id: number) {
  return http.delete<void>(`/api/admin/v1/learning-center/categories/${id}`)
}

export function fetchLearningTutorials(categoryId?: number) {
  return http.get<LearningTutorial[]>('/api/admin/v1/learning-center/tutorials', { categoryId })
}

export function createLearningTutorial(payload: TutorialPayload) {
  return http.post<LearningTutorial>('/api/admin/v1/learning-center/tutorials', payload)
}

export function updateLearningTutorial(id: number, payload: TutorialPayload) {
  return http.put<LearningTutorial>(`/api/admin/v1/learning-center/tutorials/${id}`, payload)
}

export function deleteLearningTutorial(id: number) {
  return http.delete<void>(`/api/admin/v1/learning-center/tutorials/${id}`)
}

export type CoverUploadResult = { url: string; filename: string; contentType: string; fileSize: number }

export function uploadLearningCover(file: File) {
  const form = new FormData()
  form.append('file', file)
  return http.postForm<CoverUploadResult>('/api/admin/v1/learning-center/covers/upload', form)
}
