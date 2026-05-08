//
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import { router } from './router'
import { http, attachAuthInterceptor } from './api/http'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const authStore = useAuthStore()
attachAuthInterceptor(() => authStore.token)


http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      authStore.clearToken()
      const current = router.currentRoute.value
      if (current.meta.requiresAuth) {
        router.replace({
          name: 'login',
          query: { redirect: current.fullPath },
        })
      }
    }
    return Promise.reject(err)
  },
)

app.use(router)
app.mount('#app')

authStore.fetchMe().catch(() => {})
