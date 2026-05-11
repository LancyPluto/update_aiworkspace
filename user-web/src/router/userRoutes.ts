/** 用户端路由路径（与 Vue Router 注册一致） */
export const userRoutes = {
  login: "/login",
  toolList: "/marketplace",
  toolDetail: (toolId: string) => `/tools/${toolId}`,
  toolUse: (toolId: string) => `/tools/${toolId}/use`,
  myTasks: "/tasks",
  taskStatus: (taskId: string) => `/tasks/${taskId}/status`,
  taskResult: (taskId: string) => `/tasks/${taskId}/result`,
  billing: "/billing",
  dashboard: "/dashboard",
  library: "/library",
} as const
