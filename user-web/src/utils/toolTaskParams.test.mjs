import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importToolTaskParams() {
  let source = await readFile(new URL("./toolTaskParams.ts", import.meta.url), "utf8")
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

const { buildAspectRatioOptions, buildAspectRatioTaskParams, buildTaskParams } = await importToolTaskParams()

test("uses tool-specific aspect-ratio options instead of merging generic fallbacks", () => {
  assert.deepEqual(
    buildAspectRatioOptions(
      [
        { label: "智能", value: "auto" },
        { label: "2:3", value: "1024x1536" },
      ],
      ["1:1", "16:9", "9:16"],
    ),
    [
      { label: "智能", value: "auto" },
      { label: "2:3", value: "1024x1536" },
    ],
  )
})

test("uses generic aspect ratios only when the tool has no configured options", () => {
  assert.deepEqual(buildAspectRatioOptions([], ["1:1", "16:9", "9:16"]), [
    { label: "1:1", value: "1:1" },
    { label: "16:9", value: "16:9" },
    { label: "9:16", value: "9:16" },
  ])
})

test("keeps the original dynamic aspect-ratio field key for gateway forms", () => {
  assert.deepEqual(buildAspectRatioTaskParams(" 1024x1536 ", "size"), {
    aspectRatio: "1024x1536",
    imageRatio: "1024x1536",
    size: "1024x1536",
  })
})

test("filters ui-only fields from task params", () => {
  const fields = [
    { fieldKey: "prompt", fieldType: "textarea", required: false, optionsJson: "" },
    { fieldKey: "staticMask", fieldType: "image_upload", required: false, optionsJson: JSON.stringify({ submitPolicy: "ui_only" }) },
    { fieldKey: "dynamicMasks", fieldType: "textarea", required: false, optionsJson: JSON.stringify({ submitPolicy: "ui_only" }) },
  ]

  assert.deepEqual(
    buildTaskParams(fields, {
      prompt: "  make her dance  ",
      staticMask: "/generated/mask.png",
      dynamicMasks: "[{}]",
    }),
    { prompt: "make her dance" },
  )
})

test("keeps non-empty array fields and custom aspect ratio values", () => {
  const fields = [
    { fieldKey: "elementList", fieldType: "subject_element_list", required: false, optionsJson: "" },
    { fieldKey: "aspectRatio", fieldType: "aspect_ratio", required: false, optionsJson: "" },
  ]

  assert.deepEqual(
    buildTaskParams(fields, {
      elementList: [{ element_id: 123 }],
      aspectRatio: "__custom__",
      aspectRatioCustom: "21:9",
    }),
    {
      elementList: [{ element_id: 123 }],
      aspectRatio: "21:9",
    },
  )
})
