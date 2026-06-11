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

test("fallback provider capabilities include Agnes chat, image, and video providers", async () => {
  const { resolvedModelCapabilities } = await importTsModule("./model-capabilities.ts")

  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_chat" }), ["TEXT_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_images" }), ["IMAGE_GENERATION"])
  assert.deepEqual(resolvedModelCapabilities({ provider: "agnes_video" }), ["VIDEO_GENERATION"])
})
