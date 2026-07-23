import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importCacheModule() {
  const source = await readFile(new URL("./toolCatalogCache.ts", import.meta.url), "utf8")
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

const cache = await importCacheModule()

test("catalog cache normalizes queries, caps page size, ignores token, deduplicates, and expires after 180 seconds", async () => {
  let now = 0
  const calls = []
  const resolvers = []
  const fetchCatalog = cache.createToolCatalogFetcher(
    (options) => {
      calls.push(options)
      return new Promise((resolve) => resolvers.push(resolve))
    },
    { now: () => now },
  )

  const first = fetchCatalog({
    token: "token-a",
    query: { keyword: "  cats  ", categoryId: 7, pageNo: 0, pageSize: 120, view: "summary" },
  })
  const concurrent = fetchCatalog({
    token: "token-b",
    query: { categoryId: 7, view: "summary", pageSize: 100, pageNo: 1, keyword: "cats" },
  })

  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0], {
    token: "token-a",
    query: { view: "summary", pageNo: 1, pageSize: 100, categoryId: 7, keyword: "cats" },
  })

  const firstResult = { list: [{ toolCode: "cat" }] }
  resolvers.shift()(firstResult)
  assert.strictEqual(await first, firstResult)
  assert.strictEqual(await concurrent, firstResult)

  now = 179_999
  assert.strictEqual(await fetchCatalog({ token: "token-c", query: calls[0].query }), firstResult)
  assert.equal(calls.length, 1)

  now = 180_000
  const expired = fetchCatalog({ token: null, query: calls[0].query })
  assert.equal(calls.length, 2)
  const refreshedResult = { list: [{ toolCode: "cat-v2" }] }
  resolvers.shift()(refreshedResult)
  assert.strictEqual(await expired, refreshedResult)
})

test("catalog cache separates views and query filters, and never caches failures", async () => {
  const calls = []
  let shouldFail = true
  const fetchCatalog = cache.createToolCatalogFetcher(async ({ query }) => {
    calls.push(query)
    if (shouldFail) {
      shouldFail = false
      throw new Error("temporary failure")
    }
    return query
  })

  const compactCats = { view: "compact", keyword: "cats", categoryId: 1, pageNo: 1, pageSize: 20 }
  await assert.rejects(fetchCatalog({ query: compactCats }), /temporary failure/)
  await fetchCatalog({ query: compactCats })
  await fetchCatalog({ query: { ...compactCats, keyword: "dogs" } })
  await fetchCatalog({ query: { ...compactCats, categoryId: 2 } })
  await fetchCatalog({ query: { ...compactCats, view: "summary" } })

  assert.equal(calls.length, 5)
  assert.deepEqual(calls.map((query) => [query.view, query.keyword, query.categoryId]), [
    ["compact", "cats", 1],
    ["compact", "cats", 1],
    ["compact", "dogs", 1],
    ["compact", "cats", 2],
    ["summary", "cats", 1],
  ])
})
