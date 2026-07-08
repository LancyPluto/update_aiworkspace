import { createRouter, createWebHistory } from "vue-router"
import { useAuthStore } from "@/store/authStore"

import LoginPage from "@/pages/Login/Page.vue"

const AppLayout = () => import("@/layouts/AppLayout.vue")
const HomePage = () => import("@/pages/Home/Page.vue")
const DashboardPage = () => import("@/pages/Dashboard/Page.vue")
const AgentHomePage = () => import("@/pages/AgentHome/Page.vue")
const ToolListPage = () => import("@/pages/ToolList/Page.vue")
const MyTasksPage = () => import("@/pages/MyTasks/Page.vue")
const MaterialLibraryPage = () => import("@/pages/MaterialLibrary/Page.vue")
const BillingPage = () => import("@/pages/Billing/Page.vue")
const ReferralPage = () => import("@/pages/Referral/Page.vue")
const ProfilePage = () => import("@/pages/Profile/Page.vue")
const PublicProfilePage = () => import("@/pages/PublicProfile/Page.vue")
const CommunityDiscoverPage = () => import("@/pages/CommunityDiscover/Page.vue")
const InspirationCollectionsPage = () => import("@/pages/InspirationCollections/Page.vue")

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
      path: "/",
      component: AppLayout,
      children: [
        {
          path: "home",
          name: "Home",
          meta: { requiresAuth: true },
          component: HomePage,
        },
        {
          path: "dashboard",
          name: "Dashboard",
          meta: { requiresAuth: true },
          component: DashboardPage,
        },
        {
          path: "agent",
          name: "AgentHome",
          meta: { requiresAuth: true },
          component: AgentHomePage,
        },
        {
          path: "marketplace",
          name: "ToolList",
          meta: { requiresAuth: false },
          component: ToolListPage,
        },
        {
          path: "agents",
          name: "AgentTools",
          meta: { requiresAuth: false },
          component: ToolListPage,
          props: { mode: "agents" },
        },
        {
          path: "tools/:id",
          name: "ToolDetail",
          meta: { requiresAuth: false },
          component: () => import("@/pages/ToolDetail/Page.vue"),
          props: true,
        },
        {
          path: "tools/:id/use",
          name: "ToolUse",
          meta: { requiresAuth: true },
          component: () => import("@/pages/ToolUse/Page.vue"),
          props: true,
        },
        {
          path: "tasks",
          name: "MyTasks",
          meta: { requiresAuth: true },
          component: MyTasksPage,
        },
        {
          path: "library",
          name: "MaterialLibrary",
          meta: { requiresAuth: true },
          component: MaterialLibraryPage,
        },
        {
          path: "library/subjects",
          name: "SubjectLibrary",
          meta: { requiresAuth: true },
          component: MaterialLibraryPage,
        },
        {
          path: "profile",
          name: "Profile",
          meta: { requiresAuth: true },
          component: ProfilePage,
        },
        {
          path: "community",
          name: "CommunityDiscover",
          meta: { requiresAuth: false },
          component: CommunityDiscoverPage,
        },
        {
          path: "community/inspirations",
          name: "InspirationCollections",
          meta: { requiresAuth: true },
          component: InspirationCollectionsPage,
        },
        {
          path: "billing",
          name: "Billing",
          meta: { requiresAuth: true },
          component: BillingPage,
        },
        {
          path: "referral",
          name: "Referral",
          meta: { requiresAuth: true },
          component: ReferralPage,
        },
        {
          path: "tasks/:taskId/status",
          name: "TaskStatus",
          meta: { requiresAuth: true },
          component: () => import("@/pages/TaskStatus/Page.vue"),
          props: true,
        },
        {
          path: "tasks/:taskId/result",
          name: "TaskResult",
          meta: { requiresAuth: true },
          component: () => import("@/pages/TaskResult/Page.vue"),
          props: true,
        },
        {
          path: "workflow/studio/:taskId",
          name: "WorkflowStudio",
          meta: { requiresAuth: true },
          component: () => import("@/pages/WorkflowStudio/Page.vue"),
          props: true,
        },
      ],
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
      path: "/subjects",
      redirect: "/library/subjects",
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
