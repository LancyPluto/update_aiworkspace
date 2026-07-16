import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const outputText = stripTypeScriptTypes(source, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const view = await importTsModule("./workflowPresentation.ts")
const routes = await importTsModule("./workflowRoutes.ts")

test("legacy workflow links preserve task, query, and hash", () => {
  assert.deepEqual(routes.redirectLegacyWorkflowRoute({
    params: { taskId: "task-42" },
    query: { from: "history", tab: ["result", "steps"] },
    hash: "#artifact-2",
  }), {
    name: "WorkflowRun",
    params: { taskId: "task-42" },
    query: { from: "history", tab: ["result", "steps"] },
    hash: "#artifact-2",
    replace: true,
  })
})

test("all run states and waiting actions have stable user views", () => {
  const cases = {
    QUEUED: ["排队中", false],
    RUNNING: ["执行中", false],
    AWAITING_USER: ["等待确认", true],
    AWAITING_FUNDS: ["等待充值", true],
    CANCELLING: ["取消中", false],
    SUCCESS: ["已完成", false],
    FAILED: ["失败", false],
    TIMEOUT: ["已超时", false],
    CANCELLED: ["已取消", false],
  }
  for (const [status, [label, needsAction]] of Object.entries(cases)) {
    const result = view.runStatusView(status)
    assert.equal(result.label, label)
    assert.equal(result.needsAction, needsAction)
  }
  assert.deepEqual(view.runActions("AWAITING_USER"), ["feedback", "cancel"])
  assert.deepEqual(view.runActions("AWAITING_FUNDS"), ["recharge", "resume", "cancel"])
  assert.deepEqual(view.runActions("SUCCESS"), [])
  assert.equal(view.stepStatusView("SKIPPED").label, "已跳过")
  assert.equal(view.stepStatusView("NEW_VENDOR_STATE").label, "NEW_VENDOR_STATE")
})

test("cost summaries distinguish zero from unavailable", () => {
  assert.deepEqual(view.workflowCostSummary({ totalCredits: 0, reservedCredits: 0 }), {
    total: "0 算力",
    reserved: "0 算力",
  })
  assert.deepEqual(view.workflowCostSummary({ totalCredits: null, reservedCredits: undefined }), {
    total: "暂不可用",
    reserved: "暂不可用",
  })
})

test("six artifact types and unknown values resolve to usable viewers", () => {
  const expected = {
    TEXT: "text",
    JSON: "json",
    IMAGE: "image",
    AUDIO: "audio",
    VIDEO: "video",
    FILE: "file",
    MODEL_3D: "fallback",
  }
  for (const [type, viewer] of Object.entries(expected)) {
    assert.equal(view.artifactView({ type, name: `${type} output` }).viewer, viewer)
  }
})

test("artifact URLs reject executable and ambiguous protocols", () => {
  assert.equal(view.workflowArtifactUrl("/api/v1/assets/7"), "/api/v1/assets/7")
  assert.equal(view.workflowArtifactUrl("https://cdn.example.com/result.png"), "https://cdn.example.com/result.png")
  assert.equal(view.workflowArtifactUrl("blob:https://example.com/asset"), "blob:https://example.com/asset")
  assert.equal(view.workflowArtifactUrl("javascript:alert(1)"), "")
  assert.equal(view.workflowArtifactUrl("data:text/html,<script>alert(1)</script>"), "")
  assert.equal(view.workflowArtifactUrl("//untrusted.example.com/file"), "")
})
