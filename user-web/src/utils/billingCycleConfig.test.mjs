import assert from "node:assert/strict"
import test from "node:test"
const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

async function importTsModule(path) {
  const source = await (await import("node:fs/promises")).readFile(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const pricing = await importTsModule("./billingCycleConfig.ts")

test("subscription margin stays positive at markup 1.50", () => {
  const monthly = [
    [4000, 59],
    [10000, 149],
    [20000, 299],
    [40000, 599],
  ]
  for (const [credits, price] of monthly) {
    assert.ok(pricing.subscriptionMarginPercent(credits, price) >= 45, `monthly ${credits}/${price}`)
  }

  const quarterly = [
    [12000, 159],
    [30000, 399],
    [60000, 799],
    [120000, 1599],
  ]
  for (const [credits, price] of quarterly) {
    assert.ok(pricing.subscriptionMarginPercent(credits, price) >= 45, `quarterly ${credits}/${price}`)
  }

  const yearly = [
    [52000, 446],
    [130000, 1128],
    [260000, 2264],
    [520000, 4528],
  ]
  for (const [credits, price] of yearly) {
    assert.ok(pricing.subscriptionMarginPercent(credits, price) >= 20, `yearly ${credits}/${price}`)
  }
})
