import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const outputText = stripTypeScriptTypes(source, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const polling = await importTsModule("./workflowPolling.ts")

test("terminal runs stop every refresh channel", () => {
  for (const status of ["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"]) {
    assert.deepEqual(polling.nextRefreshPlan({ status, streamOutcome: "error", failures: 8 }), {
      stop: true,
      pollAfterMs: null,
      reconnectAfterMs: null,
    })
  }
})

test("SSE EOF schedules both a reconnect and fallback polling", () => {
  assert.deepEqual(polling.nextRefreshPlan({ status: "RUNNING", streamOutcome: "eof", failures: 0 }), {
    stop: false,
    pollAfterMs: 4000,
    reconnectAfterMs: 3000,
  })
})

test("poll failures use bounded exponential backoff and stale responses lose", () => {
  assert.equal(polling.pollDelayMs(0), 4000)
  assert.equal(polling.pollDelayMs(1), 6000)
  assert.equal(polling.pollDelayMs(8), 30000)
  assert.equal(polling.shouldApplyWorkflowResponse(7, 7), true)
  assert.equal(polling.shouldApplyWorkflowResponse(7, 6), false)
})
