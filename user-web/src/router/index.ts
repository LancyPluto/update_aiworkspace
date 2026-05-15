import { createRouter, createWebHistory } from "vue-router"
import { useAuthStore } from "@/store/authStore"

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: "/", redirect: "/agent" },
    {
      path: "/login",
      name: "Login",
      meta: { requiresAuth: false },
      component: () => import("@/pages/Login/Page.vue"),
    },
    {
      path: "/dashboard",
      name: "Dashboard",
      meta: { requiresAuth: true },
      component: () => import("@/pages/Dashboard/Page.vue"),
    },
    {
      path: "/agent",
      name: "AgentHome",
      meta: { requiresAuth: true },
      component: () => import("@/pages/AgentHome/Page.vue"),
    },
    {
      path: "/marketplace",
      name: "ToolList",
      meta: { requiresAuth: false },
      component: () => import("@/pages/ToolList/Page.vue"),
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
      component: () => import("@/pages/MyTasks/Page.vue"),
    },
    {
      path: "/library",
      name: "MaterialLibrary",
      meta: { requiresAuth: true },
      component: () => import("@/pages/MaterialLibrary/Page.vue"),
    },
    {
      path: "/billing",
      name: "Billing",
      meta: { requiresAuth: true },
      component: () => import("@/pages/Billing/Page.vue"),
    },
    {
      path: "/models",
      name: "ModelWorkbench",
      meta: { requiresAuth: true },
      component: () => import("@/pages/ModelWorkbench/Page.vue"),
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
  ],
})

// 路由守卫：需要登录的页面跳转到登录页
router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.bootstrapComplete) {
    await auth.init()
  }
  if (to.meta.requiresAuth !== false && !auth.isLoggedIn) {
    next({ name: "Login", query: { redirect: to.fullPath } })
  } else {
    next()
  }
})

export default router
