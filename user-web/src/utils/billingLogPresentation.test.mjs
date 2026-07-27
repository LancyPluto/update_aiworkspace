import assert from "node:assert/strict"
import test from "node:test"

const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

async function importTsModule(path) {
  const source = await (await import("node:fs/promises")).readFile(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const billing = await importTsModule("./billingLogPresentation.ts")

function statement(overrides = {}) {
  return {
    id: 601,
    userId: 7,
    sourceType: "WORKFLOW_STEP",
    sourceRef: 91,
    logType: "DEDUCT",
    amount: 3,
    reason: "workflow step success credit deduction",
    createdAt: "2026-07-27 12:00:00",
    workflowRunId: 31,
    workflowId: 3,
    workflowName: "AI漫剧工作流",
    workflowNodeId: "script-split",
    workflowStepName: "AI拆分分镜",
    modelName: "qwen-plus",
    provider: "dashscope",
    ...overrides,
  }
}

test("workflow statement displays workflow, step and model names with actual wallet amount", () => {
  const row = billing.statementLogToBillingRow(statement())
  assert.equal(row.reason, "AI漫剧工作流 · AI拆分分镜 · qwen-plus")
  assert.equal(row.changeText, "-3")
  assert.equal(row.negative, true)
})

test("task and agent statements keep readable legacy labels", () => {
  assert.equal(
    billing.statementReason(statement({
      sourceType: "TASK",
      sourceRef: 41,
      taskNo: "TASK-41",
      toolName: "商品文案",
    })),
    "商品文案 · 任务 TASK-41 · qwen-plus",
  )
  assert.equal(
    billing.statementReason(statement({ sourceType: "AGENT_RUN", sourceRef: 42, agentRunId: 42 })),
    "Agent 会话 #42 · qwen-plus",
  )
})

test("recharge and manual records preserve positive and negative presentation", () => {
  const recharge = billing.statementLogToBillingRow(statement({
    sourceType: null,
    sourceRef: null,
    logType: "RECHARGE",
    amount: 100,
    reason: "async recharge credit dispatch",
  }))
  const manual = billing.statementLogToBillingRow(statement({
    sourceType: null,
    sourceRef: null,
    logType: "MANUAL_DEDUCT",
    amount: 5,
    reason: "退款调整",
  }))

  assert.deepEqual([recharge.reason, recharge.changeText, recharge.negative], ["充值到账", "+100", false])
  assert.deepEqual([manual.reason, manual.changeText, manual.negative], ["后台扣减算力：退款调整", "-5", true])
})
