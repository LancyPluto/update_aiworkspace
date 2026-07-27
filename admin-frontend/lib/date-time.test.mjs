import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function helpers() {
  const source = await readFile(new URL("./date-time.ts", import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}#${Date.now()}`)
}

test("task timestamps are displayed in Asia/Shanghai instead of UTC", async () => {
  const { formatShanghaiDateTime } = await helpers()

  assert.equal(formatShanghaiDateTime("2026-07-27T10:45:44+08:00"), "2026-07-27 10:45:44")
  assert.equal(formatShanghaiDateTime("2026-07-27T02:45:44Z"), "2026-07-27 10:45:44")
})

test("timezone-less backend timestamps are interpreted as Asia/Shanghai", async () => {
  const { formatShanghaiDateTime } = await helpers()

  assert.equal(formatShanghaiDateTime("2026-07-27T10:45:44"), "2026-07-27 10:45:44")
})

test("empty and invalid timestamps keep stable fallbacks", async () => {
  const { formatShanghaiDateTime } = await helpers()

  assert.equal(formatShanghaiDateTime(null), "-")
  assert.equal(formatShanghaiDateTime("not-a-date"), "not-a-date")
})
