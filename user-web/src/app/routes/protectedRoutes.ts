import type { RouteRecordRaw } from "vue-router"

import WorkspaceHomePage from "@/pages/WorkspaceHome/Page.vue"
import CreatorWorkspacePage from "@/pages/CreatorWorkspace/Page.vue"
import AgentHomePage from "@/pages/AgentHome/Page.vue"
import MaterialLibraryPage from "@/pages/MaterialLibrary/Page.vue"
import BillingPage from "@/pages/Billing/Page.vue"
import ProfilePage from "@/pages/Profile/Page.vue"
import InspirationCollectionsPage from "@/pages/InspirationCollections/Page.vue"

export const protectedRoutes: RouteRecordRaw[] = [
  {
    path: "/home",
    name: "Home",
    meta: { requiresAuth: true },
    component: WorkspaceHomePage,
  },
  {
    path: "/create",
    name: "CreatorWorkspace",
    meta: { requiresAuth: true },
    component: CreatorWorkspacePage,
  },
  {
    path: "/dashboard",
    name: "Dashboard",
    meta: { requiresAuth: true },
    redirect: (to) => ({ path: "/create", query: to.query }),
  },
  {
    path: "/agent",
    name: "AgentHome",
    meta: { requiresAuth: true },
    component: AgentHomePage,
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
    redirect: (to) => ({ path: "/create", query: to.query }),
  },
  {
    path: "/assets",
    name: "MaterialLibrary",
    meta: { requiresAuth: true },
    component: MaterialLibraryPage,
  },
  {
    path: "/library",
    redirect: (to) => ({ path: "/assets", query: to.query }),
  },
  {
    path: "/profile",
    name: "Profile",
    meta: { requiresAuth: true },
    component: ProfilePage,
  },
  {
    path: "/community/inspirations",
    name: "InspirationCollections",
    meta: { requiresAuth: true },
    component: InspirationCollectionsPage,
  },
  {
    path: "/billing",
    name: "Billing",
    meta: { requiresAuth: true },
    component: BillingPage,
  },
  {
    path: "/pricing",
    redirect: (to) => ({ path: "/billing", query: to.query }),
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
]
