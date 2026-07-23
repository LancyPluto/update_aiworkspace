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

const adapter = await importTsModule("./imageTemplateToolAdapter.ts")

test("detects Pollo-style image template tools from frontendStyle and modalities", () => {
  const tool = {
    inputModality: "IMAGE",
    outputModality: "IMAGE",
    frontendStyle: {
      mediaDisplayMode: "comparison",
      comparisonOriginalUrl: "/before.png",
      comparisonEffectUrl: "/after.png",
    },
    fields: [{ fieldKey: "sourceImageUrl", fieldName: "上传图片", fieldType: "image", required: true, sortOrder: 1 }],
  }

  assert.equal(adapter.isImageTemplateTool(tool), true)
  assert.equal(adapter.primaryImageField(tool)?.fieldKey, "sourceImageUrl")
})

test("builds task params from uploaded source image and compact option fields", () => {
  const tool = {
    fields: [
      { fieldKey: "sourceImageUrl", fieldName: "上传图片", fieldType: "image", required: true, sortOrder: 1 },
      { fieldKey: "style", fieldName: "风格", fieldType: "radio", required: false, defaultValue: "ecommerce", sortOrder: 2 },
      { fieldKey: "strength", fieldName: "强度", fieldType: "slider", required: false, defaultValue: "70", sortOrder: 3 },
    ],
  }

  const params = adapter.buildImageTemplateTaskParams(tool, "/uploads/product.png", { style: "clean" })

  assert.deepEqual(params, {
    sourceImageUrl: "/uploads/product.png",
    style: "clean",
    strength: "70",
  })
})

test("filters inactive mode fields and restores typed enum values", () => {
  const tool = {
    fields: [
      { fieldKey: "sourceImageUrl", fieldType: "image", required: true, sortOrder: 1 },
      { fieldKey: "generationMode", fieldType: "radio", required: true, sortOrder: 2 },
      {
        fieldKey: "referenceImages",
        fieldType: "multi_image",
        required: false,
        sortOrder: 3,
        options: { visibleWhen: { generationMode: ["image_edit"] } },
      },
      {
        fieldKey: "count",
        fieldType: "select",
        required: false,
        sortOrder: 4,
        options: { options: [{ label: "2", value: 2 }] },
      },
    ],
  }

  assert.deepEqual(adapter.buildImageTemplateTaskParams(tool, "/uploads/source.png", {
    generationMode: "text_to_image",
    referenceImages: ["old.png"],
    count: "2",
  }), {
    sourceImageUrl: "/uploads/source.png",
    generationMode: "text_to_image",
    count: 2,
  })
})
