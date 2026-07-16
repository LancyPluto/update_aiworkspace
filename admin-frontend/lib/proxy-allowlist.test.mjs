import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function helpers() {
  const source = await readFile(new URL("./proxy-allowlist.ts", import.meta.url), "utf8")
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

test("legacy PROXY and AUTO rules become a compact proxy website list", async () => {
  const { proxyDomainsFromRules } = await helpers()
  const domains = proxyDomainsFromRules([
    { id: "one", patternType: "EXACT", pattern: "api.ofox.ai", strategy: "PROXY", priority: 10, enabled: true, note: "", probeUrl: "" },
    { id: "two", patternType: "SUFFIX", pattern: "ofox.ai", strategy: "AUTO", priority: 20, enabled: true, note: "", probeUrl: "https://ofox.ai" },
    { id: "three", patternType: "EXACT", pattern: "direct.example.com", strategy: "DIRECT", priority: 30, enabled: true, note: "", probeUrl: "" },
  ])

  assert.deepEqual(domains, ["ofox.ai"])
})

test("website input accepts URLs and root domains cover their subdomains", async () => {
  const { addProxyDomain, normalizeProxyDomain } = await helpers()

  assert.equal(normalizeProxyDomain("https://API.OFOX.AI/v1/images"), "api.ofox.ai")
  assert.deepEqual(addProxyDomain(["api.ofox.ai"], "ofox.ai"), ["ofox.ai"])
  assert.deepEqual(addProxyDomain(["ofox.ai"], "api.ofox.ai"), ["ofox.ai"])
})

test("website list renders deterministic suffix proxy rules", async () => {
  const { proxyDomainsToRules } = await helpers()
  const rules = proxyDomainsToRules(["ofox.ai", "example.com"])

  assert.deepEqual(rules.map((rule) => [rule.patternType, rule.pattern, rule.strategy]), [
    ["SUFFIX", "example.com", "PROXY"],
    ["SUFFIX", "ofox.ai", "PROXY"],
  ])
  assert.ok(rules.every((rule) => /^site-[a-z0-9]+$/.test(rule.id)))
})
