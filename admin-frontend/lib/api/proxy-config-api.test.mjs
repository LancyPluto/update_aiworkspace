import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importProxyConfigApi(httpMock) {
  let source = await readFile(new URL("./proxy-config.ts", import.meta.url), "utf8")
  source = source.replace(
    /import \{ http \} from ['"]\.\/http['"]\s*/,
    "const http = globalThis.__proxyConfigHttpMock\n",
  )
  globalThis.__proxyConfigHttpMock = httpMock
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

test("Mihomo runtime API uses dedicated status and apply endpoints", async () => {
  const calls = []
  const api = await importProxyConfigApi({
    get: async (path) => {
      calls.push(["GET", path])
      return { managed: false, available: false }
    },
    post: async (path) => {
      calls.push(["POST", path])
      return { managed: true, available: true }
    },
  })

  await api.fetchMihomoRuntime()
  await api.applyMihomoConfig()

  assert.deepEqual(calls, [
    ["GET", "/api/admin/v1/proxy-config/runtime"],
    ["POST", "/api/admin/v1/proxy-config/apply"],
  ])
})
