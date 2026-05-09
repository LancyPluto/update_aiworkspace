import { createRouter, createWebHistory } from "vue-router"


export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: "/", redirect: "/login" },
    { path: "/dashboard", redirect: "/marketplace" },
    { path: "/billing", redirect: "/marketplace" },
    { path: "/library", redirect: "/marketplace" },
    {
      path: "/login",
      name: "Login",
      component: () => import("@/pages/Login/Page.vue"),
    },
    {
      path: "/marketplace",
      name: "ToolList",
      component: () => import("@/pages/ToolList/Page.vue"),
    },
    {
      path: "/tools/:id",
      name: "ToolDetail",
      component: () => import("@/pages/ToolDetail/Page.vue"),
      props: true,
    },
    {
      path: "/tools/:id/use",
      name: "ToolUse",
      component: () => import("@/pages/ToolUse/Page.vue"),
      props: true,
    },
    {
      path: "/tasks",
      name: "MyTasks",
      component: () => import("@/pages/MyTasks/Page.vue"),
    },
    {
      path: "/tasks/:taskId/status",
      name: "TaskStatus",
      component: () => import("@/pages/TaskStatus/Page.vue"),
      props: true,
    },
    {
      path: "/tasks/:taskId/result",
      name: "TaskResult",
      component: () => import("@/pages/TaskResult/Page.vue"),
      props: true,
    },
  ],
})

// //路由守卫
// router.beforeEach((to, from, next) => {
//   if (to.path === "/login") {
//     next()
//   } else {
//     next({ path: "/login" })
//   }
// })
