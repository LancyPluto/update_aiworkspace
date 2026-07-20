import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

const source = await readFile(new URL("./tool-access-policy.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const {
  filterIndependentAgentAccessTools,
  hasIndependentAgentAccess,
  isAgentEffectivelyEnabled,
  matchesAgentToolStatus,
} = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`
)

test("workflow tools cannot manage Agent access independently", () => {
  assert.equal(hasIndependentAgentAccess({ executionMode: "WORKFLOW" }), false)
  assert.equal(hasIndependentAgentAccess({ executionMode: " workflow " }), false)
  assert.equal(hasIndependentAgentAccess({ executionMode: "DIRECT" }), true)
  assert.equal(hasIndependentAgentAccess({}), true)
})

test("bulk Agent access updates exclude workflow tools", () => {
  const tools = [
    { toolCode: "direct", executionMode: "DIRECT" },
    { toolCode: "workflow", executionMode: "WORKFLOW" },
    { toolCode: "legacy" },
  ]

  assert.deepEqual(
    filterIndependentAgentAccessTools(tools).map((tool) => tool.toolCode),
    ["direct", "legacy"],
  )
})

test("workflow tools are effectively enabled regardless of legacy Agent access", () => {
  assert.equal(isAgentEffectivelyEnabled({ executionMode: "WORKFLOW", agentEnabled: false }), true)
  assert.equal(isAgentEffectivelyEnabled({ executionMode: "WORKFLOW", agentEnabled: true }), true)
  assert.equal(isAgentEffectivelyEnabled({ executionMode: "DIRECT", agentEnabled: false }), false)
  assert.equal(isAgentEffectivelyEnabled({ executionMode: "DIRECT", agentEnabled: true }), true)
  assert.equal(isAgentEffectivelyEnabled({ agentEnabled: false }), false)
})

test("workflow tools do not enter tool-level model or health status filters", () => {
  const workflow = {
    executionMode: "WORKFLOW",
    agentEnabled: false,
    modelConfigId: null,
    healthStatus: "FAILED",
  }

  assert.equal(matchesAgentToolStatus(workflow, "enabled"), true)
  assert.equal(matchesAgentToolStatus(workflow, "disabled"), false)
  assert.equal(matchesAgentToolStatus(workflow, "unbound"), false)
  assert.equal(matchesAgentToolStatus(workflow, "failed"), false)
  assert.equal(matchesAgentToolStatus({ ...workflow, healthStatus: "UNKNOWN" }, "unknown"), false)

  const direct = { executionMode: "DIRECT", agentEnabled: false, modelConfigId: null, healthStatus: "FAILED" }
  assert.equal(matchesAgentToolStatus(direct, "disabled"), true)
  assert.equal(matchesAgentToolStatus(direct, "unbound"), true)
  assert.equal(matchesAgentToolStatus(direct, "failed"), true)
})
