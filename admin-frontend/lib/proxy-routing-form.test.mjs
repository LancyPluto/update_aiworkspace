import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function helpers() {
  const source = await readFile(new URL("./proxy-routing-form.ts", import.meta.url), "utf8")
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

test("routing rules sort exact before suffix and wildcard before fallback", async () => {
  const { sortRoutingRules } = await helpers()
  const rules = sortRoutingRules([
    { id: "suffix", patternType: "SUFFIX", pattern: "example.com", strategy: "PROXY", priority: 900, enabled: true, note: "", probeUrl: "" },
    { id: "exact", patternType: "EXACT", pattern: "api.example.com", strategy: "DIRECT", priority: 1, enabled: true, note: "", probeUrl: "" },
    { id: "wild", patternType: "WILDCARD", pattern: "*.media.example.net", strategy: "AUTO", priority: 500, enabled: true, note: "", probeUrl: "https://health.example.net/ping" },
  ])

  assert.deepEqual(rules.map((rule) => rule.id), ["exact", "wild", "suffix"])
})

test("AUTO rule requires a valid wildcard and independent HTTPS probe", async () => {
  const { validateRoutingRule } = await helpers()
  const errors = validateRoutingRule({
    id: "auto",
    patternType: "WILDCARD",
    pattern: "media.example.com",
    strategy: "AUTO",
    priority: 100,
    enabled: true,
    note: "",
    probeUrl: "http://localhost/health",
  })

  assert.equal(errors.pattern, "通配域名必须使用 *.example.com 格式")
  assert.equal(errors.probeUrl, "AUTO 规则必须配置公网 HTTPS 探针地址")
})

test("AUTO probe host must match rule semantics and wildcard uses a real subdomain", async () => {
  const { diagnosticDomainForRule, validateRoutingRule } = await helpers()
  const rootProbe = validateRoutingRule({
    id: "auto-media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "AUTO", priority: 100, enabled: true, note: "",
    probeUrl: "https://media.example.com/health",
  })
  const childProbe = validateRoutingRule({
    id: "auto-media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "AUTO", priority: 100, enabled: true, note: "",
    probeUrl: "https://probe.media.example.com/health",
  })

  assert.equal(rootProbe.probeUrl, "探针域名必须命中当前规则")
  assert.equal(childProbe.probeUrl, undefined)
  assert.equal(diagnosticDomainForRule({
    id: "auto-media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "AUTO", priority: 100, enabled: true, note: "",
    probeUrl: "https://media.example.com/health",
  }), "")
  assert.equal(diagnosticDomainForRule({
    id: "auto-media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "AUTO", priority: 100, enabled: true, note: "",
    probeUrl: "not-a-url",
  }), "")
  assert.equal(diagnosticDomainForRule({
    id: "auto-media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "AUTO", priority: 100, enabled: true, note: "",
    probeUrl: "https://probe.media.example.com/health",
  }), "probe.media.example.com")
})

test("non-AUTO wildcard diagnosis requires an explicit real matching hostname", async () => {
  const { diagnosticDomainForRule, wildcardTestDomainError } = await helpers()
  const rule = {
    id: "media", patternType: "WILDCARD", pattern: "*.media.example.com",
    strategy: "PROXY", priority: 100, enabled: true, note: "", probeUrl: "",
  }

  assert.equal(diagnosticDomainForRule(rule, ""), "")
  assert.equal(diagnosticDomainForRule(rule, "media.example.com"), "")
  assert.equal(diagnosticDomainForRule(rule, "cdn.media.example.com"), "cdn.media.example.com")
  assert.equal(wildcardTestDomainError(rule, ""), "请输入一个命中 *.media.example.com 的真实子域")
  assert.equal(wildcardTestDomainError(rule, "media.example.com"), "测试域名必须命中 *.media.example.com")
  assert.equal(wildcardTestDomainError(rule, "cdn.media.example.com"), "")
})

test("routing config dirty state ignores presentation order but detects edits", async () => {
  const { isRoutingConfigDirty, routingConfigFingerprint } = await helpers()
  const settings = { timeoutMs: 5000, sampleSize: 6, switchThresholdMs: 150, hysteresisMs: 80, cooldownSeconds: 300 }
  const exact = { id: "exact", patternType: "EXACT", pattern: "api.example.com", strategy: "PROXY", priority: 100, enabled: true, note: "", probeUrl: "" }
  const suffix = { id: "suffix", patternType: "SUFFIX", pattern: "example.net", strategy: "DIRECT", priority: 50, enabled: true, note: "", probeUrl: "" }
  const saved = routingConfigFingerprint([suffix, exact], settings)

  assert.equal(isRoutingConfigDirty([exact, suffix], settings, saved), false)
  assert.equal(isRoutingConfigDirty([{ ...exact, strategy: "DIRECT" }, suffix], settings, saved), true)
  assert.equal(isRoutingConfigDirty([exact, suffix], { ...settings, timeoutMs: 7000 }, saved), true)
})
