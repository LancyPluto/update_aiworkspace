import { createRouter, createWebHistory } from "vue-router"
import { useAuthStore } from "@/store/authStore"
import { publicRoutes } from "@/app/routes/publicRoutes"
import { protectedRoutes } from "@/app/routes/protectedRoutes"

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    ...publicRoutes,
    ...protectedRoutes,
  ],
})

function resolvePostLoginRedirect(raw: unknown): string {
  if (
    typeof raw !== "string" ||
    !raw.startsWith("/") ||
    raw === "/" ||
    raw === "/login" ||
    raw.startsWith("/login?")
  ) {
    return "/home"
  }
  return raw
}

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.bootstrapComplete) {
    await auth.init()
  }
  if ((to.name === "Login" || to.name === "RootLogin") && auth.isLoggedIn) {
    next(resolvePostLoginRedirect(to.query.redirect))
    return
  }
  if (to.meta.requiresAuth !== false && !auth.isLoggedIn) {
    next({ name: "RootLogin", query: { redirect: to.fullPath } })
  } else {
    next()
  }
})

export default router
export { resolvePostLoginRedirect }
