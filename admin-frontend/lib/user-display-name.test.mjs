import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

const source = await readFile(new URL("./user-display-name.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { adminUserDisplayName } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`
)

test("legacy generated names fall back to the current public code", () => {
  assert.equal(adminUserDisplayName({
    id: 29,
    publicCode: "49443",
    nickname: "用户018735835",
    username: "用户018735835",
  }), "用户49443")
})

test("canonical fallback names and custom nicknames remain visible", () => {
  assert.equal(adminUserDisplayName({
    id: 29,
    publicCode: "49443",
    nickname: "用户49443",
    username: "legacy-login",
  }), "用户49443")
  assert.equal(adminUserDisplayName({
    id: 29,
    publicCode: "49443",
    nickname: "咕咕嘎嘎",
    username: "legacy-login",
  }), "咕咕嘎嘎")
})
