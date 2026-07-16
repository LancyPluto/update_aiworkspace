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

const { buildAspectRatioTaskParams, buildTaskParams } = await importToolTaskParams()

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
