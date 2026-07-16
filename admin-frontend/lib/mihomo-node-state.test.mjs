import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function helpers() {
  const source = await readFile(new URL("./mihomo-node-state.ts", import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}#${Date.now()}`)
}

test("a failed one-off probe does not overwrite provider health", async () => {
  const { mergeMihomoNodeTest } = await helpers()
  const nodes = [{ name: "Tokyo", type: "Vless", available: true, latencyMs: 270, selected: true }]

  const merged = mergeMihomoNodeTest(nodes, {
    nodeName: "Tokyo",
    available: false,
    latencyMs: 0,
    testedAt: "2026-07-16T16:15:35Z",
    message: "Node test failed",
  })

  assert.deepEqual(merged, nodes)
})
