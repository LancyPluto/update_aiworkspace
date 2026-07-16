type WorkflowQueryValue = string | number | null | undefined | Array<string | number | null | undefined>

interface LegacyWorkflowRoute {
  params: Record<string, unknown>
  query: Record<string, WorkflowQueryValue>
  hash: string
}

export function redirectLegacyWorkflowRoute(to: LegacyWorkflowRoute) {
  return {
    name: "WorkflowRun",
    params: { taskId: String(to.params.taskId ?? "") },
    query: to.query,
    hash: to.hash,
    replace: true,
  }
}
