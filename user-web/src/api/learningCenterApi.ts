import { apiRequest } from "./client"

export type LearningTutorial = {
  id: number
  title: string
  summary: string
  coverImageUrl: string
  videoUrl: string
}

export type LearningCategory = {
  id: number
  name: string
  tutorials: LearningTutorial[]
}

export type TeacherContact = {
  enabled: boolean
  description: string
  qrCodeUrl: string
}

export type LearningCenterResponse = {
  categories: LearningCategory[]
  teacherContact: TeacherContact
}

export function fetchLearningCenter(options?: { token?: string | null }) {
  return apiRequest<LearningCenterResponse>("GET", "/api/v1/learning-center", { token: options?.token })
}
