import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function loadModule() {
  const source = await readFile(new URL("./model-provider-selection.ts", import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}#${Date.now()}`)
}

const providers = [
  { code: "qwen", capabilities: ["TEXT_GENERATION", "VISION_INPUT"] },
  { code: "bailian_happyhorse", capabilities: ["VIDEO_GENERATION"] },
]

test("model provider takes precedence over the vendor group provider", async () => {
  const { selectDefaultModelProvider } = await loadModule()

  const selected = selectDefaultModelProvider(providers, "qwen", "bailian_happyhorse")

  assert.equal(selected.code, "bailian_happyhorse")
})

test("new model provider is selected only from protocols supported by the vendor", async () => {
  const { selectDefaultModelProvider, supportedModelProviders } = await loadModule()
  const allProviders = [
    ...providers,
    { code: "volcengine_images", capabilities: ["IMAGE_GENERATION"] },
    { code: "seedance", capabilities: ["VIDEO_GENERATION"] },
  ]
  const eligibleProviders = supportedModelProviders(allProviders, ["volcengine_images", "seedance"])

  const initial = selectDefaultModelProvider(eligibleProviders, "doubao")
  const preferred = selectDefaultModelProvider(eligibleProviders, "doubao", "seedance")

  assert.deepEqual(eligibleProviders.map((provider) => provider.code), ["volcengine_images", "seedance"])
  assert.equal(initial.code, "volcengine_images")
  assert.equal(preferred.code, "seedance")
})

test("HappyHorse capabilities are normalized only against the HappyHorse provider", async () => {
  const { capabilitiesForModelProvider, findModelProvider } = await loadModule()
  const provider = findModelProvider(providers, "bailian_happyhorse")

  assert.deepEqual(capabilitiesForModelProvider(["VIDEO_GENERATION"], provider), ["VIDEO_GENERATION"])
  assert.deepEqual(capabilitiesForModelProvider(["TEXT_GENERATION"], provider), ["VIDEO_GENERATION"])
})

test("provider normalization does not preserve vision when the provider does not declare it", async () => {
  const { capabilitiesForModelProvider, findModelProvider } = await loadModule()
  const provider = findModelProvider(providers, "bailian_happyhorse")

  assert.deepEqual(capabilitiesForModelProvider(["VIDEO_GENERATION", "VISION_INPUT"], provider), ["VIDEO_GENERATION"])
})

test("switching provider resets protocol defaults while replacing an untouched default model name", async () => {
  const { modelFormAfterProviderSwitch } = await loadModule()
  const current = {
    displayName: "Video model",
    provider: "volcengine_images",
    modelName: "doubao-seedream-4-0",
    baseUrl: "https://old.example/v1",
    capabilities: ["IMAGE_GENERATION"],
    executionTask: "image_generation",
    executionOptionsJson: "{\"legacy\":true}",
    endpointPath: "/legacy",
    billingUnit: "IMAGE_TOKEN",
  }
  const previous = {
    code: "volcengine_images",
    capabilities: ["IMAGE_GENERATION"],
    defaultModel: "doubao-seedream-4-0",
    defaultBaseUrl: "https://ark.cn-beijing.volces.com/api/v3",
    billingDefault: "PER_CALL",
  }
  const next = {
    code: "seedance",
    capabilities: ["VIDEO_GENERATION"],
    defaultModel: "doubao-seedance-1-5-pro",
    defaultBaseUrl: "https://ark.cn-beijing.volces.com/api/v3",
    billingDefault: "PER_SECOND",
  }

  const switched = modelFormAfterProviderSwitch(
    current,
    previous,
    next,
    ["VIDEO_GENERATION"],
    "video_generation",
  )

  assert.deepEqual(switched, {
    ...current,
    provider: "seedance",
    modelName: "doubao-seedance-1-5-pro",
    baseUrl: "https://ark.cn-beijing.volces.com/api/v3",
    capabilities: ["VIDEO_GENERATION"],
    executionTask: "video_generation",
    executionOptionsJson: "",
    endpointPath: null,
    billingUnit: "PER_SECOND",
  })
})

test("switching provider preserves an explicitly entered model name", async () => {
  const { modelFormAfterProviderSwitch } = await loadModule()
  const current = {
    provider: "volcengine_images",
    modelName: "my-deployed-endpoint",
    baseUrl: "https://old.example/v1",
    capabilities: ["IMAGE_GENERATION"],
    executionTask: "image_generation",
    billingUnit: "PER_CALL",
  }
  const previous = {
    code: "volcengine_images",
    capabilities: ["IMAGE_GENERATION"],
    defaultModel: "doubao-seedream-4-0",
    defaultBaseUrl: "https://old.example/v1",
    billingDefault: "PER_CALL",
  }
  const next = {
    code: "seedance",
    capabilities: ["VIDEO_GENERATION"],
    defaultModel: "doubao-seedance-1-5-pro",
    defaultBaseUrl: "https://new.example/v1",
    billingDefault: "PER_SECOND",
  }

  const switched = modelFormAfterProviderSwitch(
    current,
    previous,
    next,
    ["VIDEO_GENERATION"],
    "video_generation",
  )

  assert.equal(switched.modelName, "my-deployed-endpoint")
  assert.equal(switched.provider, "seedance")
  assert.equal(switched.baseUrl, "https://new.example/v1")
  assert.deepEqual(switched.capabilities, ["VIDEO_GENERATION"])
  assert.equal(switched.executionTask, "video_generation")
  assert.equal(switched.billingUnit, "PER_SECOND")
})
