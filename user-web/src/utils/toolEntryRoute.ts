import type { RouteLocationRaw } from "vue-router"
import { isPptWorkspaceTool } from "@/api/pptApi"
import { userRoutes } from "@/router/userRoutes"

/** 工具超市 / 列表卡片点击后的入口路由 */
export function toolEntryRoute(toolCode: string, integrationMode?: string | null): RouteLocationRaw {
  if (isPptWorkspaceTool(toolCode, integrationMode)) {
    return userRoutes.pptWorkspace()
  }
  return { path: `/chat/${encodeURIComponent(toolCode)}` }
}
