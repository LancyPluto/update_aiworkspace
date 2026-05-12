import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import router from "./router"
import { useAuthStore } from "./store/authStore"
import "./styles/main.css"

async function bootstrap() {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)
  app.use(router)

  // 等待路由就绪后初始化登录态
  await router.isReady()

  // 尝试从 localStorage 恢复登录态
  const auth = useAuthStore()
  await auth.init()

  app.mount("#app")
}

bootstrap()
