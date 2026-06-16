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
  get home() {
    return { name: "Home" }
  },
  get agent() {
    return { name: "AgentHome" }
  },
  get toolList() {
    return { name: "ToolList" }
  },
  get agentPlaceholder() {
    return { name: "AgentTools" }
  },
  get agentTools() {
    return { name: "AgentTools" }
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
  get community() {
    return { name: "CommunityDiscover" }
  },
  get profile() {
    return { name: "Profile" }
  },
  publicProfile(userId: string | number) {
    return { name: "PublicProfile", params: { userId: String(userId) } }
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
  workflowStudio(taskId: string) {
    return { name: "WorkflowStudio", params: { taskId } }
  },
} as const
