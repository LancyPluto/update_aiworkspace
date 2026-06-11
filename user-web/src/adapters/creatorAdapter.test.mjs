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

const adapter = await importTsModule("./creatorAdapter.ts")

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
  fields: [
    { fieldKey: "prompt", fieldName: "提示词", fieldType: "textarea", required: true, sortOrder: 1 },
    { fieldKey: "aspect_ratio", fieldName: "画面比例", fieldType: "select", required: false, sortOrder: 2 },
    { fieldKey: "duration", fieldName: "视频时长", fieldType: "number", required: false, sortOrder: 3 },
    { fieldKey: "resolution", fieldName: "清晰度", fieldType: "select", required: false, sortOrder: 4 },
  ],
}

test("maps common composer controls to matching backend tool fields", () => {
  const result = adapter.resolveCreatorTask(
    {
      mode: "video",
      prompt: "A cinematic product shot",
      ratio: "16:9",
      durationSeconds: 5,
      quality: "720p",
    },
    videoTool,
  )

  assert.equal(result.toolCode, "text_to_video")
  assert.deepEqual(result.params, {
    prompt: "A cinematic product shot",
    aspect_ratio: "16:9",
    duration: 5,
    resolution: "720p",
  })
  assert.deepEqual(result.missingRequiredFields, [])
  assert.deepEqual(result.handledFieldKeys.sort(), ["aspect_ratio", "duration", "prompt", "resolution"])
})

test("reports required backend fields that the simplified composer cannot satisfy", () => {
  const tool = {
    ...videoTool,
    fields: [
      ...videoTool.fields,
      { fieldKey: "style", fieldName: "风格", fieldType: "select", required: true, sortOrder: 5 },
    ],
  }

  const result = adapter.resolveCreatorTask({ mode: "video", prompt: "sunset" }, tool)

  assert.deepEqual(result.missingRequiredFields, ["style"])
  assert.equal(result.params.prompt, "sunset")
})

test("advanced form values satisfy unmapped required fields", () => {
  const tool = {
    ...videoTool,
    fields: [
      ...videoTool.fields,
      { fieldKey: "style", fieldName: "风格", fieldType: "select", required: true, sortOrder: 5 },
    ],
  }

  const result = adapter.resolveCreatorTask({ mode: "video", prompt: "sunset" }, tool, { style: "写实" })

  assert.deepEqual(result.missingRequiredFields, [])
  assert.equal(result.params.style, "写实")
})

