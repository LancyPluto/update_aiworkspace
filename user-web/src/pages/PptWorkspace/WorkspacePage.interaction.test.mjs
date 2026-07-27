import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./WorkspacePage.vue", import.meta.url), "utf8")

test("stage navigation selects content and never directly submits a job", () => {
  const stageNavigation = source.match(/v-for="stage in stages"[\s\S]*?<\/button>/)?.[0] || ""
  assert.match(stageNavigation, /@click="selectStage\(stage\.type\)"/)
  assert.doesNotMatch(stageNavigation, /submitJob|requestGeneration|retryJob/)
})

test("generation is submitted only after the confirmation action", () => {
  assert.match(source, /@click="requestGeneration\(selectedStageType\)"/)
  assert.match(source, /@click="confirmGeneration"/)
  assert.match(source, /async function confirmGeneration\(\)[\s\S]*await submitJob\(type\)/)
  assert.match(source, /本次只生成当前阶段，不会自动更新后续阶段/)
})

test("the header downloads an existing export instead of generating another one", () => {
  assert.match(source, /if \(latestExport\.value\?\.downloadUrl\) download\(latestExport\.value\.downloadUrl\)/)
  assert.match(source, /latestExport \? "下载最新 PPTX" : "生成 PPTX"/)
})

test("historical failure cards are not rendered as a global blocking list", () => {
  assert.doesNotMatch(source, />需要处理</)
  assert.match(source, /当前仍展示上一次成功内容/)
})
