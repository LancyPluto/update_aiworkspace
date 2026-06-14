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
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const labels = await importTsModule("./taskStatusLabels.ts")

const ADMIN_CANCEL_REASON = "\u7ba1\u7406\u5458\u5df2\u53d6\u6d88\u4efb\u52a1"
const USER_CANCEL_HINT = "\u4efb\u52a1\u5df2\u53d6\u6d88\u3002"
const USER_CANCEL_LABEL = "\u4efb\u52a1\u5df2\u53d6\u6d88"
const FAILED_HINT = "\u751f\u6210\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u53c2\u6570\u540e\u91cd\u8bd5\u3002"

test("cancelled tasks show a neutral user-facing hint instead of backend operator text", () => {
  assert.equal(labels.taskFailureHint("CANCELLED", [ADMIN_CANCEL_REASON]), USER_CANCEL_HINT)
  assert.doesNotMatch(labels.taskFailureHint("CANCELLED", [ADMIN_CANCEL_REASON]), /\u7ba1\u7406\u5458/)
})

test("failed tasks still prefer a concrete backend progress message", () => {
  assert.equal(labels.taskFailureHint("FAILED", ["provider rejected prompt"]), "provider rejected prompt")
})

test("failed tasks fall back to the documented failure hint when no progress message exists", () => {
  assert.equal(labels.taskFailureHint("FAILED", ["   ", null, undefined]), FAILED_HINT)
})

test("cancelled tasks use a distinct status view kind instead of failed", () => {
  assert.equal(labels.taskStatusViewKind("CANCELLED"), "cancelled")
  assert.equal(labels.taskStatusViewKind("FAILED"), "failed")
  assert.equal(labels.taskStatusViewKind("TIMEOUT"), "failed")
})

test("failed status helper excludes cancelled tasks", () => {
  assert.equal(labels.isFailedTaskStatus("FAILED"), true)
  assert.equal(labels.isFailedTaskStatus("TIMEOUT"), true)
  assert.equal(labels.isFailedTaskStatus("CANCELLED"), false)
})

test("cancelled progress messages are sanitized before display", () => {
  assert.equal(labels.taskProgressMessage("CANCELLED", ADMIN_CANCEL_REASON), USER_CANCEL_LABEL)
})

test("running progress messages still prefer backend progress text", () => {
  assert.equal(labels.taskProgressMessage("PROCESSING", "AI is generating"), "AI is generating")
})

test("processing status ignores stale queued progress messages", () => {
  assert.equal(labels.taskProgressMessage("PROCESSING", "\u4efb\u52a1\u5df2\u6392\u961f"), labels.TASK_STATUS_DOC_LABELS.PROCESSING)
})
