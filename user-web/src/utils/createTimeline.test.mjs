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

const timeline = await importTsModule("./createTimeline.ts")

test("create timeline sorts tool calls from oldest at top to newest at bottom", () => {
  const items = timeline.buildCreateTimelineItems([
    {
      taskId: 10,
      taskNo: "T10",
      status: "SUCCESS",
      userId: 1,
      toolCode: "image_tool",
      toolName: "图片工具",
      outputModality: "IMAGE",
      params: { prompt: "first image" },
      createdAt: "2026-06-01T10:00:00",
      startedAt: "2026-06-01T10:03:00",
    },
    {
      taskId: 11,
      taskNo: "T11",
      status: "SUCCESS",
      userId: 1,
      toolCode: "video_tool",
      toolName: "视频工具",
      outputModality: "VIDEO",
      params: { prompt: "second video" },
      createdAt: "2026-06-01T10:01:00",
      startedAt: "2026-06-01T10:05:00",
    },
  ], [])

  assert.equal(items[0].task.taskId, 10)
  assert.equal(items[0].startedAtLabel, "06-01 10:03")
  assert.equal(items[1].task.taskId, 11)
})

test("create timeline exposes brand, type, model, prompt, and input materials", () => {
  const [item] = timeline.buildCreateTimelineItems([
    {
      taskId: 12,
      taskNo: "T12",
      status: "SUCCESS",
      userId: 1,
      toolCode: "518",
      toolName: "Z-image-Turbo",
      outputModality: "IMAGE",
      params: {
        prompt: "用戏剧性的电影灯光重新照亮场景。",
        referenceImage: "/generated/uploads/reference.png",
      },
      createdAt: "2026-06-01T10:35:00",
      startedAt: "2026-06-01T10:35:00",
    },
  ], [
    {
      id: 39,
      toolCode: "518",
      toolName: "Z-image-Turbo",
      categoryId: 1,
      categoryName: "图片",
      status: "ONLINE",
      estimatedCreditCost: 1,
      outputModality: "IMAGE",
      modelDisplayName: "Workspace Image 2.0",
    },
  ])

  assert.equal(item.brandName, "科创点AI")
  assert.equal(item.typeLabel, "生图")
  assert.equal(item.modelLabel, "Workspace Image 2.0")
  assert.equal(item.promptText, "用戏剧性的电影灯光重新照亮场景。")
  assert.deepEqual(item.materials, [{
    key: "referenceImage",
    kind: "image",
    url: "/generated/uploads/reference.png",
  }])
})

test("create timeline prefers the task selected model over the tool default model", () => {
  const [item] = timeline.buildCreateTimelineItems([
    {
      taskId: 13,
      taskNo: "T13",
      status: "PROCESSING",
      userId: 1,
      toolCode: "image_tool",
      toolName: "图片工具",
      outputModality: "IMAGE",
      modelConfigId: 88,
      modelConfigName: "agnes-image-2.1-flash",
      modelName: "agnes-image-2.1-flash",
      params: { prompt: "生成一只皮卡丘" },
      createdAt: "2026-06-08T12:53:00",
      startedAt: "2026-06-08T12:53:00",
    },
  ], [
    {
      id: 39,
      toolCode: "image_tool",
      toolName: "图片工具",
      categoryId: 1,
      categoryName: "图片",
      status: "ONLINE",
      estimatedCreditCost: 1,
      outputModality: "IMAGE",
      modelDisplayName: "gpt-image-2-2k",
    },
  ])

  assert.equal(item.modelLabel, "agnes-image-2.1-flash")
})

test("create timeline supports video, image, digital human, and audio tool filters", () => {
  const tools = [
    { toolCode: "video", toolName: "文生视频", outputModality: "VIDEO" },
    { toolCode: "image", toolName: "文生图", outputModality: "IMAGE" },
    { toolCode: "avatar", toolName: "数字人口播", outputModality: "VIDEO" },
    { toolCode: "voice", toolName: "AI 配音", outputModality: "AUDIO" },
  ]

  assert.equal(timeline.classifyCreateToolMode({ toolCode: "video", outputModality: "VIDEO" }), "video")
  assert.equal(timeline.classifyCreateToolMode({ toolCode: "image", outputModality: "IMAGE" }), "image")
  assert.equal(timeline.classifyCreateToolMode({ toolName: "数字人口播", outputModality: "VIDEO" }), "digitalHuman")
  assert.equal(timeline.classifyCreateToolMode({ toolName: "AI 配音", outputModality: "AUDIO" }), "audio")
  assert.deepEqual(timeline.CREATE_TOOL_MODES.map((mode) => mode.key), ["video", "image", "digitalHuman", "audio"])
  assert.deepEqual(
    tools.filter((tool) => timeline.toolMatchesCreateMode(tool, "audio")).map((tool) => tool.toolCode),
    ["voice"],
  )
})

test("create timeline falls back to the tool description for image tasks with only uploaded media", () => {
  const [item] = timeline.buildCreateTimelineItems([
    {
      taskId: 120,
      taskNo: "T120",
      status: "SUCCESS",
      userId: 1,
      toolCode: "image_outpainting",
      toolName: "AI image outpainting",
      outputModality: "IMAGE",
      params: {
        sourceImageUrl: "/generated/uploads/input.png",
      },
      createdAt: "2026-06-09T17:52:17",
      startedAt: "2026-06-09T17:52:17",
    },
  ], [
    {
      id: 40,
      toolCode: "image_outpainting",
      toolName: "AI image outpainting",
      categoryId: 1,
      categoryName: "Images",
      description: "Expand the uploaded image while preserving subject, lighting, and perspective.",
      status: "ONLINE",
      estimatedCreditCost: 1,
      outputModality: "IMAGE",
    },
  ])

  assert.equal(item.promptText, "Expand the uploaded image while preserving subject, lighting, and perspective.")
})
