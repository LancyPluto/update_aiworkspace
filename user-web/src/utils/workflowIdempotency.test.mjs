import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

const source = await readFile(new URL("./workflowIdempotency.ts", import.meta.url), "utf8")
const outputText = stripTypeScriptTypes(source, { mode: "transform" })
const idempotency = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  }
}

test("confirmation retries keep one idempotency key until token or action changes", () => {
  const storage = memoryStorage()
  let sequence = 0
  const createId = () => `request-${++sequence}`

  const first = idempotency.workflowFeedbackIdempotencyKey(7, 9, "token-a", "APPROVE", createId, storage)
  const retry = idempotency.workflowFeedbackIdempotencyKey(7, 9, "token-a", "APPROVE", createId, storage)
  const changedAction = idempotency.workflowFeedbackIdempotencyKey(7, 9, "token-a", "REJECT", createId, storage)
  const changedToken = idempotency.workflowFeedbackIdempotencyKey(7, 9, "token-b", "APPROVE", createId, storage)

  assert.equal(first, retry)
  assert.notEqual(first, changedAction)
  assert.notEqual(first, changedToken)
  assert.equal(sequence, 3)
})
