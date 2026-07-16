import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function loadApi() {
  const source = await readFile(new URL("./workflowApi.ts", import.meta.url), "utf8")
  const injectable = source.replace(
    /import\s+\{\s*apiRequest\s*\}\s+from\s+["']\.\/client["']\s*/,
    "const apiRequest = undefined\n",
  )
  const outputText = stripTypeScriptTypes(injectable, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

test("workflow API sends exact methods, paths, queries, and bodies", async () => {
  const calls = []
  const api = (await loadApi()).createWorkflowApi(async (...args) => {
    calls.push(args)
    return { ok: true }
  })

  await api.listTools({ query: { page: 2, pageSize: 12, keyword: "video" }, token: "token" })
  await api.getTool("comic/movie", { signal: AbortSignal.abort() })
  await api.createRun("comic/movie", { input: { prompt: "hello" }, clientRequestId: "request-1" })
  await api.getRun("task/9")
  await api.cancelRun("task/9")
  await api.resumeRun("task/9")
  await api.submitFeedback("task/9", {
    stepId: "step-2",
    action: "CONTINUE_WITH_FEEDBACK",
    fields: { note: "keep title" },
    confirmationToken: "confirmation-token",
    idempotencyKey: "feedback-1",
  })

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["GET", "/api/v1/agents/tools"],
    ["GET", "/api/v1/agents/tools/comic%2Fmovie"],
    ["POST", "/api/v1/agents/tools/comic%2Fmovie/runs"],
    ["GET", "/api/v1/agents/runs/task%2F9"],
    ["POST", "/api/v1/agents/runs/task%2F9/cancel"],
    ["POST", "/api/v1/agents/runs/task%2F9/resume"],
    ["POST", "/api/v1/agents/runs/task%2F9/feedback"],
  ])
  assert.deepEqual(calls[0][2], { query: { page: 2, pageSize: 12, keyword: "video" }, token: "token" })
  assert.equal(calls[1][2].signal.aborted, true)
  assert.deepEqual(calls[2][2].body, { input: { prompt: "hello" }, clientRequestId: "request-1" })
  assert.deepEqual(calls[6][2].body, {
    stepId: "step-2",
    action: "CONTINUE_WITH_FEEDBACK",
    fields: { note: "keep title" },
    confirmationToken: "confirmation-token",
    idempotencyKey: "feedback-1",
  })
})
