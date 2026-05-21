import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import router from "./router"
import { useAuthStore } from "./store/authStore"
import "./styles/main.css"
// 与路由同步导入一致：启动时即参与 Tailwind 扫描，避免首跳懒加载样式滞后
import "@/components/AppShell.vue"

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
