/**
 * 用户端路由名称常量，方便各处引用。
 */
export const userRoutes = {
  get login() {
    return { name: "Login" }
  },
  get toolList() {
    return { name: "ToolList" }
  },
  toolDetail(id: string) {
    return { name: "ToolDetail", params: { id } }
  },
  toolUse(id: string) {
    return { name: "ToolUse", params: { id } }
  },
  get myTasks() {
    return { name: "MyTasks" }
  },
  taskStatus(taskId: string) {
    return { name: "TaskStatus", params: { taskId } }
  },
  taskResult(taskId: string) {
    return { name: "TaskResult", params: { taskId } }
  },
} as const
