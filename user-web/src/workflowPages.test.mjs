import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const root = new URL("./", import.meta.url)

async function source(path) {
  return readFile(new URL(path, root), "utf8")
}

test("router exposes the workflow tool center, detail, run, and legacy redirect", async () => {
  const [router, helpers] = await Promise.all([source("router/index.ts"), source("router/userRoutes.ts")])
  assert.match(router, /path: "agents"[\s\S]*AgentPlaceholderPage/)
  assert.match(router, /path: "agents\/tools\/:toolCode"[\s\S]*name: "WorkflowToolDetail"/)
  assert.match(router, /path: "agents\/runs\/:taskId"[\s\S]*name: "WorkflowRun"/)
  assert.match(router, /path: "workflow\/studio\/:taskId"[\s\S]*redirect: redirectLegacyWorkflowRoute/)
  for (const helper of ["workflowTool", "workflowRun", "workflowStudio"]) assert.match(helpers, new RegExp(`${helper}\\(`))
})

test("workflow pages compose the shared run components and explicit recovery states", async () => {
  const [center, detail, run, steps, costs, artifacts, actions] = await Promise.all([
    source("pages/AgentPlaceholder/Page.vue"),
    source("pages/WorkflowToolDetail.vue"),
    source("pages/WorkflowRun/Page.vue"),
    source("components/workflow/WorkflowStepList.vue"),
    source("components/workflow/WorkflowCostSummary.vue"),
    source("components/workflow/WorkflowArtifactViewer.vue"),
    source("components/workflow/WorkflowRunActions.vue"),
  ])
  assert.match(center, /listTools/)
  assert.match(center, /userRoutes\.workflowTool\(tool\.toolCode\)/)
  assert.match(detail, /DynamicForm/)
  assert.match(detail, /clientRequestId/)
  assert.match(detail, /submitting/)
  for (const component of ["WorkflowStepList", "WorkflowCostSummary", "WorkflowArtifactViewer", "WorkflowRunActions"]) {
    assert.match(run, new RegExp(component))
  }
  for (const state of ["403", "404", "legacy", "emptySteps", "emptyArtifacts"]) assert.match(run, new RegExp(state))
  assert.match(steps, /SKIPPED/)
  assert.match(costs, /workflowCostSummary/)
  for (const type of ["TEXT", "JSON", "IMAGE", "AUDIO", "VIDEO", "FILE"]) assert.match(artifacts, new RegExp(type))
  assert.match(actions, /AWAITING_USER/)
  assert.match(actions, /AWAITING_FUNDS/)
})

test("agent timeline exposes delegated workflow run links", async () => {
  const timeline = await source("pages/AgentHome/RunTimeline.vue")
  assert.match(timeline, /runUrl/)
  assert.ok(timeline.includes("\\/agents\\/runs\\/"))
  assert.match(timeline, /workflow-run-link/)
  assert.match(timeline, /payload\.workflowStatus \|\| payload\.status/)
  assert.match(timeline, /status === "FAILED"/)
  assert.match(timeline, /status === "TIMEOUT"/)
  assert.match(timeline, /status === "CANCELLED"/)
})
