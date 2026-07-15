import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("model editor omits the obsolete account address inheritance card", () => {
  assert.doesNotMatch(source, /继承账号地址/)
  assert.doesNotMatch(source, /模型覆盖地址/)
  assert.doesNotMatch(source, /改回继承账号地址/)
  assert.doesNotMatch(source, /当前模型保存了独立 baseUrl/)
})

test("model editor removes helpers used only by the obsolete card", () => {
  assert.doesNotMatch(source, /function normalizeOptionalUrl/)
  assert.doesNotMatch(source, /function sameBaseUrl/)
})
