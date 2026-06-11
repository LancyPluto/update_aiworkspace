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
