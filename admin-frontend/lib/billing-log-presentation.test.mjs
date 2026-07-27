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

const presentation = await importTsModule("./billing-log-presentation.ts")

const workflowUsage = {
  sourceType: "WORKFLOW_STEP",
  sourceId: 91,
  workflowRunId: 31,
  workflowId: 3,
  workflowName: "AI漫剧工作流",
  workflowNodeId: "script-split",
  workflowStepName: "AI拆分分镜",
}

test("admin billing source shows workflow and step names", () => {
  assert.equal(presentation.billingUsageSourceLabel(workflowUsage), "AI漫剧工作流 · AI拆分分镜")
})

test("workflow billing search includes names and technical identifiers", () => {
  const searchText = presentation.billingUsageSearchText(workflowUsage)
  for (const term of ["AI漫剧工作流", "AI拆分分镜", "script-split", "31", "3"]) {
    assert.equal(searchText.includes(term.toLowerCase()), true, `missing ${term}`)
  }
})

test("task and agent source labels keep their existing format", () => {
  assert.equal(presentation.billingUsageSourceLabel({ sourceType: "TASK", sourceId: 7 }), "#7")
  assert.equal(presentation.billingUsageSourceLabel({ sourceType: "AGENT_RUN", sourceId: 8 }), "Agent运行 #8")
})
