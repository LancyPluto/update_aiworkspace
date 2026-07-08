import type { RouteRecordRaw } from "vue-router"

const WorkspaceHomePage = () => import("@/pages/WorkspaceHome/Page.vue")
const CreatorWorkspacePage = () => import("@/pages/CreatorWorkspace/Page.vue")
const AgentHomePage = () => import("@/pages/AgentHome/Page.vue")
const MaterialLibraryPage = () => import("@/pages/MaterialLibrary/Page.vue")
const BillingPage = () => import("@/pages/Billing/Page.vue")
const ProfilePage = () => import("@/pages/Profile/Page.vue")
const InspirationCollectionsPage = () => import("@/pages/InspirationCollections/Page.vue")

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
]
