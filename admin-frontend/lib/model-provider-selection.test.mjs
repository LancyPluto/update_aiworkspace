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

test("HappyHorse capabilities are normalized only against the HappyHorse provider", async () => {
  const { capabilitiesForModelProvider, findModelProvider } = await loadModule()
  const provider = findModelProvider(providers, "bailian_happyhorse")

  assert.deepEqual(capabilitiesForModelProvider(["VIDEO_GENERATION"], provider), ["VIDEO_GENERATION"])
  assert.deepEqual(capabilitiesForModelProvider(["TEXT_GENERATION"], provider), ["VIDEO_GENERATION"])
})
