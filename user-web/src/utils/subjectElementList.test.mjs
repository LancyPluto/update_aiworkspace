import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importSubjectElementList() {
  let source = await readFile(new URL("./subjectElementList.ts", import.meta.url), "utf8")
  source = source
    .replace(/import type .*? from ".*?"\r?\n/g, "")
    .replace(
      /import \{ parseFieldMeta \} from ".*?"\r?\n/,
      `const parseFieldMeta = (field) => {
        if (!field?.optionsJson) return {}
        try { return JSON.parse(field.optionsJson) } catch { return {} }
      }\n`,
    )
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

const subjectList = await importSubjectElementList()

const field = {
  fieldName: "主体参考列表",
  required: true,
  optionsJson: JSON.stringify({ minCount: 1, maxCount: 2 }),
}

const optionalField = {
  fieldName: "主体参考列表",
  required: false,
  optionsJson: JSON.stringify({ minCount: 0, maxCount: 2 }),
}

test("serializes selected library subject to element_id", () => {
  const serialized = subjectList.serializeSubjectElementItems([
    {
      id: "1",
      mode: "library_ref",
      elementId: "",
      upstreamElementId: "elem_123",
      frontalImage: "",
      referImages: [],
      referVideo: "",
    },
  ])

  assert.deepEqual(serialized, [{ element_id: "elem_123" }])
})

test("serializes manual element id", () => {
  const serialized = subjectList.serializeSubjectElementItems([
    {
      id: "1",
      mode: "element_id",
      elementId: "elem_manual",
      frontalImage: "",
      referImages: [],
      referVideo: "",
    },
  ])

  assert.deepEqual(serialized, [{ element_id: "elem_manual" }])
})

test("serializes temporary image and video subjects", () => {
  const serialized = subjectList.serializeSubjectElementItems([
    {
      id: "1",
      mode: "image_element",
      elementId: "",
      frontalImage: "front.jpg",
      referImages: ["side-1.jpg", "side-2.jpg", "side-3.jpg", "ignored.jpg"],
      referVideo: "",
    },
    {
      id: "2",
      mode: "video_element",
      elementId: "",
      frontalImage: "",
      referImages: [],
      referVideo: "clip.mp4",
    },
  ])

  assert.deepEqual(serialized, [
    { frontal_image: "front.jpg", refer_images: ["side-1.jpg", "side-2.jpg", "side-3.jpg"] },
    { refer_videos: ["clip.mp4"] },
  ])
})

test("falls back to default max when field config uses zero", () => {
  const max = subjectList.subjectElementMax({
    optionsJson: JSON.stringify({ maxCount: 0 }),
  })

  assert.equal(max, 7)
})

test("supports maxItems alias and allowedModes for motion control", () => {
  const motionField = {
    fieldName: "主体参考列表",
    required: false,
    optionsJson: JSON.stringify({
      maxItems: 1,
      allowedModes: ["library_ref", "element_id"],
    }),
  }

  assert.equal(subjectList.subjectElementMax(motionField), 1)
  assert.deepEqual(subjectList.subjectElementAllowedModes(motionField), ["library_ref", "element_id"])

  const invalid = subjectList.validateSubjectElementItems(
    [
      {
        id: "1",
        mode: "image_element",
        elementId: "",
        frontalImage: "front.jpg",
        referImages: ["side.jpg"],
        referVideo: "",
      },
    ],
    motionField,
  )
  assert.equal(invalid.valid, false)
  assert.match(invalid.message, /不支持当前主体类型/)
})

test("validates required subject item details", () => {
  const missingLibrary = subjectList.validateSubjectElementItems(
    [
      {
        id: "1",
        mode: "library_ref",
        elementId: "",
        upstreamElementId: "",
        frontalImage: "",
        referImages: [],
        referVideo: "",
      },
    ],
    optionalField,
  )
  assert.equal(missingLibrary.valid, false)
  assert.match(missingLibrary.message, /未选择的主体库条目/)

  const imageWithoutRefer = subjectList.validateSubjectElementItems(
    [
      {
        id: "1",
        mode: "image_element",
        elementId: "",
        frontalImage: "front.jpg",
        referImages: [],
        referVideo: "",
      },
    ],
    optionalField,
  )
  assert.equal(imageWithoutRefer.valid, false)
  assert.match(imageWithoutRefer.message, /至少上传 1 张参考图/)

  const valid = subjectList.validateSubjectElementItems(
    [
      {
        id: "1",
        mode: "element_id",
        elementId: "elem_123",
        frontalImage: "",
        referImages: [],
        referVideo: "",
      },
    ],
    field,
  )
  assert.equal(valid.valid, true)
})
