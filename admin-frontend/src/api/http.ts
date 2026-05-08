import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

import router from '@/router'
import { clearSession, getToken } from '@/stores/auth'
import type { ApiResponse } from '@/types'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body && body.code && body.code !== 'SUCCESS') {
      return Promise.reject(new Error(body.message || body.code))
    }
    return response
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    if (error.response?.status === 401) {
      clearSession()
      router.push({ name: 'login' })
    }
    const message = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>) {
  const response = await promise
  return response.data.data
}
