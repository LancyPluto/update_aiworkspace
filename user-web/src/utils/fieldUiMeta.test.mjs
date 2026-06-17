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

test("parses reusable form metadata from optionsJson", () => {
  const meta = fieldUiMeta.parseFieldMeta({
    optionsJson: JSON.stringify({
      uiRole: "motion_video",
      uiOrder: 2,
      layoutHint: "paired_media",
      helpText: "MP4/MOV",
      submitPolicy: "ui_only",
    }),
  })

  assert.equal(meta.uiRole, "motion_video")
  assert.equal(meta.uiOrder, 2)
  assert.equal(meta.layoutHint, "paired_media")
  assert.equal(meta.helpText, "MP4/MOV")
  assert.equal(meta.submitPolicy, "ui_only")
})

test("groups fields by uiGroup and sorts by uiOrder", () => {
  const groups = fieldUiMeta.groupVisibleFields([
    { fieldKey: "mode", fieldName: "质量", sortOrder: 99, optionsJson: JSON.stringify({ uiGroup: "settings", uiGroupLabel: "常用设置", uiOrder: 6 }) },
    { fieldKey: "imageUrl", fieldName: "人物图片", sortOrder: 99, optionsJson: JSON.stringify({ uiGroup: "core", uiGroupLabel: "核心输入", uiOrder: 1 }) },
    { fieldKey: "videoUrl", fieldName: "动作视频", sortOrder: 99, optionsJson: JSON.stringify({ uiGroup: "core", uiGroupLabel: "核心输入", uiOrder: 2 }) },
  ])

  assert.equal(groups[0].key, "core")
  assert.deepEqual(groups[0].fields.map((field) => field.fieldKey), ["imageUrl", "videoUrl"])
  assert.equal(groups[1].key, "settings")
})

test("advanced fields stay visible without a custom-mode switch", () => {
  const fields = [
    { fieldKey: "staticMask", fieldName: "静态遮罩", optionsJson: JSON.stringify({ uiTier: "advanced" }) },
  ]

  assert.equal(fieldUiMeta.filterFieldsForUi(fields, {}, { advancedModeEnabled: false }).length, 1)
  assert.equal(fieldUiMeta.filterFieldsForUi(fields, {}, { advancedModeEnabled: true }).length, 0)
})
