import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import router from "./router"
import { applyAppTheme, applyBrandAccent, getStoredBrandAccent, getStoredTheme } from "./utils/theme"
import "./styles/main.css"
import "./styles/workspace.css"
// 与路由同步导入一致：启动时即参与 Tailwind 扫描，避免首跳懒加载样式滞后
import "@/components/AppShell.vue"

async function bootstrap() {
  applyAppTheme(getStoredTheme())
  applyBrandAccent(getStoredBrandAccent())

  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)
  app.use(router)
  await router.isReady()

  // 会话恢复由 router.beforeEach 中的 auth.init() 完成；此处勿重复 init，避免 /me 二次失败误清 token
  app.mount("#app")
}

bootstrap()
