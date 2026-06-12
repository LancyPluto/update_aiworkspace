import type { RouteLocationRaw } from "vue-router"

/** 工具超市 / 列表卡片点击后的入口路由（PPT 工作台已整体下线，统一进入对话） */
export function toolEntryRoute(toolCode: string, _integrationMode?: string | null): RouteLocationRaw {
  return { path: `/chat/${encodeURIComponent(toolCode)}` }
}
