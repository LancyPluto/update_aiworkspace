import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

const source = await readFile(new URL("./displayName.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const displayName = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`
)

test("legacy internal-id display names fall back to the public code", () => {
  assert.equal(displayName.safeDisplayName("用户3", "54321"), "")
  assert.equal(displayName.defaultUserDisplayName("54321"), "用户54321")
})

test("valid public fallback names and custom nicknames remain visible", () => {
  assert.equal(displayName.safeDisplayName("用户54321", "54321"), "用户54321")
  assert.equal(displayName.safeDisplayName("咕咕嘎嘎", "54321"), "咕咕嘎嘎")
  assert.equal(displayName.safeDisplayName("13800138000", "54321"), "")
})
