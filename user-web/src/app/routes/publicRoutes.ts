import type { RouteRecordRaw } from "vue-router"

const LoginPage = () => import("@/pages/Login/Page.vue")
const VideoToolsPage = () => import("@/pages/VideoTools/Page.vue")
const ImageToolsPage = () => import("@/pages/ImageTools/Page.vue")
const ToolCenterPage = () => import("@/pages/ToolCenter/Page.vue")
const AgentPlaceholderPage = () => import("@/pages/AgentPlaceholder/Page.vue")
const PublicProfilePage = () => import("@/pages/PublicProfile/Page.vue")
const CommunityDiscoverPage = () => import("@/pages/CommunityDiscover/Page.vue")

export const publicRoutes: RouteRecordRaw[] = [
  {
    path: "/",
    name: "RootLogin",
    meta: { requiresAuth: false },
    component: LoginPage,
  },
  {
    path: "/login",
    name: "Login",
    meta: { requiresAuth: false },
    component: LoginPage,
  },
  {
    path: "/video",
    name: "VideoTools",
    meta: { requiresAuth: false },
    component: VideoToolsPage,
  },
  {
    path: "/image",
    name: "ImageTools",
    meta: { requiresAuth: false },
    component: ImageToolsPage,
  },
  {
    path: "/tool",
    name: "ToolList",
    meta: { requiresAuth: false },
    component: ToolCenterPage,
  },
  {
    path: "/tools",
    redirect: (to) => ({ path: "/tool", query: to.query }),
  },
  {
    path: "/marketplace",
    redirect: (to) => ({ path: "/tool", query: to.query }),
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
    component: () => import("@/pages/Chat/Page.vue"),
  },
  {
    path: "/tools/:id",
    name: "ToolDetail",
    meta: { requiresAuth: false },
    component: () => import("@/pages/ToolDetail/Page.vue"),
    props: true,
  },
  {
    path: "/community",
    name: "CommunityDiscover",
    meta: { requiresAuth: false },
    component: CommunityDiscoverPage,
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
]
