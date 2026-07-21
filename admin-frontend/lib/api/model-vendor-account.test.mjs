import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importAccountApi(httpMock) {
  let source = await readFile(new URL("./model-vendor-account.ts", import.meta.url), "utf8")
  source = source.replace(
    /import \{ http \} from ['"]\.\/http['"]\s*/,
    "const http = globalThis.__modelVendorAccountHttpMock\n",
  )
  globalThis.__modelVendorAccountHttpMock = httpMock

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

test("discoverModelVendorAccountModels posts to the account discovery endpoint", async () => {
  let captured
  const api = await importAccountApi({
    post: async (path, body) => {
      captured = { path, body }
      return { importedCount: 3, updatedCount: 2 }
    },
  })

  assert.equal(typeof api.discoverModelVendorAccountModels, "function")
  const result = await api.discoverModelVendorAccountModels(42)

  assert.deepEqual(captured, {
    path: "/api/admin/v1/model-vendor-accounts/42/discover-models",
    body: undefined,
  })
  assert.deepEqual(result, { importedCount: 3, updatedCount: 2 })
})

test("updateModelVendorAccountRouting patches only routing settings", async () => {
  let captured
  const updatedAccount = {
    id: 25,
    vendorCode: "agnes",
    loadBalanceEnabled: true,
    loadBalanceWeight: 60,
    routingPoolName: "视频主池",
  }
  const api = await importAccountApi({
    patch: async (path, body) => {
      captured = { path, body }
      return updatedAccount
    },
  })

  const result = await api.updateModelVendorAccountRouting(25, {
    loadBalanceEnabled: true,
    loadBalanceWeight: 60,
    routingPoolName: "视频主池",
  })

  assert.deepEqual(captured, {
    path: "/api/admin/v1/model-vendor-accounts/25/routing",
    body: { loadBalanceEnabled: true, loadBalanceWeight: 60, routingPoolName: "视频主池" },
  })
  assert.equal(result, updatedAccount)
})

test("WARNING connectivity result keeps the account credential valid", async () => {
  const api = await importAccountApi({})
  const account = {
    id: 25,
    vendorCode: "agnes",
    healthStatus: "WARNING",
    balanceErrorMessage: "quota is low",
  }

  const result = api.normalizeVendorAccountTestResult(account)

  assert.equal(result.success, true)
  assert.equal(result.account, account)
})
