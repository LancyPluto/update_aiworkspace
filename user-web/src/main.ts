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
  await router.isReady()

  // 先挂载再恢复会话，避免有 token 时长时间白屏（/me 在后台完成）
  app.mount("#app")

  const auth = useAuthStore()
  void auth.init()
}

bootstrap()
