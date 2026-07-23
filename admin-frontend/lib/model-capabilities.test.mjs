import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
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

test("fallback provider capabilities include supported workflow providers", async () => {
  const { resolvedModelCapabilities } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_chat" }), ["TEXT_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_images" }), ["IMAGE_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_video" }), ["VIDEO_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "infinitetalk" }), ["VIDEO_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "dashscope_qwen_tts" }), ["TEXT_TO_SPEECH"])
})

test("model capabilities are normalized, deduplicated, and hide the legacy digital-human marker", async () => {
  const { normalizeModelCapabilities } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(
    normalizeModelCapabilities([" text_generation ", "TEXT_GENERATION", "digital_human", "vision_input"]),
    ["TEXT_GENERATION", "VISION_INPUT"],
  )
})

test("new tool requirements normalize casing without accepting the legacy digital-human marker", async () => {
  const { normalizeRequiredModelCapabilities } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(
    normalizeRequiredModelCapabilities([" image_generation ", "DIGITAL_HUMAN", "video_generation"]),
    ["IMAGE_GENERATION", "DIGITAL_HUMAN", "VIDEO_GENERATION"],
  )
})

test("legacy tool requirements map digital-human to video without dropping other requirements", async () => {
  const { normalizeLegacyRequiredModelCapabilities } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(
    normalizeLegacyRequiredModelCapabilities([" image_generation ", "DIGITAL_HUMAN", "video_generation"]),
    ["IMAGE_GENERATION", "VIDEO_GENERATION"],
  )
})

test("model matching requires every selected tool capability", async () => {
  const { modelConfigSupportsCapabilities } = await importTsModule("./model-capabilities.ts")
  const model = {
    provider: "qwen",
    capabilities: ["TEXT_GENERATION", "VISION_INPUT"],
  }

  assert.equal(modelConfigSupportsCapabilities(model, ["TEXT_GENERATION", "VISION_INPUT"]), true)
  assert.equal(modelConfigSupportsCapabilities(model, ["TEXT_GENERATION", "VIDEO_GENERATION"]), false)
  assert.equal(modelConfigSupportsCapabilities(model, []), false)
})

test("stored model capabilities are constrained by final provider metadata", async () => {
  const { modelConfigSupportsCapabilities, resolvedModelCapabilities } = await importTsModule("./model-capabilities.ts")
  const mislabeledSeedream = {
    provider: "volcengine_images",
    capabilities: ["VIDEO_GENERATION", "IMAGE_GENERATION"],
  }
  const providerCapabilities = { volcengine_images: ["IMAGE_GENERATION"] }

  assert.deepEqual(resolvedModelCapabilities(mislabeledSeedream, providerCapabilities), ["IMAGE_GENERATION"])
  assert.equal(
    modelConfigSupportsCapabilities(mislabeledSeedream, ["VIDEO_GENERATION"], providerCapabilities),
    false,
  )
})

test("digital-human execution accepts only implemented video providers", async () => {
  const { modelConfigSupportsToolRequirements } = await importTsModule("./model-capabilities.ts")
  const videoModel = (provider) => ({ provider, capabilities: ["VIDEO_GENERATION"] })

  assert.equal(
    modelConfigSupportsToolRequirements(videoModel("seedance"), ["VIDEO_GENERATION"], undefined, "DIGITAL_HUMAN"),
    true,
  )
  assert.equal(
    modelConfigSupportsToolRequirements(videoModel("infinitetalk"), ["VIDEO_GENERATION"], undefined, "digital_human"),
    true,
  )
  assert.equal(
    modelConfigSupportsToolRequirements(videoModel("agnes_video"), ["VIDEO_GENERATION"], undefined, "DIGITAL_HUMAN"),
    false,
  )
  assert.equal(
    modelConfigSupportsToolRequirements(videoModel("agnes_video"), ["VIDEO_GENERATION"], undefined, "VIDEO_GENERATION"),
    true,
  )
})

test("tool defaults use fixed known mappings independent of configured provider capabilities", async () => {
  const { defaultRequiredModelCapabilitiesForTool } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ executionHandler: "DIGITAL_HUMAN", toolType: "AGENT" }),
    ["VIDEO_GENERATION"],
  )
  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ toolType: "IMAGE_TO_IMAGE" }),
    ["IMAGE_GENERATION"],
  )
  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ toolType: "IMAGE_UNDERSTANDING" }),
    ["TEXT_GENERATION", "VISION_INPUT"],
  )
  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ toolType: "SPEECH_TO_TEXT" }),
    ["SPEECH_TO_TEXT"],
  )
  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ toolType: "AGENT" }),
    ["TEXT_GENERATION"],
  )
  assert.deepEqual(
    defaultRequiredModelCapabilitiesForTool({ executionHandler: "NOT_A_MODEL_CAPABILITY" }),
    ["TEXT_GENERATION"],
  )
})

test("tool types without a dedicated worker handler fall back to text execution", async () => {
  const { defaultExecutionHandlerForToolType } = await importTsModule("./model-capabilities.ts")

  assert.equal(defaultExecutionHandlerForToolType("VIDEO_GENERATION"), "VIDEO_GENERATION")
  assert.equal(defaultExecutionHandlerForToolType(" text_to_speech "), "TEXT_TO_SPEECH")
  assert.equal(defaultExecutionHandlerForToolType("IMAGE_TO_IMAGE"), "TEXT_GENERATION")
  assert.equal(defaultExecutionHandlerForToolType("SPEECH_TO_TEXT"), "TEXT_GENERATION")
  assert.equal(defaultExecutionHandlerForToolType(undefined), "TEXT_GENERATION")
})

test("workflow model options keep only enabled compatible models and preserve an invalid current binding", async () => {
  const { workflowModelOptions } = await importTsModule("./model-capabilities.ts")
  const models = [
    { id: 1, enabled: true, provider: "dashscope_qwen_tts", capabilities: ["TEXT_TO_SPEECH"] },
    { id: 2, enabled: true, provider: "agnes_chat", capabilities: ["TEXT_GENERATION"] },
    { id: 3, enabled: false, provider: "siliconflow_speech", capabilities: ["TEXT_TO_SPEECH"] },
  ]

  assert.deepEqual(
    workflowModelOptions(models, "TEXT_TO_SPEECH", null).map((model) => model.id),
    [1],
  )
  assert.deepEqual(
    workflowModelOptions(models, "TEXT_TO_SPEECH", 2).map((model) => model.id),
    [2, 1],
  )
})

test("workflow model issue distinguishes missing, disabled, and incompatible bindings", async () => {
  const { workflowModelIssue } = await importTsModule("./model-capabilities.ts")

  assert.equal(workflowModelIssue(null, "TEXT_TO_SPEECH"), "MISSING")
  assert.equal(
    workflowModelIssue({ id: 1, enabled: false, capabilities: ["TEXT_TO_SPEECH"] }, "TEXT_TO_SPEECH"),
    "DISABLED",
  )
  assert.equal(
    workflowModelIssue({ id: 2, enabled: true, capabilities: ["VIDEO_GENERATION"] }, "TEXT_TO_SPEECH"),
    "CAPABILITY_MISMATCH",
  )
  assert.equal(
    workflowModelIssue({ id: 3, enabled: true, capabilities: ["TEXT_TO_SPEECH"] }, "TEXT_TO_SPEECH"),
    null,
  )
})
