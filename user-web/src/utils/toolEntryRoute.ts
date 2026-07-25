import type { RouteLocationRaw } from "vue-router"

/** 工具超市 / 列表卡片点击后的入口路由。PPT 使用独立工作台，其余保持对话入口。 */
export function toolEntryRoute(toolCode: string, integrationMode?: string | null): RouteLocationRaw {
  if (integrationMode?.toUpperCase() === "PPT_WORKSPACE" || toolCode === "banana_ppt_generator") {
    return { path: "/ppt" }
  }
  return { path: `/chat/${encodeURIComponent(toolCode)}` }
}
