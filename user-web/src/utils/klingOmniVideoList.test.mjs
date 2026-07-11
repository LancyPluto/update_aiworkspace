import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importKlingOmniVideoList() {
  let source = await readFile(new URL("./klingOmniVideoList.ts", import.meta.url), "utf8")
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

const omniVideoList = await importKlingOmniVideoList()

const field = {
  fieldName: "参考视频列表",
  required: true,
  optionsJson: JSON.stringify({ minCount: 1, maxCount: 4, accept: "video/*" }),
}

test("serializes omni video references as documented objects", () => {
  const serialized = omniVideoList.serializeKlingOmniVideoItems([
    {
      id: "1",
      videoUrl: "https://example.com/source.mp4",
      referType: "base",
      keepOriginalSound: "yes",
    },
    {
      id: "2",
      videoUrl: "https://example.com/feature.mp4",
      referType: "feature",
      keepOriginalSound: "no",
    },
  ])

  assert.deepEqual(serialized, [
    {
      video_url: "https://example.com/source.mp4",
      refer_type: "base",
      keep_original_sound: "yes",
    },
    {
      video_url: "https://example.com/feature.mp4",
      refer_type: "feature",
      keep_original_sound: "no",
    },
  ])
})

test("parses legacy string arrays as base references", () => {
  const parsed = omniVideoList.parseKlingOmniVideoEditorItems(["https://example.com/source.mp4"], 4)

  assert.equal(parsed.length, 1)
  assert.equal(parsed[0].videoUrl, "https://example.com/source.mp4")
  assert.equal(parsed[0].referType, "base")
  assert.equal(parsed[0].keepOriginalSound, "no")
})

test("validates required video url and max count", () => {
  const missing = omniVideoList.validateKlingOmniVideoItems(
    [
      {
        id: "1",
        videoUrl: "",
        referType: "base",
        keepOriginalSound: "no",
      },
    ],
    field,
  )
  assert.equal(missing.valid, false)
  assert.match(missing.message, /未上传的参考视频/)

  const tooMany = omniVideoList.validateKlingOmniVideoItems(
    Array.from({ length: 5 }, (_, index) => ({
      id: String(index),
      videoUrl: `https://example.com/${index}.mp4`,
      referType: "base",
      keepOriginalSound: "no",
    })),
    field,
  )
  assert.equal(tooMany.valid, false)
  assert.match(tooMany.message, /最多选择 4 个参考视频/)
})

test("detects base reference videos for duration notice", () => {
  assert.equal(
    omniVideoList.hasBaseKlingOmniVideo([
      {
        id: "1",
        videoUrl: "https://example.com/source.mp4",
        referType: "base",
        keepOriginalSound: "no",
      },
    ]),
    true,
  )
})
