import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importRoutingTarget() {
  const source = await readFile(new URL("./model-routing-target.ts", import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}#${Date.now()}`)
}

const accounts = [
  { id: 11, enabled: true, loadBalanceEnabled: true, routingPoolId: 7, routingPoolName: "主池" },
  { id: 12, enabled: false, loadBalanceEnabled: true, routingPoolId: 7, routingPoolName: "主池" },
  { id: 21, enabled: true, loadBalanceEnabled: false, routingPoolId: 8, routingPoolName: "停用池" },
  { id: 31, enabled: true },
]

test("pool targets preserve an enabled existing anchor that is already in the pool", async () => {
  const { resolveModelRoutingTarget } = await importRoutingTarget()

  assert.deepEqual(resolveModelRoutingTarget("pool:7", 11, accounts), {
    vendorAccountId: 11,
    routingPoolId: 7,
  })
})

test("pool targets use the first enabled member when the existing anchor is outside or disabled", async () => {
  const { resolveModelRoutingTarget } = await importRoutingTarget()

  assert.deepEqual(resolveModelRoutingTarget("pool:7", 31, accounts), {
    vendorAccountId: 11,
    routingPoolId: 7,
  })
  assert.deepEqual(resolveModelRoutingTarget("pool:7", 12, accounts), {
    vendorAccountId: 11,
    routingPoolId: 7,
  })
  assert.equal(resolveModelRoutingTarget("pool:8", 31, accounts), null)
})

test("account targets clear the routing pool", async () => {
  const { resolveModelRoutingTarget } = await importRoutingTarget()

  assert.deepEqual(resolveModelRoutingTarget("account:31", 11, accounts), {
    vendorAccountId: 31,
    routingPoolId: null,
  })
})
