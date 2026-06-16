import { createRouter, createWebHistory } from "vue-router"
import { useAuthStore } from "@/store/authStore"

import LoginPage from "@/pages/Login/Page.vue"
import HomePage from "@/pages/Home/Page.vue"
import DashboardPage from "@/pages/Dashboard/Page.vue"
import AgentHomePage from "@/pages/AgentHome/Page.vue"
import ToolListPage from "@/pages/ToolList/Page.vue"
import MyTasksPage from "@/pages/MyTasks/Page.vue"
import MaterialLibraryPage from "@/pages/MaterialLibrary/Page.vue"
import BillingPage from "@/pages/Billing/Page.vue"
import ProfilePage from "@/pages/Profile/Page.vue"
import PublicProfilePage from "@/pages/PublicProfile/Page.vue"
import CommunityDiscoverPage from "@/pages/CommunityDiscover/Page.vue"
import InspirationCollectionsPage from "@/pages/InspirationCollections/Page.vue"

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
      path: "/home",
      name: "Home",
      meta: { requiresAuth: true },
      component: HomePage,
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
      name: "AgentTools",
      meta: { requiresAuth: false },
      component: ToolListPage,
      props: { mode: "agents" },
    },
    {
      path: "/chat/:toolId",
      name: "Chat",
      meta: { requiresAuth: false },
      redirect: (to) => ({
        path: "/dashboard",
        query: { tool: String(to.params.toolId || "") },
      }),
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
      path: "/profile",
      name: "Profile",
      meta: { requiresAuth: true },
      component: ProfilePage,
    },
    {
      path: "/community",
      name: "CommunityDiscover",
      meta: { requiresAuth: false },
      component: CommunityDiscoverPage,
    },
    {
      path: "/community/inspirations",
      name: "InspirationCollections",
      meta: { requiresAuth: true },
      component: InspirationCollectionsPage,
    },
    {
      path: "/u/:userId",
      name: "PublicProfile",
      meta: { requiresAuth: false },
      component: PublicProfilePage,
    },
    {
      path: "/community/posts/:postId",
      name: "CommunityPost",
      meta: { requiresAuth: false },
      component: () => import("@/pages/CommunityPost/Page.vue"),
      props: true,
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
      path: "/workflow/studio/:taskId",
      name: "WorkflowStudio",
      meta: { requiresAuth: true },
      component: () => import("@/pages/WorkflowStudio/Page.vue"),
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
    return "/home"
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
