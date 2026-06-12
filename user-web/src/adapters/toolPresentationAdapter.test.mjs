import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const rewritten = source
    .replace('import { cleanToolDisplayText } from "@/utils/toolDisplayText"\n', "")
    .replace('import { formatToolCreditLabel } from "@/utils/toolCreditLabel"\n', "")
    .replaceAll("cleanToolDisplayText", "cleanToolDisplayTextForTest")
    .replaceAll("formatToolCreditLabel", "formatToolCreditLabelForTest")
  const { outputText } = ts.transpileModule(
    `function formatToolCreditLabelForTest(tool) { if (!tool) return "0 算力"; if (tool.variableCreditPricing || tool.estimatedCreditCost == null) return "算力不详"; return (tool.estimatedCreditCost ?? 0) + " 算力"; }\nfunction cleanToolDisplayTextForTest(value) { return (value || "").replace(/AI PPT ç”Ÿæˆå™¨/g, "AI PPT 生成器").replace(/åŸºäºŽ banana-slides çš„å¤šæ­¥éª¤ PPT ç”Ÿæˆ/g, "基于 banana-slides 的多步骤 PPT 生成").trim() }\n${rewritten}`,
    {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const adapter = await importTsModule("./toolPresentationAdapter.ts")

test("classifies video and image tools from modality, category, and type", () => {
  const videoTool = {
    id: 1,
    toolCode: "text_to_video",
    toolName: "Text to Video",
    categoryId: 1,
    categoryName: "视频生成",
    status: "ONLINE",
    estimatedCreditCost: 10,
    inputModality: "TEXT",
    outputModality: "VIDEO",
  }
  const imageTool = {
    ...videoTool,
    id: 2,
    toolCode: "image_creator",
    toolName: "Image Creator",
    categoryName: "图片工具",
    outputModality: "IMAGE",
  }
  const fallbackVideoTool = {
    ...videoTool,
    id: 3,
    toolCode: "motion_magic",
    categoryName: "创意工具",
    toolType: "video-effect",
    outputModality: "TEXT",
  }

  assert.equal(adapter.isVideoTool(videoTool), true)
  assert.equal(adapter.isImageTool(videoTool), false)
  assert.equal(adapter.isImageTool(imageTool), true)
  assert.equal(adapter.isVideoTool(imageTool), false)
  assert.equal(adapter.isVideoTool(fallbackVideoTool), true)
})

test("maps backend tool summaries into qianduan-style cards without losing route identity", () => {
  const tool = {
    id: 9,
    toolCode: "banana_ppt_generator",
    toolName: "PPT Generator",
    categoryId: 2,
    categoryName: "办公",
    description: "Create slides",
    coverUrl: "generated/tool-covers/ppt.png",
    status: "ONLINE",
    estimatedCreditCost: 20,
    outputModality: "TEXT",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.id, "banana_ppt_generator")
  assert.equal(card.title, "PPT Generator")
  assert.equal(card.description, "Create slides")
  assert.equal(card.image, "/generated/tool-covers/ppt.png")
  assert.equal(card.tag, "办公")
  assert.equal(card.to, "/tools/banana_ppt_generator")
  // PPT 工作台已整体下线：所有工具统一进入 /create
  assert.equal(card.useTo, "/create?tool=banana_ppt_generator")
  assert.equal(card.costLabel, "20 算力")
})

test("maps ordinary tool CTAs to the migrated creator while preserving tool identity", () => {
  const tool = {
    id: 10,
    toolCode: "text to video",
    toolName: "Video",
    categoryId: 2,
    categoryName: "视频",
    coverUrl: null,
    status: "ONLINE",
    estimatedCreditCost: 5,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.to, "/tools/text%20to%20video")
  assert.equal(card.useTo, "/create?tool=text%20to%20video")
  assert.equal(card.image, "")
})

test("does not use bundled visual fallbacks for backend tools without cover images", () => {
  const agentTool = {
    id: 11,
    toolCode: "ai_comic_drama_agent",
    toolName: "AI Comic Agent",
    categoryId: 3,
    categoryName: "智能体",
    coverUrl: null,
    status: "ONLINE",
    estimatedCreditCost: 3,
    outputModality: "TEXT",
  }

  const imageTool = {
    ...agentTool,
    id: 12,
    toolCode: "poster_creator",
    toolName: "Poster Creator",
    categoryName: "图片工具",
    outputModality: "IMAGE",
  }

  assert.equal(adapter.toWorkspaceToolCard(agentTool).image, "")
  assert.equal(adapter.toWorkspaceToolCard(imageTool).image, "")
})

test("repairs mojibake backend display text in migrated tool cards", () => {
  const tool = {
    id: 17,
    toolCode: "banana_ppt_generator",
    toolName: "AI PPT ç”Ÿæˆå™¨",
    categoryId: 1,
    categoryName: "Copywriting",
    description: "åŸºäºŽ banana-slides çš„å¤šæ­¥éª¤ PPT ç”Ÿæˆ",
    coverUrl: null,
    status: "ONLINE",
    estimatedCreditCost: 90,
    outputModality: "FILE",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.title, "AI PPT 生成器")
  assert.equal(card.description, "基于 banana-slides 的多步骤 PPT 生成")
})

test("filters public tool cards by requested creator mode", () => {
  const tools = [
    { id: 1, toolCode: "v", toolName: "V", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO" },
    { id: 2, toolCode: "i", toolName: "I", categoryId: 1, categoryName: "图片", status: "ONLINE", estimatedCreditCost: 1, outputModality: "IMAGE" },
    { id: 3, toolCode: "off", toolName: "Off", categoryId: 1, categoryName: "视频", status: "OFFLINE", estimatedCreditCost: 1, outputModality: "VIDEO" },
  ]

  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "video").map((tool) => tool.id), ["v"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "image").map((tool) => tool.id), ["i"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "all").map((tool) => tool.id), ["v", "i"])
})

test("keeps default homepage generation channels out of public tool cards", () => {
  const tools = [
    { id: 1, toolCode: "gpt_image_text_to_image", toolName: "Default Image", categoryId: 1, categoryName: "图片", status: "ONLINE", estimatedCreditCost: 1, outputModality: "IMAGE" },
    { id: 2, toolCode: "agnes_text_to_video", toolName: "Default Video", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO" },
    { id: 3, toolCode: "kling_video_editor", toolName: "Kling Video Editor", categoryId: 1, categoryName: "视频工具", status: "ONLINE", estimatedCreditCost: 12, outputModality: "VIDEO" },
  ]

  assert.equal(adapter.isInternalDefaultCreationTool("gpt_image_text_to_image"), true)
  assert.equal(adapter.isInternalDefaultCreationTool("agnes_text_to_video"), true)
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "all").map((tool) => tool.id), ["kling_video_editor"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "video").map((tool) => tool.id), ["kling_video_editor"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "image").map((tool) => tool.id), [])
})

