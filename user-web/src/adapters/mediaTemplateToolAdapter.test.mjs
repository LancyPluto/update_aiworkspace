import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

async function importTsModule(path) {
  let source = await readFile(new URL(path, import.meta.url), "utf8")
  source = source
    .replace(/import type .*?\r?\n/, "")
    .replace(/import \{[\s\S]*?\} from "@\/utils\/fieldUiMeta"\r?\n/, `
      const fieldMeta = (field) => field?.options && typeof field.options === "object" && !Array.isArray(field.options) ? field.options : {}
      const fieldOptions = (field) => Array.isArray(fieldMeta(field).options) ? fieldMeta(field).options : []
      const canonicalFieldOptionValue = (field, value) => {
        const values = fieldOptions(field).map((item) => typeof item === "object" ? item.value : item)
        return values.find((item) => Object.is(item, value))
          ?? values.find((item) => String(item) === String(value))
          ?? value
      }
      const resolveConfiguredDefault = (field) => fieldMeta(field).defaultValue
        ?? (fieldOptions(field)[0] && (typeof fieldOptions(field)[0] === "object" ? fieldOptions(field)[0].value : fieldOptions(field)[0]))
        ?? (field.fieldType === "checkbox" ? false : "")
      const isFieldVisible = (field, values) => {
        const visibleWhen = fieldMeta(field).visibleWhen
        return !visibleWhen || Object.entries(visibleWhen).every(([key, allowed]) => allowed.map(String).includes(String(values[key] ?? "")))
      }
    `)
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

const publicVideoTool = {
  toolCode: "video_effect",
  toolName: "Video Effect",
  categoryCode: "video-effects",
  categoryName: "Video Effects",
  toolType: "VIDEO_GENERATION",
  inputModality: "VIDEO",
  outputModality: "VIDEO",
  toolKind: "video",
  modelDisplayName: "Video Effect Model",
}

test("resolves toolKind from backend field before modality fallback", () => {
  assert.equal(adapter.resolveToolKind({ ...publicVideoTool, toolCode: "digital_human", toolKind: "digitalHuman" }), "digitalHuman")
  assert.equal(adapter.resolveToolKind({ ...publicVideoTool, toolKind: null }), "video")
  assert.equal(adapter.resolveToolKind({ ...publicVideoTool, toolKind: null, outputModality: "AUDIO", toolType: "TEXT_TO_SPEECH" }), "audio")
  assert.equal(adapter.resolveToolKind({ ...publicVideoTool, toolKind: null, outputModality: "FILE", toolType: "FILE_PROCESSING" }), "other")
})

test("detects Pollo-style video and digital human effect tools", () => {
  assert.equal(adapter.isVideoTemplateTool({
    ...publicVideoTool,
    frontendStyle: { mediaDisplayMode: "effect", beforeVideoUrl: "/before.mp4", afterVideoUrl: "/after.mp4" },
  }), true)

  assert.equal(adapter.isVideoTemplateTool({
    ...publicVideoTool,
    toolCode: "digital_human",
    toolKind: "digitalHuman",
    frontendStyle: { mediaDisplayMode: "effect" },
  }), true)
})

test("builds media template task params from uploaded source media", () => {
  const tool = {
    ...publicVideoTool,
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

test("does not submit compact fields hidden by the selected mode", () => {
  const tool = {
    ...publicVideoTool,
    fields: [
      { fieldKey: "sourceVideoUrl", fieldType: "file", required: true, sortOrder: 1 },
      { fieldKey: "generationMode", fieldType: "radio", required: true, sortOrder: 2 },
      {
        fieldKey: "referenceImages",
        fieldType: "multi_image",
        required: false,
        sortOrder: 3,
        options: { visibleWhen: { generationMode: ["reference_to_video"] } },
      },
    ],
  }

  assert.deepEqual(adapter.buildMediaTemplateTaskParams(tool, "/uploads/source.mp4", {
    generationMode: "video_edit",
    referenceImages: ["old.png"],
  }), {
    sourceVideoUrl: "/uploads/source.mp4",
    generationMode: "video_edit",
  })
})
