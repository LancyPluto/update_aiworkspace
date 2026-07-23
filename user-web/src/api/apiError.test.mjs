import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

async function loadTypeScript() {
  const module = await import("typescript")
  return module.default ?? module
}

async function importTsModule(path) {
  const ts = await loadTypeScript()
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

test("user errors prefer userMessage and discard admin-only diagnostics", async () => {
  const { toUserApiError } = await importTsModule("./apiError.ts")

  const error = toUserApiError(
    {
      errorCode: "MODEL_004",
      userMessage: "模型响应超时，请稍后重试",
      developerMessage: "provider=minimax token=must-not-be-displayed",
      traceId: "trace-new",
    },
    504,
  )

  assert.equal(error.message, "模型响应超时，请稍后重试")
  assert.doesNotMatch(error.message, /provider|minimax|token/)
  assert.equal(error.errorCode, "MODEL_004")
  assert.equal(error.code, "MODEL_004")
  assert.equal(error.httpStatus, 504)
  assert.equal(error.status, 504)
  assert.equal(error.userMessage, "模型响应超时，请稍后重试")
  assert.equal("developerMessage" in error, false)
  assert.equal(error.traceId, "trace-new")
})

test("user errors map legacy codes without trusting legacy message text", async () => {
  const { toUserApiError } = await importTsModule("./apiError.ts")

  const error = toUserApiError(
    {
      code: "TASK_STATUS_INVALID",
      message: "select password from users; upstream payload=must-not-be-displayed",
      requestId: "legacy-request",
    },
    409,
  )

  assert.equal(error.message, "当前任务状态不允许操作")
  assert.doesNotMatch(error.message, /select|password|payload/)
  assert.equal(error.code, "TASK_STATUS_INVALID")
  assert.equal(error.traceId, "legacy-request")
})

test("user errors discard unknown legacy messages and use a controlled fallback", async () => {
  const { toUserApiError } = await importTsModule("./apiError.ts")

  const error = toUserApiError(
    { code: "VENDOR_RAW_FAILURE", message: "Authorization=Bearer secret-provider-token" },
    502,
    "请求失败 (502)",
  )

  assert.equal(error.message, "请求失败 (502)")
  assert.equal(error.userMessage, undefined)
  assert.doesNotMatch(error.message, /Authorization|secret-provider-token/)
})

test("user errors never use developerMessage as the display fallback", async () => {
  const { toUserApiError } = await importTsModule("./apiError.ts")

  const error = toUserApiError(
    { errorCode: "SYSTEM_001", developerMessage: "select * from private_table" },
    500,
    "请求失败 (500)",
  )

  assert.equal(error.message, "请求失败，请稍后重试")
  assert.doesNotMatch(error.message, /select|private_table/)
})
