import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const rewritten = source
    .replace('import { cleanToolDisplayText } from "@/utils/toolDisplayText"\n', "")
    .replace('import { formatToolCreditLabel } from "@/utils/toolCreditLabel"\n', "")
    .replace('import { resolveSummaryToolCoverUrl } from "@/utils/toolCoverMedia"\n', "")
    .replaceAll("cleanToolDisplayText", "cleanToolDisplayTextForTest")
    .replaceAll("formatToolCreditLabel", "formatToolCreditLabelForTest")
    .replaceAll("resolveSummaryToolCoverUrl", "resolveSummaryToolCoverUrlForTest")
  const { outputText } = ts.transpileModule(
    `function formatToolCreditLabelForTest(tool) { if (!tool) return "0 算力"; if (tool.variableCreditPricing || tool.estimatedCreditCost == null) return "算力不详"; return (tool.estimatedCreditCost ?? 0) + " 算力"; }\nfunction cleanToolDisplayTextForTest(value) { return (value || "").replace(/AI PPT ç”Ÿæˆå™¨/g, "AI PPT 生成器").replace(/åŸºäºŽ banana-slides çš„å¤šæ­¥éª¤ PPT ç”Ÿæˆ/g, "基于 banana-slides 的多步骤 PPT 生成").trim() }\nfunction resolveSummaryToolCoverUrlForTest(tool) { return tool?.coverUrl || "" }\n${rewritten}`,
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
    toolCode: "text_to_video",
    toolName: "Text to Video",
    categoryCode: "video-generation",
    categoryName: "视频生成",
    toolKind: "video",
    estimatedCreditCost: 10,
    inputModality: "TEXT",
    outputModality: "VIDEO",
  }
  const imageTool = {
    ...videoTool,
    toolCode: "image_creator",
    toolName: "Image Creator",
    categoryCode: "image-tools",
    categoryName: "图片工具",
    toolKind: "image",
    outputModality: "IMAGE",
  }
  const fallbackVideoTool = {
    ...videoTool,
    toolCode: "motion_magic",
    categoryCode: "creative-tools",
    categoryName: "创意工具",
    toolKind: "other",
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
    toolCode: "banana_ppt_generator",
    toolName: "PPT Generator",
    categoryCode: "office",
    categoryName: "办公",
    description: "Create slides",
    coverUrl: "generated/tool-covers/ppt.png",
    toolKind: "agent",
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
  // PPT 工作台已整体下线：所有工具统一进入当前生成工作台。
  assert.equal(card.useTo, "/dashboard?tool=banana_ppt_generator")
  assert.equal(card.costLabel, "20 算力")
})

test("maps ordinary tool CTAs to the migrated creator while preserving tool identity", () => {
  const tool = {
    toolCode: "text to video",
    toolName: "Video",
    categoryCode: "video",
    categoryName: "视频",
    coverUrl: null,
    toolKind: "video",
    estimatedCreditCost: 5,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.to, "/tools/text%20to%20video")
  assert.equal(card.useTo, "/dashboard?tool=text%20to%20video")
  assert.equal(card.image, "")
})

test("does not use bundled visual fallbacks for backend tools without cover images", () => {
  const agentTool = {
    toolCode: "ai_comic_drama_agent",
    toolName: "AI Comic Agent",
    categoryCode: "agent",
    categoryName: "智能体",
    coverUrl: null,
    toolKind: "agent",
    estimatedCreditCost: 3,
    outputModality: "TEXT",
  }

  const imageTool = {
    ...agentTool,
    toolCode: "poster_creator",
    toolName: "Poster Creator",
    categoryCode: "image-tools",
    categoryName: "图片工具",
    toolKind: "image",
    outputModality: "IMAGE",
  }

  assert.equal(adapter.toWorkspaceToolCard(agentTool).image, "")
  assert.equal(adapter.toWorkspaceToolCard(imageTool).image, "")
})

test("repairs mojibake backend display text in migrated tool cards", () => {
  const tool = {
    toolCode: "banana_ppt_generator",
    toolName: "AI PPT ç”Ÿæˆå™¨",
    categoryCode: "copywriting",
    categoryName: "Copywriting",
    description: "åŸºäºŽ banana-slides çš„å¤šæ­¥éª¤ PPT ç”Ÿæˆ",
    coverUrl: null,
    toolKind: "agent",
    estimatedCreditCost: 90,
    outputModality: "FILE",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.title, "AI PPT 生成器")
  assert.equal(card.description, "基于 banana-slides 的多步骤 PPT 生成")
})

test("filters public tool cards by requested creator mode", () => {
  const tools = [
    { toolCode: "v", toolName: "V", categoryCode: "video", categoryName: "视频", toolKind: "video", estimatedCreditCost: 1, outputModality: "VIDEO" },
    { toolCode: "i", toolName: "I", categoryCode: "image", categoryName: "图片", toolKind: "image", estimatedCreditCost: 1, outputModality: "IMAGE" },
  ]

  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "video").map((tool) => tool.id), ["v"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "image").map((tool) => tool.id), ["i"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "all").map((tool) => tool.id), ["v", "i"])
})

test("keeps default homepage generation channels out of public tool cards", () => {
  const tools = [
    { toolCode: "gpt_image_text_to_image", toolName: "Default Image", categoryCode: "image", categoryName: "图片", toolKind: "image", estimatedCreditCost: 1, outputModality: "IMAGE" },
    { toolCode: "agnes_text_to_video", toolName: "Default Video", categoryCode: "video", categoryName: "视频", toolKind: "video", estimatedCreditCost: 1, outputModality: "VIDEO" },
    { toolCode: "kling_video_editor", toolName: "Kling Video Editor", categoryCode: "video-tools", categoryName: "视频工具", toolKind: "video", estimatedCreditCost: 12, outputModality: "VIDEO" },
  ]

  assert.equal(adapter.isInternalDefaultCreationTool("gpt_image_text_to_image"), true)
  assert.equal(adapter.isInternalDefaultCreationTool("agnes_text_to_video"), true)
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "all").map((tool) => tool.id), ["kling_video_editor"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "video").map((tool) => tool.id), ["kling_video_editor"])
  assert.deepEqual(adapter.toWorkspaceToolCards(tools, "image").map((tool) => tool.id), [])
})

test("classifies configured video cover URLs as video card media", () => {
  const tool = {
    toolCode: "kling_video_preview",
    toolName: "Kling Video Preview",
    categoryCode: "video",
    categoryName: "Video",
    description: "Preview video",
    coverUrl: "/generated/tool-covers/kling-preview.mp4",
    toolKind: "video",
    estimatedCreditCost: 24,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.image, "/generated/tool-covers/kling-preview.mp4")
  assert.equal(card.mediaType, "video")
})

test("classifies generated video URLs with filename question marks as video media", () => {
  const tool = {
    toolCode: "local_video_preview",
    toolName: "Local Video Preview",
    categoryCode: "video",
    categoryName: "Video",
    coverUrl: "/generated/tool-covers/??????????-local-video-preview-20260603211234.mp4",
    toolKind: "video",
    estimatedCreditCost: 1,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.mediaType, "video")
})

test("uses a public fallback when the tool description is absent", () => {
  const tool = {
    toolCode: "description_fallback_tool",
    toolName: "Description Fallback Tool",
    categoryCode: "video",
    categoryName: "Video",
    description: null,
    coverUrl: null,
    toolKind: "video",
    estimatedCreditCost: 1,
    outputModality: "VIDEO",
  }

  const card = adapter.toWorkspaceToolCard(tool)

  assert.equal(card.description.length > 0, true)
  assert.notEqual(card.description, tool.toolName)
})
