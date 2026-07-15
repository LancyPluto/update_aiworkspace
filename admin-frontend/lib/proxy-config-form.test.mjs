import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importFormHelpers() {
  const source = await readFile(new URL("./proxy-config-form.ts", import.meta.url), "utf8")
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

test("proxyConfigToFormState never places stored secrets into editable inputs", async () => {
  const { proxyConfigToFormState } = await importFormHelpers()
  const form = proxyConfigToFormState({
    enabled: true,
    sourceType: "SUBSCRIPTION",
    displayName: "海外主线路",
    subscriptionConfigured: true,
    subscriptionUrlMasked: "https://airport.example.com/***?token=***",
    subscriptionUpdateIntervalMinutes: 360,
    mihomoEndpoint: "http://host.docker.internal:7890",
    manualProtocol: "SOCKS5",
    manualHost: "8.8.8.8",
    manualPort: 1080,
    manualUsernameMasked: "pr***er",
    manualPasswordConfigured: true,
    noProxyHosts: "localhost,backend",
    proxyUrlMasked: "socks5://pr***er:***@8.8.8.8:1080",
  })

  assert.equal(form.subscriptionUrl, "")
  assert.equal(form.manualUsername, "")
  assert.equal(form.manualPassword, "")
  assert.equal(form.subscriptionConfigured, true)
  assert.equal(form.subscriptionUrlMasked, "https://airport.example.com/***?token=***")
  assert.equal(form.manualPasswordConfigured, true)
  assert.equal(form.manualPort, "1080")
})

test("validateProxyForm accepts an already configured subscription without re-entering its URL", async () => {
  const { proxyConfigToFormState, validateProxyForm } = await importFormHelpers()
  const form = proxyConfigToFormState({
    enabled: true,
    sourceType: "SUBSCRIPTION",
    displayName: "主线路",
    subscriptionConfigured: true,
    subscriptionUrlMasked: "https://airport.example.com/***",
    subscriptionUpdateIntervalMinutes: 360,
    mihomoEndpoint: "http://host.docker.internal:7890",
    manualProtocol: "HTTP",
    manualHost: "",
    manualPort: 7890,
    manualUsernameMasked: "",
    manualPasswordConfigured: false,
    noProxyHosts: "localhost,backend",
    proxyUrlMasked: "http://host.docker.internal:7890",
  })

  assert.deepEqual(validateProxyForm(form), {})
})

test("validateProxyForm requires a new subscription URL and rejects a private manual IP", async () => {
  const { defaultProxyFormState, validateProxyForm } = await importFormHelpers()

  const subscriptionErrors = validateProxyForm({
    ...defaultProxyFormState,
    sourceType: "SUBSCRIPTION",
    subscriptionConfigured: false,
    subscriptionUrl: "",
  })
  assert.equal(subscriptionErrors.subscriptionUrl, "请输入机场订阅链接")

  const manualErrors = validateProxyForm({
    ...defaultProxyFormState,
    sourceType: "MANUAL",
    manualHost: "192.168.1.10",
    manualPort: "70000",
  })
  assert.equal(manualErrors.manualHost, "请输入可路由的公网 IPv4 地址")
  assert.equal(manualErrors.manualPort, "端口需在 1 到 65535 之间")
})
