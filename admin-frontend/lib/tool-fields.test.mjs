import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  let source = await readFile(new URL(path, import.meta.url), "utf8")
  source = source
    .replace(/import type \{ ToolField, ToolFieldPayload \} from ".*?"\r?\n/, "")
    .replace(/import \{[\s\S]*?\} from "@\/lib\/field-ui-meta"\r?\n/, "")
  const helpers = `
function parseFieldOptionsJson(raw) {
  if (!raw) return { options: [], meta: {} }
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) return { options: parsed, meta: {} }
    return { options: Array.isArray(parsed?.options) ? parsed.options : [], meta: parsed?.meta || {} }
  } catch { return { options: [], meta: {} } }
}
function buildMetaOptionsJson(options, meta, core) {
  return JSON.stringify({ options, meta: { ...meta, core: core === true } })
}
`
  const { outputText } = ts.transpileModule(`${helpers}\n${source}`, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const fields = await importTsModule("./tool-fields.ts")

test("creates Pollo-style image template field defaults", () => {
  const defaults = fields.createImageTemplateFields()

  assert.deepEqual(defaults.map((field) => field.fieldKey), [
    "sourceImageUrl",
    "style",
    "strength",
    "outputFormat",
  ])
  assert.equal(defaults[0].fieldType, "image")
  assert.equal(defaults[0].required, true)
  assert.equal(defaults[1].fieldType, "radio")
  assert.equal(defaults[2].fieldType, "slider")
  assert.equal(defaults[3].defaultValue, "png")
})

test("creates video template field defaults", () => {
  const defaults = fields.createVideoTemplateFields()

  assert.deepEqual(defaults.map((field) => field.fieldKey), [
    "sourceVideoUrl",
    "referenceImageUrl",
    "duration",
    "aspectRatio",
    "motionMode",
  ])
  assert.equal(defaults[0].fieldType, "file")
  assert.equal(defaults[0].required, true)
  assert.equal(defaults[1].fieldType, "image")
  assert.equal(defaults[2].defaultValue, "5")
  assert.equal(defaults[3].defaultValue, "16:9")
})

test("creates digital human template field defaults", () => {
  const defaults = fields.createDigitalHumanTemplateFields()

  assert.deepEqual(defaults.map((field) => field.fieldKey), [
    "referenceImageUrl",
    "script",
    "voiceStyle",
    "scene",
    "duration",
    "aspectRatio",
  ])
  assert.equal(defaults[0].fieldType, "image")
  assert.equal(defaults[1].fieldType, "textarea")
  assert.equal(defaults[1].required, true)
  assert.equal(defaults[2].fieldType, "radio")
  assert.equal(defaults[3].fieldType, "radio")
  assert.equal(defaults[4].defaultValue, "10")
})
