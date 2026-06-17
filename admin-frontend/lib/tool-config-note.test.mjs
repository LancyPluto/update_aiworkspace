import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

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
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const configNote = await importTsModule("./tool-config-note.ts")

test("serializes Pollo-style display fields into ai-tool-ui config", () => {
  const serialized = configNote.serializeConfigNote("operator note", {
    primaryColor: "#ff2f6d",
    welcomeMessage: "",
    mediaDisplayMode: "comparison",
    modelIconUrl: "",
    comparisonOriginalUrl: "/before.png",
    comparisonEffectUrl: "/after.png",
    heroTitle: "一键商品图",
    heroSubtitle: "上传图片生成高级商品图",
    demoThumbnails: ["/a.png"],
    useCases: ["商品图"],
    steps: ["上传", "生成", "下载"],
    recommendedToolCodes: ["style_transfer"],
  })

  const parsed = configNote.extractFrontendStyle(serialized).style

  assert.equal(parsed.heroTitle, "一键商品图")
  assert.equal(parsed.heroSubtitle, "上传图片生成高级商品图")
  assert.deepEqual(parsed.demoThumbnails, ["/a.png"])
  assert.deepEqual(parsed.useCases, ["商品图"])
  assert.deepEqual(parsed.steps, ["上传", "生成", "下载"])
  assert.deepEqual(parsed.recommendedToolCodes, ["style_transfer"])
})

test("preserves audio preview url in ai-tool-ui config", () => {
  const serialized = configNote.serializeConfigNote("", {
    mediaDisplayMode: "effect",
    audioPreviewUrl: "/generated/music/demo.mp3",
    demoThumbnails: ["/generated/music/demo.png"],
  })

  const parsed = configNote.extractFrontendStyle(serialized).style

  assert.equal(parsed.mediaDisplayMode, "effect")
  assert.equal(parsed.audioPreviewUrl, "/generated/music/demo.mp3")
  assert.deepEqual(parsed.demoThumbnails, ["/generated/music/demo.png"])
})
