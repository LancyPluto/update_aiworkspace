import { createRouter, createWebHistory } from "vue-router"
import { useAuthStore } from "@/store/authStore"

import LoginPage from "@/pages/Login/Page.vue"
import DashboardPage from "@/pages/Dashboard/Page.vue"
import AgentHomePage from "@/pages/AgentHome/Page.vue"
import ToolListPage from "@/pages/ToolList/Page.vue"
import ChatPage from "@/pages/Chat/Page.vue"
import MyTasksPage from "@/pages/MyTasks/Page.vue"
import MaterialLibraryPage from "@/pages/MaterialLibrary/Page.vue"
import BillingPage from "@/pages/Billing/Page.vue"
import AgentPlaceholderPage from "@/pages/AgentPlaceholder/Page.vue"

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/",
      name: "Login",
      meta: { requiresAuth: false },
      component: LoginPage,
    },
    {
      path: "/login",
      redirect: (to) => ({ path: "/", query: to.query }),
    },
    {
      path: "/dashboard",
      name: "Dashboard",
      meta: { requiresAuth: true },
      component: DashboardPage,
    },
    {
      path: "/agent",
      name: "AgentHome",
      meta: { requiresAuth: true },
      component: AgentHomePage,
    },
    {
      path: "/marketplace",
      name: "ToolList",
      meta: { requiresAuth: false },
      component: ToolListPage,
    },
    {
      path: "/agents",
      name: "AgentPlaceholder",
      meta: { requiresAuth: false },
      component: AgentPlaceholderPage,
    },
    {
      path: "/chat/:toolId",
      name: "Chat",
      meta: { requiresAuth: false },
      component: ChatPage,
      props: true,
    },
    {
      path: "/tools/:id",
      name: "ToolDetail",
      meta: { requiresAuth: false },
      component: () => import("@/pages/ToolDetail/Page.vue"),
      props: true,
    },
    {
      path: "/tools/:id/use",
      name: "ToolUse",
      meta: { requiresAuth: true },
      component: () => import("@/pages/ToolUse/Page.vue"),
      props: true,
    },
    {
      path: "/tasks",
      name: "MyTasks",
      meta: { requiresAuth: true },
      component: MyTasksPage,
    },
    {
      path: "/library",
      name: "MaterialLibrary",
      meta: { requiresAuth: true },
      component: MaterialLibraryPage,
    },
    {
      path: "/billing",
      name: "Billing",
      meta: { requiresAuth: true },
      component: BillingPage,
    },
    {
      path: "/tasks/:taskId/status",
      name: "TaskStatus",
      meta: { requiresAuth: true },
      component: () => import("@/pages/TaskStatus/Page.vue"),
      props: true,
    },
    {
      path: "/tasks/:taskId/result",
      name: "TaskResult",
      meta: { requiresAuth: true },
      component: () => import("@/pages/TaskResult/Page.vue"),
      props: true,
    },
    {
      path: "/tools/banana_ppt_generator/workspace",
      name: "PptWorkspace",
      meta: { requiresAuth: true },
      component: () => import("@/pages/PptWorkspace/Page.vue"),
    },
    {
      path: "/tools/banana_ppt_generator/workspace/:bindingId",
      name: "PptProjectEditor",
      meta: { requiresAuth: true },
      component: () => import("@/pages/PptWorkspace/Editor.vue"),
      props: true,
    },
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
    return "/marketplace"
  }
  return raw
}

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.bootstrapComplete) {
    await auth.init()
  }
  if (to.name === "Login" && auth.isLoggedIn) {
    next(resolvePostLoginRedirect(to.query.redirect))
    return
  }
  if (to.meta.requiresAuth !== false && !auth.isLoggedIn) {
    next({ name: "Login", query: { redirect: to.fullPath } })
  } else {
    next()
  }
})

export default router
export { resolvePostLoginRedirect }
