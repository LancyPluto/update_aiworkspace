import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
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

test("formats imported and updated discovery counts for toast descriptions", async () => {
  const { formatModelDiscoveryResult } = await importTsModule("./model-vendor-discovery.ts")

  assert.equal(
    formatModelDiscoveryResult({ importedCount: 3, updatedCount: 2 }),
    "导入 3 个，更新 2 个",
  )
})

test("formats discovered totals when backend omits imported and updated counts", async () => {
  const { formatModelDiscoveryResult } = await importTsModule("./model-vendor-discovery.ts")

  assert.equal(
    formatModelDiscoveryResult({ discoveredCount: 8, message: "同步完成" }),
    "发现 8 个模型；同步完成",
  )
})

test("uses backend message as a fallback when no counts are present", async () => {
  const { formatModelDiscoveryResult } = await importTsModule("./model-vendor-discovery.ts")

  assert.equal(formatModelDiscoveryResult({ message: "没有可导入模型" }), "没有可导入模型")
})
