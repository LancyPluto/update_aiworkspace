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

const adapter = await importTsModule("./mediaTemplateToolAdapter.ts")

test("resolves toolKind from backend field before modality fallback", () => {
  assert.equal(adapter.resolveToolKind({ toolKind: "digitalHuman", outputModality: "VIDEO" }), "digitalHuman")
  assert.equal(adapter.resolveToolKind({ outputModality: "VIDEO", toolType: "VIDEO_GENERATION" }), "video")
  assert.equal(adapter.resolveToolKind({ outputModality: "AUDIO", toolType: "TEXT_TO_SPEECH" }), "audio")
  assert.equal(adapter.resolveToolKind({ outputModality: "FILE", toolType: "FILE_PROCESSING" }), "other")
})

test("detects Pollo-style video and digital human effect tools", () => {
  assert.equal(adapter.isVideoTemplateTool({
    toolKind: "video",
    outputModality: "VIDEO",
    frontendStyle: { mediaDisplayMode: "effect", beforeVideoUrl: "/before.mp4", afterVideoUrl: "/after.mp4" },
  }), true)

  assert.equal(adapter.isVideoTemplateTool({
    toolKind: "digitalHuman",
    outputModality: "VIDEO",
    frontendStyle: { mediaDisplayMode: "effect" },
  }), true)
})

test("builds media template task params from uploaded source media", () => {
  const tool = {
    fields: [
      { fieldKey: "sourceVideoUrl", fieldName: "上传视频", fieldType: "file", required: true, sortOrder: 1 },
      { fieldKey: "duration", fieldName: "时长", fieldType: "radio", required: false, defaultValue: "10", sortOrder: 2 },
      { fieldKey: "motionMode", fieldName: "能力", fieldType: "radio", required: false, defaultValue: "face_swap", sortOrder: 3 },
    ],
  }

  const params = adapter.buildMediaTemplateTaskParams(tool, "/uploads/source.mp4", { motionMode: "motion_transfer" })

  assert.deepEqual(params, {
    sourceVideoUrl: "/uploads/source.mp4",
    duration: "10",
    motionMode: "motion_transfer",
  })
})
