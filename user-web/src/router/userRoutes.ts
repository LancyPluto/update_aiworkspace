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
  get ppt() {
    return { name: "PptProjects" }
  },
  get pptNew() {
    return { name: "PptProjectCreate" }
  },
  pptProject(projectId: string | number) {
    return { name: "PptProjectWorkspace", params: { projectId: String(projectId) } }
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
  workflowTool(toolCode: string) {
    return { name: "WorkflowToolDetail", params: { toolCode } }
  },
  workflowRun(taskId: string | number) {
    return { name: "WorkflowRun", params: { taskId: String(taskId) } }
  },
  get comicProjects() {
    return { name: "ComicProjects" }
  },
  comicProject(projectId: string | number) {
    return { name: "ComicProjectWorkspace", params: { projectId: String(projectId) } }
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
  get subjectLibrary() {
    return { name: "SubjectLibrary" }
  },
  get community() {
    return { name: "CommunityDiscover" }
  },
  get learningCenter() {
    return { name: "LearningCenter" }
  },
  get profile() {
    return { name: "Profile" }
  },
  publicProfile(publicCode: string | number) {
    return { name: "PublicProfile", params: { publicCode: String(publicCode) } }
  },
  get billing() {
    return { name: "Billing" }
  },
  get referral() {
    return { name: "Home", query: { dialog: "referral" } }
  },
  taskStatus(taskId: string) {
    return { name: "TaskStatus", params: { taskId } }
  },
  taskResult(taskId: string) {
    return { name: "TaskResult", params: { taskId } }
  },
  workflowStudio(taskId: string) {
    return { name: "WorkflowRun", params: { taskId } }
  },
} as const
