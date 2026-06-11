import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { test } from "node:test"
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
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const mediaKind = await importTsModule("./tool-media-kind.ts")

test("resolves image, video, digital human, audio, agent and text tool media kinds", () => {
  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolType: "IMAGE_TO_IMAGE",
      inputModality: "IMAGE",
      outputModality: "IMAGE",
    }),
    "image",
  )

  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolType: "VIDEO_GENERATION",
      inputModality: "IMAGE",
      outputModality: "VIDEO",
    }),
    "video",
  )

  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolCode: "health_digital_human",
      toolName: "健康医疗数字人",
      toolType: "VIDEO_GENERATION",
      inputModality: "TEXT",
      outputModality: "VIDEO",
      executionHandler: "DIGITAL_HUMAN",
    }),
    "digitalHuman",
  )

  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolType: "TEXT_TO_SPEECH",
      inputModality: "TEXT",
      outputModality: "AUDIO",
    }),
    "audio",
  )

  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolType: "AGENT",
      inputModality: "MULTIMODAL",
      outputModality: "TEXT",
    }),
    "agent",
  )

  assert.equal(
    mediaKind.resolveToolMediaKind({
      toolType: "FILE_PROCESSING",
      inputModality: "FILE",
      outputModality: "FILE",
    }),
    "other",
  )
})

test("returns sensible defaults for media tool presets", () => {
  assert.deepEqual(mediaKind.defaultsForMediaKind("image"), {
    toolType: "IMAGE_TO_IMAGE",
    inputModality: "IMAGE",
    outputModality: "IMAGE",
    mediaDisplayMode: "comparison",
    templateCode: "image_generation_default",
  })

  assert.deepEqual(mediaKind.defaultsForMediaKind("video"), {
    toolType: "VIDEO_GENERATION",
    inputModality: "IMAGE",
    outputModality: "VIDEO",
    mediaDisplayMode: "effect",
    templateCode: "video_generation_default",
  })

  assert.deepEqual(mediaKind.defaultsForMediaKind("digitalHuman"), {
    toolType: "VIDEO_GENERATION",
    inputModality: "MULTIMODAL",
    outputModality: "VIDEO",
    mediaDisplayMode: "effect",
    templateCode: "video_generation_default",
  })

  assert.deepEqual(mediaKind.defaultsForMediaKind("audio"), {
    toolType: "TEXT_TO_SPEECH",
    inputModality: "TEXT",
    outputModality: "AUDIO",
    mediaDisplayMode: "icon",
    templateCode: "",
  })
})