test("classifies configured video cover URLs as video card media", () => {
  const tool = {
    id: 21,
    toolCode: "kling_video_preview",
    toolName: "Kling Video Preview",
    categoryId: 2,
    categoryName: "Video",
    description: "Preview video",
    coverUrl: "/generated/tool-covers/kling-preview.mp4",
    status: "ONLINE",
    estimatedCreditCost: 24,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.image, "/generated/tool-covers/kling-preview.mp4")
  assert.equal(card.mediaType, "video")
})

test("classifies generated video URLs with filename question marks as video media", () => {
  const tool = {
    id: 22,
    toolCode: "local_video_preview",
    toolName: "Local Video Preview",
    categoryId: 2,
    categoryName: "Video",
    coverUrl: "/generated/tool-covers/??????????-local-video-preview-20260603211234.mp4",
    status: "ONLINE",
    estimatedCreditCost: 1,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.mediaType, "video")
})

test("does not use internal config notes as public card descriptions", () => {
  const tool = {
    id: 23,
    toolCode: "internal_note_tool",
    toolName: "Internal Note Tool",
    categoryId: 2,
    categoryName: "Video",
    description: null,
    configNote: "operator-only instructions <!-- ppt-workflow:{\"engineSecrets\":{\"token\":\"secret\"}} -->",
    coverUrl: null,
    status: "ONLINE",
    estimatedCreditCost: 1,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.notEqual(card.description, "operator-only instructions")
  assert.equal(card.description.includes("secret"), false)
})