test("selects default online tools by creator mode without hardcoding model labels", () => {
  const tools = [
    { id: 1, toolCode: "offline_video", toolName: "Offline", categoryId: 1, categoryName: "视频", status: "OFFLINE", estimatedCreditCost: 1, outputModality: "VIDEO" },
    { id: 2, toolCode: "image", toolName: "Image", categoryId: 1, categoryName: "图片", status: "ONLINE", estimatedCreditCost: 1, outputModality: "IMAGE" },
    { id: 3, toolCode: "video", toolName: "Video", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO" },
  ]

  assert.equal(adapter.selectDefaultTool(tools, "video")?.toolCode, "video")
  assert.equal(adapter.selectDefaultTool(tools, "image")?.toolCode, "image")
})

test("builds homepage composer model options from online tool model bindings", () => {
  assert.equal(typeof adapter.buildComposerModelOptions, "function")

  const tools = [
    { id: 1, toolCode: "offline_video", toolName: "Offline", categoryId: 1, categoryName: "视频", status: "OFFLINE", estimatedCreditCost: 1, outputModality: "VIDEO", modelConfigName: "Offline Model", modelName: "offline" },
    { id: 2, toolCode: "image_v21", toolName: "Image Creator", categoryId: 1, categoryName: "图片", status: "ONLINE", estimatedCreditCost: 1, outputModality: "IMAGE", modelConfigName: "Kling Image 2.1", modelName: "kling-v2-1" },
    { id: 3, toolCode: "video_v3", toolName: "Video Creator", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO", modelConfigName: "Kling Video 3.0", modelName: "kling-v3" },
    { id: 4, toolCode: "local_mock_video_generator", toolName: "Local Mock", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO", modelConfigId: 99, modelConfigName: "Local Media Mock", modelName: "local-media-mock" },
    { id: 5, toolCode: "unbound_video", toolName: "Unbound", categoryId: 1, categoryName: "视频", status: "ONLINE", estimatedCreditCost: 1, outputModality: "VIDEO", modelConfigName: null, modelName: null },
    { id: 6, toolCode: "banana_ppt_generator", toolName: "AI PPT Generator", categoryId: 1, categoryName: "智能体", toolType: "IMAGE_GENERATION", status: "ONLINE", estimatedCreditCost: 1, outputModality: "FILE", executionHandler: "TEXT_GENERATION", modelConfigName: "DeepSeek Chat", modelName: "deepseek-chat" },
  ]

  const videoOptions = adapter.buildComposerModelOptions(tools, "video")
  const imageOptions = adapter.buildComposerModelOptions(tools, "image")

  assert.deepEqual(videoOptions.map((option) => option.label), ["自动选择", "Kling Video 3.0"])
  assert.equal(videoOptions[1].toolCode, "video_v3")
  assert.deepEqual(imageOptions.map((option) => option.label), ["自动选择", "Kling Image 2.1"])
  assert.equal(imageOptions[1].toolCode, "image_v21")
})

test("builds composer model groups from public model options response", () => {
  assert.equal(typeof adapter.buildComposerModelGroupsFromResponse, "function")

  const groups = adapter.buildComposerModelGroupsFromResponse({
    mode: "video",
    groups: [
      {
        vendorCode: "kling",
        vendorName: "Kling",
        models: [
          {
            modelConfigId: 42,
            displayName: "Kling 2.1",
            modelName: "kling-v2-1",
            description: "Create short videos from a prompt",
            iconUrl: "/assets/vendor-icons/kling.svg",
            badges: ["最新"],
            toolCode: "kling_video",
            estimatedCreditCost: 12,
            isDefault: true,
            imageParameters: {
              sizes: [{ label: "方图", value: "1024x1024" }],
              defaultSize: "1024x1024",
              counts: [1, 2],
              defaultCount: 2,
            },
          },
        ],
      },
      {
        vendorCode: "veo",
        vendorName: "Veo",
        models: [
          {
            id: 77,
            name: "Veo 3",
            modelName: "veo-3",
            estimatedCreditCost: 20,
          },
        ],
      },
    ],
  })

  assert.deepEqual(groups.map((group) => group.label), ["Kling", "Veo"])
  assert.equal(groups[0].models[0].key, "model:42")
  assert.equal(groups[0].models[0].modelConfigId, 42)
  assert.equal(groups[0].models[0].toolCode, "kling_video")
  assert.equal(groups[0].models[0].description, "Create short videos from a prompt")
  assert.equal(groups[0].models[0].iconUrl, "/assets/vendor-icons/kling.svg")
  assert.deepEqual(groups[0].models[0].badges, ["最新"])
  assert.equal(groups[0].models[0].isDefault, true)
  assert.deepEqual(groups[0].models[0].imageParameters.counts, [1, 2])
  assert.equal(groups[1].models[0].key, "model:77")
})

test("infers image size and count options for legacy public image model responses", () => {
  const groups = adapter.buildComposerModelGroupsFromResponse({
    mode: "image",
    groups: [
      {
        vendorCode: "agnes",
        vendorName: "Agnes AI",
        models: [
          {
            modelConfigId: 65,
            displayName: "agnes-image-2.1-flash",
            modelName: "agnes-image-2.1-flash",
          },
        ],
      },
      {
        vendorCode: "openai",
        vendorName: "OpenAI",
        models: [
          {
            modelConfigId: 25,
            displayName: "gpt-image-2-4k",
            modelName: "gpt-image-2-4k",
          },
        ],
      },
    ],
  })

  assert.deepEqual(groups[0].models[0].imageParameters.sizes.map((option) => option.value), [
    "1024x1024",
    "1536x1024",
    "1024x1536",
  ])
  assert.deepEqual(groups[0].models[0].imageParameters.counts, [1, 2, 3, 4])
  assert.ok(groups[1].models[0].imageParameters.sizes.some((option) => option.value === "3840x2160"))
})

test("infers parameters only for actual Kling image models, not image-to-video entries", () => {
  const groups = adapter.buildComposerModelGroupsFromResponse({
    mode: "image",
    groups: [
      {
        vendorCode: "kling",
        vendorName: "可灵",
        models: [
          {
            modelConfigId: 9,
            configCode: "kling-v1-image-to-video",
            displayName: "可灵 V1 图生视频",
            modelName: "kling-v1-i2v",
            provider: "kling_video",
          },
          {
            modelConfigId: 23,
            configCode: "8",
            displayName: "可灵生图 v2-1",
            modelName: "kling-v2-1",
            provider: "kling_video",
          },
        ],
      },
    ],
  })

  assert.equal(groups[0].models[0].imageParameters, null)
  assert.deepEqual(groups[0].models[1].imageParameters.sizes.map((option) => option.value), [
    "1024x1024",
    "1280x720",
    "720x1280",
    "1024x768",
    "768x1024",
    "1152x768",
    "768x1152",
  ])
})

test("builds composer model groups from tools as endpoint fallback", () => {
  assert.equal(typeof adapter.buildComposerModelGroupsFromTools, "function")

  const groups = adapter.buildComposerModelGroupsFromTools([
    {
      id: 1,
      toolCode: "kling_video",
      toolName: "Kling Video",
      categoryId: 1,
      categoryName: "视频",
      status: "ONLINE",
      estimatedCreditCost: 12,
      outputModality: "VIDEO",
      modelConfigId: 42,
      modelConfigName: "Kling 2.1",
      modelName: "kling-v2-1",
    },
    {
      id: 2,
      toolCode: "veo_video",
      toolName: "Veo Video",
      categoryId: 1,
      categoryName: "视频",
      status: "ONLINE",
      estimatedCreditCost: 20,
      outputModality: "VIDEO",
      modelConfigId: 77,
      modelConfigName: "Veo 3",
      modelName: "veo-3",
    },
  ], "video")

  assert.deepEqual(groups.map((group) => group.label), ["Kling", "Veo"])
  assert.deepEqual(groups.flatMap((group) => group.models.map((model) => model.modelConfigId)), [42, 77])
})

test("keeps video, digital human, image, and audio model candidates mutually exclusive", () => {
  const tools = [
    {
      id: 1,
      toolCode: "digital_human_video",
      toolName: "Digital Human Presenter",
      categoryId: 1,
      categoryName: "数字人",
      status: "ONLINE",
      estimatedCreditCost: 30,
      outputModality: "VIDEO",
      modelConfigName: "Avatar Video",
      modelName: "avatar-v1",
    },
    {
      id: 2,
      toolCode: "voice_tts",
      toolName: "Voice TTS",
      categoryId: 1,
      categoryName: "音频",
      status: "ONLINE",
      estimatedCreditCost: 5,
      outputModality: "AUDIO",
      modelConfigName: "TTS Pro",
      modelName: "tts-pro",
    },
    {
      id: 3,
      toolCode: "plain_video",
      toolName: "Plain Video Generator",
      categoryId: 1,
      categoryName: "视频",
      status: "ONLINE",
      estimatedCreditCost: 10,
      outputModality: "VIDEO",
      modelConfigName: "Video Pro",
      modelName: "video-pro",
    },
    {
      id: 4,
      toolCode: "image_model",
      toolName: "Image Generator",
      categoryId: 1,
      categoryName: "图片",
      status: "ONLINE",
      estimatedCreditCost: 4,
      outputModality: "IMAGE",
      modelConfigName: "Image Pro",
      modelName: "image-pro",
    },
  ]

  const labels = (mode) => adapter.buildComposerModelOptions(tools, mode).map((option) => option.toolCode).filter(Boolean)

  assert.deepEqual(labels("video"), ["plain_video", "plain_video"])
  assert.deepEqual(labels("digitalHuman"), ["digital_human_video", "digital_human_video"])
  assert.deepEqual(labels("audio"), ["voice_tts", "voice_tts"])
  assert.deepEqual(labels("image"), ["image_model", "image_model"])
  assert.equal(adapter.creatorModeForTool(tools[0]), "digitalHuman")
})

test("builds homepage composer format options from selected tool field schema", () => {
  assert.equal(typeof adapter.buildComposerFormatOptions, "function")

  const fields = [
    { fieldKey: "prompt", fieldName: "提示词", fieldType: "textarea", required: true, sortOrder: 1 },
    { fieldKey: "duration", fieldName: "视频时长", fieldType: "select", required: false, sortOrder: 2, options: [{ label: "5 秒", value: "5" }, { label: "10 秒", value: "10" }], defaultValue: "10" },
    { fieldKey: "resolution", fieldName: "分辨率", fieldType: "select", required: false, sortOrder: 3, options: ["720p"] },
    { fieldKey: "aspectRatio", fieldName: "AspectRatio", fieldType: "select", required: false, sortOrder: 4, options: [{ label: "竖屏 9:16", value: "9:16" }, { label: "横屏 16:9", value: "16:9" }], defaultValue: "9:16" },
    { fieldKey: "image_count", fieldName: "图片数量", fieldType: "number", required: false, sortOrder: 5, placeholder: "例如 1" },
  ]

  const options = adapter.buildComposerFormatOptions(fields)

  assert.deepEqual(options.duration.map((option) => option.label), ["5 秒", "10 秒"])
  assert.deepEqual(options.quality.map((option) => option.value), ["720p"])
  assert.deepEqual(options.ratio.map((option) => option.value), ["9:16", "16:9"])
  assert.deepEqual(options.count.map((option) => option.value), [1, 2, 3, 4])
  assert.equal(options.defaults.duration, 10)
  assert.equal(options.defaults.ratio, "9:16")
  assert.equal(options.defaults.count, 1)
})

test("uses selected image model parameters as homepage image size and count options", () => {
  const options = adapter.buildComposerFormatOptions([], {
    sizes: [
      { label: "方图 1024", value: "1024x1024" },
      { label: "横图 1536", value: "1536x1024" },
    ],
    defaultSize: "1536x1024",
    counts: [1, 2, 4],
    defaultCount: 2,
    qualities: [
      { label: "Low", value: "low" },
      { label: "High", value: "high" },
    ],
    defaultQuality: "high",
  })

  assert.deepEqual(options.ratio.map((option) => option.value), ["1024x1024", "1536x1024"])
  assert.deepEqual(options.count.map((option) => option.value), [1, 2, 4])
  assert.deepEqual(options.quality.map((option) => option.value), ["low", "high"])
  assert.equal(options.labels.ratio, "图片尺寸")
  assert.equal(options.defaults.ratio, "1536x1024")
  assert.equal(options.defaults.count, 2)
  assert.equal(options.defaults.quality, "high")
})

test("submits selected image size and count even when the default image tool has no format fields", () => {
  const result = adapter.resolveCreatorTask(
    {
      mode: "image",
      prompt: "a tiny robot",
      ratio: "1536x1024",
      quality: "high",
      outputCount: 2,
    },
    {
      ...videoTool,
      toolCode: "gpt_image_text_to_image",
      fields: [
        { fieldKey: "prompt", fieldName: "提示词", fieldType: "textarea", required: true, sortOrder: 1 },
        { fieldKey: "sourceImageUrl", fieldName: "参考图片", fieldType: "image_upload", required: false, sortOrder: 2 },
      ],
    },
  )

  assert.equal(result.params.imageSize, "1536x1024")
  assert.equal(result.params.quality, "high")
  assert.equal(result.params.count, 2)
  assert.deepEqual(result.missingRequiredFields, [])
})

test("treats model mode fields as homepage format options and task params", () => {
  const tool = {
    ...videoTool,
    fields: [
      { fieldKey: "prompt", fieldName: "画面描述", fieldType: "textarea", required: true, sortOrder: 1 },
      { fieldKey: "aspectRatio", fieldName: "画面比例", fieldType: "radio", required: true, sortOrder: 2, options: [{ label: "16:9", value: "16:9" }] },
      { fieldKey: "duration", fieldName: "时长", fieldType: "radio", required: true, sortOrder: 3, options: [{ label: "5秒", value: "5" }] },
      { fieldKey: "mode", fieldName: "质量档位", fieldType: "radio", required: true, sortOrder: 4, options: [{ label: "标准（std）", value: "std" }, { label: "高质量（pro）", value: "pro" }] },
    ],
  }

  const options = adapter.buildComposerFormatOptions(tool.fields)
  const result = adapter.resolveCreatorTask(
    {
      mode: "video",
      prompt: "city timelapse",
      ratio: "16:9",
      durationSeconds: 5,
      quality: "pro",
    },
    tool,
  )

  assert.deepEqual(options.quality.map((option) => option.value), ["std", "pro"])
  assert.equal(options.labels.quality, "质量档位")
  assert.equal(result.params.mode, "pro")
  assert.deepEqual(result.missingRequiredFields, [])
})

test("infers creator mode from a routed tool so compatibility entries keep their context", () => {
  assert.equal(adapter.creatorModeForTool({ outputModality: "IMAGE", categoryName: "图片" }), "image")
  assert.equal(adapter.creatorModeForTool({ outputModality: "VIDEO", categoryName: "视频" }), "video")
  assert.equal(adapter.creatorModeForTool({ toolType: "agent", categoryName: "智能体" }), "agent")
})

test("shows only unmapped required fields when the composer has not submitted yet", () => {
  const tool = {
    ...videoTool,
    fields: [
      ...videoTool.fields,
      { fieldKey: "style", fieldName: "风格", fieldType: "select", required: true, sortOrder: 5 },
    ],
  }
  const preview = adapter.resolveCreatorTask({ mode: "video", prompt: "" }, tool)
  const advancedFields = adapter.getAdvancedFields(tool, preview.handledFieldKeys)

  assert.deepEqual(advancedFields.map((field) => field.fieldKey), ["style"])
  assert.deepEqual(preview.missingRequiredFields, ["prompt", "style"])
})

test("keeps upload fields visible until the composer provides an uploaded asset", () => {
  const tool = {
    ...videoTool,
    fields: [
      ...videoTool.fields,
      { fieldKey: "reference_image", fieldName: "参考图片", fieldType: "image", required: true, sortOrder: 5 },
    ],
  }
  const preview = adapter.resolveCreatorTask({ mode: "video", prompt: "" }, tool)
  const advancedFields = adapter.getAdvancedFields(tool, preview.handledFieldKeys)

  assert.deepEqual(advancedFields.map((field) => field.fieldKey), ["reference_image"])
  assert.deepEqual(preview.missingRequiredFields, ["prompt", "reference_image"])
})

test("maps an uploaded composer asset into image or file backend fields", () => {
  const tool = {
    ...videoTool,
    fields: [
      ...videoTool.fields,
      { fieldKey: "reference_image", fieldName: "参考图片", fieldType: "image", required: true, sortOrder: 5 },
    ],
  }

  const result = adapter.resolveCreatorTask(
    {
      mode: "video",
      prompt: "make this move",
      uploadedAssetUrl: "/generated/uploads/reference.png",
    },
    tool,
  )

  assert.equal(result.params.reference_image, "/generated/uploads/reference.png")
  assert.deepEqual(result.missingRequiredFields, [])
  assert.equal(adapter.getAdvancedFields(tool, result.handledFieldKeys).some((field) => field.fieldKey === "reference_image"), false)
})

test("keeps Agnes video text-only when no image is uploaded and maps uploaded images into referenceImageUrl", () => {
  const tool = {
    ...videoTool,
    toolCode: "agnes_text_to_video",
    fields: [
      ...videoTool.fields,
      { fieldKey: "referenceImageUrl", fieldName: "参考图片", fieldType: "image_upload", required: false, sortOrder: 5 },
    ],
  }

  const textOnly = adapter.resolveCreatorTask({ mode: "video", prompt: "make a product reveal" }, tool)
  assert.equal(textOnly.params.referenceImageUrl, undefined)
  assert.deepEqual(textOnly.missingRequiredFields, [])

  const imageToVideo = adapter.resolveCreatorTask(
    {
      mode: "video",
      prompt: "make this image move",
      uploadedAssetUrl: "/generated/uploads/reference.png",
    },
    tool,
  )

  assert.equal(imageToVideo.params.referenceImageUrl, "/generated/uploads/reference.png")
  assert.deepEqual(imageToVideo.missingRequiredFields, [])
})

test("maps homepage uploaded image into the default image generator even when the tool has only prompt fields", () => {
  const tool = {
    ...videoTool,
    toolCode: "gpt_image_text_to_image",
    toolType: "IMAGE_GENERATION",
    inputModality: "TEXT",
    outputModality: "IMAGE",
    fields: [
      { fieldKey: "prompt", fieldName: "Prompt", fieldType: "textarea", required: true, sortOrder: 1 },
    ],
  }

  const textOnly = adapter.resolveCreatorTask({ mode: "image", prompt: "make a poster" }, tool)
  assert.equal(textOnly.params.sourceImageUrl, undefined)
  assert.equal(textOnly.params.referenceImageUrl, undefined)

  const imageToImage = adapter.resolveCreatorTask(
    {
      mode: "image",
      prompt: "keep the subject and change the style",
      uploadedAssetUrl: "/generated/uploads/reference.png",
    },
    tool,
  )

  assert.equal(imageToImage.params.sourceImageUrl, "/generated/uploads/reference.png")
  assert.deepEqual(imageToImage.missingRequiredFields, [])
})

test("keeps video-only controls visible for image mode tools when no image equivalent exists", () => {
  const tool = {
    ...videoTool,
    outputModality: "IMAGE",
    fields: [
      { fieldKey: "prompt", fieldName: "提示词", fieldType: "textarea", required: true, sortOrder: 1 },
      { fieldKey: "resolution", fieldName: "清晰度", fieldType: "select", required: true, sortOrder: 2 },
    ],
  }
  const preview = adapter.resolveCreatorTask({ mode: "image", prompt: "" }, tool)
  const advancedFields = adapter.getAdvancedFields(tool, preview.handledFieldKeys)

  assert.deepEqual(advancedFields.map((field) => field.fieldKey), ["resolution"])
  assert.deepEqual(preview.missingRequiredFields, ["prompt", "resolution"])
})
