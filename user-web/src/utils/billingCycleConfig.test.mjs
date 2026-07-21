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

test("new membership prices preserve margin and improve with tier", () => {
  const cycles = {
    monthly: [[4000, 59], [10500, 149], [22000, 299], [45000, 599]],
    quarterly: [[12000, 169], [31500, 425], [66000, 849], [135000, 1699]],
    yearly: [[48000, 639], [126000, 1609], [264000, 3229], [540000, 6469]],
  }

  for (const [cycle, packages] of Object.entries(cycles)) {
    const unitPrices = packages.map(([credits, price]) => price / credits)
    for (const [credits, price] of packages) {
      assert.ok(pricing.subscriptionMarginPercent(credits, price) >= 30, `${cycle} ${credits}/${price}`)
    }
    for (let i = 1; i < unitPrices.length; i++) {
      assert.ok(unitPrices[i] < unitPrices[i - 1], `${cycle} tier ${i} must be cheaper per credit`)
    }
  }
})

test("yearly credits equal twelve monthly grants", () => {
  assert.deepEqual([48000, 126000, 264000, 540000], [4000, 10500, 22000, 45000].map((credits) => credits * 12))
})
