//axios请求统一封装
import axios from 'axios'

// 优先用环境配置的接口地址，如果没配置就用默认地址 /api/v1
const baseURL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') || 'http://localhost:5173/api/v1'

export const http = axios.create({
  baseURL,
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
})

//添加授权拦截器
export function attachAuthInterceptor(getToken) {
  http.interceptors.request.use((config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
}
