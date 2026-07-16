import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

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

const progressView = await importTsModule("./taskProgressView.ts")

test("hides the standalone percent label when the caption already contains a percent", () => {
  assert.equal(progressView.shouldShowProgressPercentLabel("实时进度：1%", "5%"), false)
  assert.equal(progressView.shouldShowProgressPercentLabel("AI 正在生成", "5%"), true)
  assert.equal(progressView.shouldShowProgressPercentLabel("AI 正在生成", ""), false)
})

test("uses provider realtime progress without estimated wording", () => {
  const view = progressView.buildTaskProgressView(
    {
      status: "PROCESSING",
      progress: 56,
      progressMessage: "实时进度：56%",
      outputModality: "VIDEO",
      startedAt: "2026-06-08T12:00:00.000Z",
      createdAt: "2026-06-08T12:00:00.000Z",
    },
    Date.parse("2026-06-08T12:00:30.000Z"),
  )

  assert.equal(view.percent, 56)
  assert.equal(view.percentLabel, "56%")
  assert.equal(view.estimated, false)
})

test("estimates active video progress from elapsed time and caps before completion", () => {
  const view = progressView.buildTaskProgressView(
    {
      status: "PROCESSING",
      progress: 20,
      progressMessage: "视频生成任务已开始",
      outputModality: "VIDEO",
      startedAt: "2026-06-08T12:00:00.000Z",
      createdAt: "2026-06-08T12:00:00.000Z",
    },
    Date.parse("2026-06-08T12:00:25.000Z"),
  )

  assert.equal(view.estimated, true)
  assert.equal(view.percentLabel.startsWith("预计 "), true)
  assert.equal(view.percent >= 45, true)
  assert.equal(view.percent <= 88, true)
})

test("estimates active image progress one percent at a time and caps before completion", () => {
  const view = progressView.buildTaskProgressView(
    {
      status: "PROCESSING",
      progress: 90,
      progressMessage: "图片已生成，正在保存结果",
      outputModality: "IMAGE",
      toolType: "IMAGE_GENERATION",
      startedAt: "2026-06-08T12:00:00.000Z",
      createdAt: "2026-06-08T12:00:00.000Z",
    },
    Date.parse("2026-06-08T12:00:32.000Z"),
  )

  assert.equal(view.estimated, true)
  assert.equal(view.percent, 32)
  assert.equal(view.percentLabel, "预计 32%")

  const longRunningView = progressView.buildTaskProgressView(
    {
      status: "PROCESSING",
      progress: 90,
      progressMessage: "图片已生成，正在保存结果",
      outputModality: "IMAGE",
      toolType: "IMAGE_GENERATION",
      startedAt: "2026-06-08T12:00:00.000Z",
      createdAt: "2026-06-08T12:00:00.000Z",
    },
    Date.parse("2026-06-08T12:02:00.000Z"),
  )

  assert.equal(longRunningView.percent, 99)
  assert.equal(longRunningView.percentLabel, "预计 99%")
})

test("reports terminal success as complete", () => {
  const view = progressView.buildTaskProgressView({
    status: "SUCCESS",
    progress: 100,
    outputModality: "IMAGE",
    createdAt: "2026-06-08T12:00:00.000Z",
  })

  assert.equal(view.percent, 100)
  assert.equal(view.percentLabel, "100%")
  assert.equal(view.caption, "生成完成")
})
