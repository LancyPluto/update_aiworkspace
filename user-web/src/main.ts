import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import router from "./router"
import "./styles/main.css"

<<<<<<< Updated upstream
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount("#app")
=======
async function bootstrap() {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)
  app.use(router)

  // 等待路由就绪后初始化登录态
  await router.isReady()

  // 通过 HttpOnly Cookie 尝试恢复会话（见 authStore.init）
  const auth = useAuthStore()
  await auth.init()

  app.mount("#app")
}

bootstrap()
>>>>>>> Stashed changes
