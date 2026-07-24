import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importModelRequestSchema() {
  let source = await readFile(new URL("./modelRequestSchema.ts", import.meta.url), "utf8")
  source = source.replace(/import type[\s\S]*?from "@\/api\/types"\n/, "")
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

const schemaTools = await importModelRequestSchema()

function model(fields) {
  return {
    modelConfigId: 7,
    displayName: "Video model",
    requestSchemaJson: { version: "1", fields },
  }
}

function imageModel(fields) {
  return {
    ...model(fields),
    capabilities: ["IMAGE_GENERATION"],
  }
}

test("model schema owns values and constraints while tool fields own UI metadata", () => {
  const fields = schemaTools.buildEffectiveToolFields([
    {
      fieldKey: "generationMode",
      fieldName: "创作方式",
      fieldType: "select",
      required: false,
      sortOrder: 9,
      options: { options: [{ label: "旧值", value: "legacy" }], uiGroup: "core" },
    },
    {
      fieldKey: "referenceImages",
      fieldName: "参考素材",
      fieldType: "multi_image",
      required: false,
      sortOrder: 10,
      options: { maxCount: 2, uiOrder: 3 },
    },
  ], model([
    {
      key: "generationMode",
      label: "生成方式",
      type: "string",
      default: "text_to_video",
      enum: [
        { label: "文生视频", value: "text_to_video" },
        { label: "全能参考", value: "multimodal_reference" },
      ],
    },
    {
      key: "referenceImages",
      label: "参考图片",
      type: "array",
      itemType: "image",
      maxItems: 9,
      maxLength: 20,
      requiresAny: ["prompt", "sourceVideo"],
      visibleWhen: { generationMode: ["multimodal_reference"] },
      requiredWhen: { anyOf: [{ generationMode: ["multimodal_reference"] }] },
    },
  ]))

  assert.equal(fields[0].fieldName, "创作方式")
  assert.equal(fields[0].fieldType, "radio")
  assert.deepEqual(fields[0].options.options.map((item) => item.value), ["text_to_video", "multimodal_reference"])
  assert.equal(fields[0].options.defaultValue, "text_to_video")
  assert.equal(fields[1].options.maxCount, 9)
  assert.equal(fields[1].options.maxLength, 20)
  assert.deepEqual(fields[1].options.requiresAnyFields, ["prompt", "sourceVideo"])
  assert.equal(fields[1].options.uiOrder, 3)
  assert.deepEqual(fields[1].options.visibleWhen, { generationMode: ["multimodal_reference"] })
  assert.deepEqual(fields[1].options.requiredWhen, { anyOf: [{ generationMode: ["multimodal_reference"] }] })
})

test("keeps tool-only business inputs while schema fields own upstream constraints", () => {
  const fields = schemaTools.buildEffectiveToolFields([
    {
      fieldKey: "businessBrief",
      fieldName: "涓氬姟鑳屾櫙",
      fieldType: "textarea",
      required: true,
      sortOrder: 20,
      options: { uiGroup: "business" },
    },
    {
      fieldKey: "prompt",
      fieldName: "鍒涙剰鎻愮ず",
      fieldType: "textarea",
      required: false,
      sortOrder: 10,
      options: { uiOrder: 3 },
    },
  ], model([
    { key: "generationMode", type: "string", default: "text", enum: ["text"] },
    { key: "prompt", type: "string", required: true, maxLength: 200 },
  ]))

  assert.deepEqual(fields.map((field) => field.fieldKey), ["generationMode", "prompt", "businessBrief"])
  assert.equal(fields[1].fieldName, "鍒涙剰鎻愮ず")
  assert.equal(fields[1].fieldType, "textarea")
  assert.equal(fields[1].required, true)
  assert.equal(fields[1].options.maxLength, 200)
  assert.equal(fields[2].required, true)
  assert.equal(fields[2].options.uiGroup, "business")
})

test("image output count is owned by the model schema and fixed single-image counts stay hidden", () => {
  const schemaLessImage = schemaTools.buildEffectiveToolFields([
    { fieldKey: "prompt", fieldName: "Prompt", fieldType: "textarea", required: true, sortOrder: 1 },
    { fieldKey: "count", fieldName: "Count", fieldType: "number", required: false, sortOrder: 2 },
  ], {
    modelConfigId: 8,
    displayName: "Schema-less image model",
    capabilities: ["IMAGE_GENERATION"],
  })
  assert.deepEqual(schemaLessImage.map((field) => field.fieldKey), ["prompt"])

  const legacyOnly = schemaTools.buildEffectiveToolFields([
    { fieldKey: "prompt", fieldName: "Prompt", fieldType: "textarea", required: true, sortOrder: 1 },
    { fieldKey: "image_count", fieldName: "Count", fieldType: "number", required: false, sortOrder: 2 },
  ], imageModel([
    { key: "prompt", type: "string", required: true },
  ]))
  assert.deepEqual(legacyOnly.map((field) => field.fieldKey), ["prompt"])

  const fixedSingle = schemaTools.buildEffectiveToolFields([], imageModel([
    { key: "n", type: "integer", min: 1, max: 1 },
  ]))[0]
  assert.equal(fixedSingle.options.uiHidden, true)
  assert.equal(fixedSingle.options.defaultValue, 1)

  const multiple = schemaTools.buildEffectiveToolFields([], imageModel([
    { key: "batch_size", type: "integer", default: 1, min: 1, max: 4 },
  ]))[0]
  assert.equal(multiple.options.uiHidden, undefined)
  assert.equal(multiple.options.maxValue, 4)

  const videoBatch = schemaTools.buildEffectiveToolFields([
    { fieldKey: "batch_size", fieldName: "Batch", fieldType: "number", required: false, sortOrder: 2 },
  ], {
    ...model([{ key: "prompt", type: "string" }]),
    capabilities: ["VIDEO_GENERATION"],
  })
  assert.deepEqual(videoBatch.map((field) => field.fieldKey), ["prompt", "batch_size"])

  const schemaLessVideoFields = [
    { fieldKey: "batch_size", fieldName: "Batch", fieldType: "number", required: false, sortOrder: 1 },
  ]
  assert.equal(schemaTools.buildEffectiveToolFields(schemaLessVideoFields, {
    modelConfigId: 9,
    displayName: "Schema-less video model",
    capabilities: ["VIDEO_GENERATION"],
  }), schemaLessVideoFields)
})

test("generation mode uses hidden, segmented and select controls from enum count", () => {
  const one = schemaTools.buildEffectiveToolFields([], model([
    { key: "generationMode", type: "string", enum: ["only"] },
  ]))[0]
  const four = schemaTools.buildEffectiveToolFields([], model([
    { key: "generationMode", type: "string", enum: ["a", "b", "c", "d"] },
  ]))[0]
  const five = schemaTools.buildEffectiveToolFields([], model([
    { key: "generationMode", type: "string", enum: ["a", "b", "c", "d", "e"] },
  ]))[0]

  assert.equal(one.options.uiHidden, true)
  assert.equal(four.fieldType, "radio")
  assert.equal(five.fieldType, "select")
})

test("switching models keeps only compatible values and trims media arrays", () => {
  const fields = schemaTools.buildEffectiveToolFields([], model([
    { key: "generationMode", type: "string", default: "image", enum: ["image", "reference"] },
    { key: "duration", type: "integer", min: 4, max: 15 },
    { key: "referenceAudios", type: "array", itemType: "audio", maxItems: 3 },
  ]))
  const params = schemaTools.prepareModelParams(fields, {
    generationMode: "unsupported",
    duration: 20,
    referenceAudios: ["a.mp3", "b.mp3", "c.mp3", "d.mp3"],
    referenceVideos: ["unsupported.mp4"],
  })

  assert.deepEqual(params, {
    generationMode: "image",
    referenceAudios: ["a.mp3", "b.mp3", "c.mp3"],
  })
})

test("numeric and boolean enum values keep their schema types", () => {
  const fields = schemaTools.buildEffectiveToolFields([
    { fieldKey: "sampleRate", fieldName: "采样率", fieldType: "number", required: false, sortOrder: 1 },
  ], model([
    { key: "sampleRate", type: "integer", control: "select", default: 32000, enum: [16000, 32000] },
    { key: "channel", type: "integer", control: "segmented", default: 1, enum: [1, 2] },
    { key: "stream", type: "boolean", control: "segmented", default: false, enum: [false, true] },
  ]))

  assert.equal(fields[0].fieldType, "select")
  assert.deepEqual(fields[0].options.options.map((item) => item.value), [16000, 32000])
  assert.equal(fields[1].fieldType, "radio")
  assert.equal(fields[2].fieldType, "radio")
  assert.deepEqual(schemaTools.prepareModelParams(fields, {
    sampleRate: "32000",
    channel: "2",
    stream: "false",
  }), {
    sampleRate: 32000,
    channel: 2,
    stream: false,
  })
})

test("configured default model wins before row default and first row", () => {
  const models = [
    { modelConfigId: 1, displayName: "A" },
    { modelConfigId: 2, displayName: "B", isDefault: true },
  ]
  assert.equal(schemaTools.resolveDefaultModelConfigId(models, 1), 1)
  assert.equal(schemaTools.resolveDefaultModelConfigId(models, 99), 2)
})

test("detects tools with at least one schema-backed model", () => {
  assert.equal(schemaTools.hasSchemaBackedModel([
    { modelConfigId: 1, displayName: "Legacy" },
    model([{ key: "prompt", type: "string" }]),
  ]), true)
  assert.equal(schemaTools.hasSchemaBackedModel([
    { modelConfigId: 1, displayName: "Legacy" },
  ]), false)
})

test("JSON string schemas are supported and legacy tools keep their scalar fields", () => {
  const parsed = schemaTools.parseModelRequestSchema(JSON.stringify({
    version: "1",
    fields: [{ key: "prompt", type: "string" }],
  }))
  assert.equal(parsed.fields[0].key, "prompt")

  const legacyFields = [{
    fieldKey: "prompt",
    fieldName: "提示词",
    fieldType: "textarea",
    required: true,
    sortOrder: 1,
  }]
  assert.equal(
    schemaTools.buildEffectiveToolFields(legacyFields, { modelConfigId: 8, displayName: "Legacy" }),
    legacyFields,
  )
})

test("preserves normalized root requires-any groups", () => {
  const parsed = schemaTools.parseModelRequestSchema({
    version: "1",
    fields: [
      { key: "generationMode", type: "string" },
      { key: "referenceImages", type: "array" },
      { key: "referenceVideos", type: "array" },
    ],
    requiresAnyGroups: [
      {
        when: { generationMode: ["multimodal_reference"] },
        fields: ["referenceImages", "referenceVideos", "referenceImages", ""],
      },
      { when: {}, fields: [] },
    ],
  })

  assert.deepEqual(parsed.requiresAnyGroups, [{
    when: { generationMode: ["multimodal_reference"] },
    fields: ["referenceImages", "referenceVideos"],
  }])
})
