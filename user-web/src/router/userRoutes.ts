/**
 * 用户端路由名称常量，方便各处引用。
 */
export const userRoutes = {
  get login() {
    return { name: "Login" }
  },
  get dashboard() {
    return { name: "Dashboard" }
  },
  get agent() {
    return { name: "AgentHome" }
  },
  get toolList() {
    return { name: "ToolList" }
  },
  get agentPlaceholder() {
    return { name: "AgentPlaceholder" }
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
  get materialLibrary() {
    return { name: "MaterialLibrary" }
  },
  get profile() {
    return { name: "Profile" }
  },
  get billing() {
    return { name: "Billing" }
  },
  taskStatus(taskId: string) {
    return { name: "TaskStatus", params: { taskId } }
  },
  taskResult(taskId: string) {
    return { name: "TaskResult", params: { taskId } }
  },
  pptWorkspace() {
    return { name: "PptWorkspace" }
  },
  pptProjectEditor(bindingId: string | number) {
    return { name: "PptProjectEditor", params: { bindingId: String(bindingId) } }
  },
} as const
