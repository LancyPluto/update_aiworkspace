import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const componentUrl = new URL("./WorkspaceComposer.vue", import.meta.url)
const workspaceCssUrl = new URL("../../styles/workspace.css", import.meta.url)
const openAiVendorIconUrl = new URL("../../../asset/assets/vendor-icons/openai.svg", import.meta.url)

test("homepage mode tabs expose a sliding selected indicator", async () => {
  const [component, css] = await Promise.all([
    readFile(componentUrl, "utf8"),
    readFile(workspaceCssUrl, "utf8"),
  ])

  assert.match(component, /const activeModeIndex = computed/)
  assert.match(component, /--workspace-mode-index/)
  assert.match(component, /const modeTabIndicatorStyle = computed/)
  assert.match(component, /const modeTabWidth = ref\(0\)/)
  assert.match(component, /ResizeObserver/)
  assert.match(component, /translate3d\(\$\{activeModeIndex\.value \* modeTabWidth\.value\}px, 0, 0\)/)
  assert.match(component, /workspace-mode-tabs-indicator/)
  assert.match(component, /:style="modeTabStyle"/)
  assert.match(component, /:style="modeTabIndicatorStyle"/)

  assert.match(css, /\.workspace-mode-tabs-indicator/)
  assert.doesNotMatch(css, /translateX\(var\(--workspace-mode-offset\)\)/)
  assert.doesNotMatch(css, /translateX\(calc\(var\(--workspace-mode-index\)\s*\*/)
  assert.match(css, /transition:\s*transform/)
})

test("model picker loads grouped options and submits selected model config id", async () => {
  const [component, css, openAiVendorIcon] = await Promise.all([
    readFile(componentUrl, "utf8"),
    readFile(workspaceCssUrl, "utf8"),
    readFile(openAiVendorIconUrl, "utf8"),
  ])

  assert.match(component, /fetchModelOptions/)
  assert.match(component, /buildComposerModelGroupsFromResponse/)
  assert.match(component, /buildComposerModelGroupsFromTools/)
  assert.match(component, /const effectiveSelectedModelOption = computed/)
  assert.match(component, /selectedModelOption\.value\?\.auto/)
  assert.match(component, /effectiveSelectedModelOption\.value\?\.imageParameters/)
  assert.match(component, /buildComposerFormatOptions\(props\.toolDetail\?\.fields \|\| \[\],\s*effectiveSelectedModelOption\.value\?\.imageParameters/)
  assert.match(component, /modelConfigId:\s*effectiveSelectedModelOption\.value\?\.modelConfigId/)
  assert.match(component, /workspace-model-vendor-list/)
  assert.match(component, /workspace-model-vendor-icon/)
  assert.match(component, /workspace-model-list/)
  assert.match(component, /workspace-model-row/)
  assert.match(component, /modelDescription\(item\)/)
  assert.doesNotMatch(component, /workspace-model-card-grid/)
  assert.match(component, /@click="selectModelGroup/)

  assert.match(css, /\.workspace-model-picker/)
  assert.match(css, /\.workspace-model-vendor-list/)
  assert.match(css, /\.workspace-model-vendor-icon/)
  assert.match(css, /\.workspace-model-list/)
  assert.match(css, /\.workspace-model-row/)
  assert.doesNotMatch(css, /\.workspace-model-card-grid/)
  assert.match(openAiVendorIcon, /<svg/)
  assert.match(openAiVendorIcon, /#f8fafc/)
  assert.doesNotMatch(openAiVendorIcon, /currentColor/)
})

test("image format menu renders model size, quality, and count controls", async () => {
  const [component, css] = await Promise.all([
    readFile(componentUrl, "utf8"),
    readFile(workspaceCssUrl, "utf8"),
  ])

  assert.match(component, /const imageQualityOptions = computed\(\(\) => configuredFormatOptions\.value\.quality\)/)
  assert.match(component, /imageRatioOptions\.length \|\| imageQualityOptions\.length \|\| imageCountOptions\.length/)
  assert.match(component, /imageSizeAspectLabel/)
  assert.match(component, /imageSizeDimensionLabel/)
  assert.match(component, /v-if="imageQualityOptions\.length"/)
  assert.match(component, /v-for="item in imageQualityOptions"/)
  assert.match(component, /const selectedQuality = mode\.value === "image"[\s\S]*quality\.value/)
  assert.match(component, /quality:\s*selectedQuality/)

  assert.match(css, /\.workspace-image-size-options/)
  assert.match(css, /\.workspace-image-size-aspect/)
  assert.match(css, /\.workspace-image-size-dimensions/)
})

test("upload dropzone validates dropped files and shows compact asset affordances", async () => {
  const [component, css] = await Promise.all([
    readFile(componentUrl, "utf8"),
    readFile(workspaceCssUrl, "utf8"),
  ])

  assert.match(component, /const dragActive = ref\(false\)/)
  assert.match(component, /const uploadedAssets = ref<UploadedComposerAsset\[\]>/)
  assert.match(component, /function isFileAcceptedForMode\(file: File/)
  assert.match(component, /async function uploadFiles\(files: File\[\]\)/)
  assert.match(component, /function onUploadDrop\(event: DragEvent\)/)
  assert.match(component, /uploadError\.value = uploadRejectMessage\(\)/)
  assert.match(component, /当前工具不支持该文件类型/)
  assert.match(component, /@dragover\.prevent="onUploadDragOver"/)
  assert.match(component, /@drop\.prevent="onUploadDrop"/)
  assert.match(component, /multiple/)
  assert.match(component, /workspace-upload-stack/)
  assert.match(component, /workspace-upload-thumb/)
  assert.match(component, /workspace-upload-more/)
  assert.match(component, /workspace-mention-pop/)
  assert.match(component, /workspace-thumb-delete/)
  assert.match(component, /workspace-upload-lightbox/)
  assert.match(component, /const mentionOptions = computed/)
  assert.match(component, /const requiresVideoInput = computed/)
  assert.match(component, /@\(video\|视频\|素材视频\)/)
  assert.match(component, /v-for="item in mentionOptions"/)
  assert.doesNotMatch(component, /已添加素材：/)

  assert.match(css, /\.workspace-upload-stack/)
  assert.match(css, /\.workspace-upload-thumb/)
  assert.match(css, /\.workspace-upload-more/)
  assert.match(css, /\.workspace-thumb-play/)
  assert.match(css, /\.workspace-thumb-kind/)
  assert.match(css, /\.workspace-thumb-delete/)
  assert.match(css, /\.workspace-upload-lightbox/)
  assert.match(css, /\.workspace-upload-stack:hover \.workspace-upload-thumb/)
  assert.match(css, /\.workspace-upload-thumb:hover \.workspace-thumb-delete/)
  assert.match(css, /\.workspace-upload\.dragging/)
})

test("composer can submit image tools with an uploaded asset and optional prompt", async () => {
  const component = await readFile(componentUrl, "utf8")

  assert.match(component, /const hasComposerInput = computed/)
  assert.match(component, /prompt\.value\.trim\(\)\.length > 0 \|\| uploadedAssets\.value\.length > 0/)
  assert.match(component, /hasComposerInput\.value/)
})

test("composer responds to reset key by clearing the prompt draft", async () => {
  const component = await readFile(componentUrl, "utf8")

  assert.match(component, /resetKey\?: number/)
  assert.match(component, /watch\(\(\) => props\.resetKey/)
  assert.match(component, /prompt\.value = ""/)
})
