import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importFieldUiMeta() {
  let source = await readFile(new URL("./fieldUiMeta.ts", import.meta.url), "utf8")
  source = source.replace(/import type .*? from ".*?"\n/g, "")
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

const fieldUiMeta = await importFieldUiMeta()

test("parses reusable form metadata from structured options", () => {
  const meta = fieldUiMeta.parseFieldMeta({
    options: {
      uiRole: "motion_video",
      uiOrder: 2,
      layoutHint: "paired_media",
      helpText: "MP4/MOV",
      submitPolicy: "ui_only",
      requiresAnyFields: ["referenceImages", "referenceVideos"],
    },
  })

  assert.equal(meta.uiRole, "motion_video")
  assert.equal(meta.uiOrder, 2)
  assert.equal(meta.layoutHint, "paired_media")
  assert.equal(meta.helpText, "MP4/MOV")
  assert.equal(meta.submitPolicy, "ui_only")
  assert.deepEqual(meta.requiresAnyFields, ["referenceImages", "referenceVideos"])
})

test("groups fields by uiGroup and sorts by uiOrder", () => {
  const groups = fieldUiMeta.groupVisibleFields([
    { fieldKey: "mode", fieldName: "质量", sortOrder: 99, options: { uiGroup: "settings", uiGroupLabel: "常用设置", uiOrder: 6 } },
    { fieldKey: "imageUrl", fieldName: "人物图片", sortOrder: 99, options: { uiGroup: "core", uiGroupLabel: "核心输入", uiOrder: 1 } },
    { fieldKey: "videoUrl", fieldName: "动作视频", sortOrder: 99, options: { uiGroup: "core", uiGroupLabel: "核心输入", uiOrder: 2 } },
  ])

  assert.equal(groups[0].key, "core")
  assert.deepEqual(groups[0].fields.map((field) => field.fieldKey), ["imageUrl", "videoUrl"])
  assert.equal(groups[1].key, "settings")
})

test("advanced fields stay visible without a custom-mode switch", () => {
  const fields = [
    { fieldKey: "staticMask", fieldName: "静态遮罩", options: { uiTier: "advanced" } },
  ]

  assert.equal(fieldUiMeta.filterFieldsForUi(fields, {}, { advancedModeEnabled: false }).length, 1)
  assert.equal(fieldUiMeta.filterFieldsForUi(fields, {}, { advancedModeEnabled: true }).length, 0)
})

test("validates conditional root requires-any groups with field labels", () => {
  const groups = [{
    when: { generationMode: ["multimodal_reference"] },
    fields: ["referenceImages", "referenceVideos"],
  }]
  const fields = [
    { fieldKey: "referenceImages", fieldName: "参考图片" },
    { fieldKey: "referenceVideos", fieldName: "参考视频" },
  ]

  assert.deepEqual(
    fieldUiMeta.validateRequiresAnyGroups(groups, { generationMode: "text_to_video" }, fields),
    { valid: true },
  )
  assert.deepEqual(
    fieldUiMeta.validateRequiresAnyGroups(groups, { generationMode: "multimodal_reference" }, fields),
    { valid: false, message: "请至少填写一项：参考图片、参考视频" },
  )
  assert.deepEqual(
    fieldUiMeta.validateRequiresAnyGroups(groups, {
      generationMode: "multimodal_reference",
      referenceVideos: ["reference.mp4"],
    }, fields),
    { valid: true },
  )
})

test("hides uiHidden fields while preserving typed option values", () => {
  const fields = [
    { fieldKey: "generationMode", fieldName: "生成模式", options: { uiHidden: true } },
    { fieldKey: "sampleRate", fieldName: "采样率", options: { options: [16000, { label: "32 kHz", value: 32000 }] } },
  ]

  assert.deepEqual(fieldUiMeta.filterFieldsForUi(fields, {}).map((field) => field.fieldKey), ["sampleRate"])
  assert.deepEqual(fieldUiMeta.fieldOptionsFromMeta(fields[1]), [
    { label: "16000", value: 16000 },
    { label: "32 kHz", value: 32000 },
  ])
  assert.equal(fieldUiMeta.canonicalFieldOptionValue(fields[1], "32000"), 32000)
})

test("resolves a core field only when it is visible in the active operation", () => {
  const fields = [
    {
      fieldKey: "prompt",
      fieldName: "提示词",
      options: { core: true, visibleWhen: { generationMode: ["generate"] } },
    },
  ]

  assert.equal(fieldUiMeta.resolveVisibleCoreField(fields, { generationMode: "generate" })?.fieldKey, "prompt")
  assert.equal(fieldUiMeta.resolveVisibleCoreField(fields, { generationMode: "add_instrumental" }), null)
})

test("evaluates nested visible and required conditions", () => {
  const field = {
    fieldKey: "prompt",
    fieldName: "提示词",
    required: false,
    options: {
      visibleWhen: { anyOf: [{ generationMode: ["generate"] }, { generationMode: ["add_vocals"] }] },
      requiredWhen: {
        anyOf: [
          { generationMode: ["add_vocals"] },
          { generationMode: ["generate"], customMode: [false] },
        ],
      },
    },
  }

  assert.equal(fieldUiMeta.isFieldVisible(field, { generationMode: "extend" }), false)
  assert.equal(fieldUiMeta.isFieldVisible(field, { generationMode: "generate" }), true)
  assert.equal(fieldUiMeta.isFieldRequired(field, { generationMode: "generate" }), true)
  assert.equal(fieldUiMeta.isFieldRequired(field, { generationMode: "generate", customMode: true }), false)
  assert.equal(fieldUiMeta.isFieldRequired(field, { generationMode: "add_vocals" }), true)
})
