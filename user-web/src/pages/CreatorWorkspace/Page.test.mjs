import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const pageUrl = new URL("./Page.vue", import.meta.url)
const workspaceCssUrl = new URL("../../styles/workspace.css", import.meta.url)

test("creator workspace submits selected model config id with task creation", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /initialModelConfigId/)
  assert.match(page, /routeNumberParam\("modelConfigId"\)/)
  assert.match(page, /modelConfigId:\s*state\.modelConfigId\s*\?\?\s*undefined/)
})

test("creator workspace carries the selected model label into optimistic timeline cards", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /const initialModelLabel = ref\(""\)/)
  assert.match(page, /initialModelLabel\.value = routeStringParam\("modelLabel"\) \|\| ""/)
  assert.match(page, /modelLabel:\s*initialModelLabel\.value \|\| summary\?\.modelConfigName/)
  assert.match(page, /function optimisticTaskFromResponse\(response: CreateTaskResponse, params: Record<string, unknown>, state: ComposerState\)/)
  assert.match(page, /modelConfigId:\s*state\.modelConfigId\s*\?\?\s*null/)
  assert.match(page, /modelConfigName:\s*state\.modelLabel\s*\|\|\s*null/)
  assert.match(page, /modelName:\s*state\.modelLabel\s*\|\|\s*null/)
  assert.match(page, /upsertTask\(optimisticTaskFromResponse\(created, resolved\.params, state\)\)/)
})

test("creator workspace preserves local model labels when polling old task payloads", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /function mergeTaskModelDisplay\(incoming: TaskDetail\[\]\)/)
  assert.match(page, /const existingById = new Map\(tasks\.value\.map\(\(task\) => \[task\.taskId, task\]\)\)/)
  assert.match(page, /modelConfigName:\s*task\.modelConfigName\s*\?\?\s*existing\?\.modelConfigName\s*\?\?\s*null/)
  assert.match(page, /tasks\.value = mergeTaskModelDisplay\(response\.list\)/)
})

test("creator workspace clears composer prompt after successful task creation", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /const composerResetKey = ref\(0\)/)
  assert.match(page, /function clearComposerDraftAfterSubmit\(\)/)
  assert.match(page, /composerResetKey\.value \+= 1/)
  assert.match(page, /upsertTask\(optimisticTaskFromResponse\(created, resolved\.params, state\)\)[\s\S]*clearComposerDraftAfterSubmit\(\)/)
  assert.match(page, /:reset-key="composerResetKey"/)
})

test("creator workspace preserves the submitted image or video mode across task refreshes", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /function rememberComposerSelection\(mode: CreatorMode, toolCode\?: string \| null\)/)
  assert.match(page, /const query = routeQueryWithout\(\["autoSubmit"\]\)/)
  assert.match(page, /query\.modality = mode/)
  assert.match(page, /if \(toolCode\) query\.tool = toolCode/)
  assert.match(page, /function rememberComposerStateSelection\(state: ComposerState\)/)
  assert.match(page, /selectedMode\.value = state\.mode/)
  assert.match(page, /selectedToolCode\.value = state\.toolCode/)
  assert.match(page, /rememberComposerSelection\(state\.mode, state\.toolCode \|\| selectedToolCode\.value\)/)
  assert.match(page, /rememberComposerStateSelection\(state\)[\s\S]*const resolved = resolveCreatorTask/)
  assert.match(page, /function onToolSelect\(toolCode: string\)/)
  assert.match(page, /@tool-select="onToolSelect"/)
})

test("creator workspace auto-submits homepage image and video create routes once", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /const pendingAutoSubmit = ref\(false\)/)
  assert.match(page, /const autoSubmitConsumed = ref\(false\)/)
  assert.match(page, /pendingAutoSubmit\.value = routeStringParam\("autoSubmit"\) === "1"/)
  assert.match(page, /function routeComposerState\(\): ComposerState/)
  assert.match(page, /function clearAutoSubmitRouteFlag\(\)/)
  assert.match(page, /function maybeAutoSubmitFromRoute\(\)/)
  assert.match(page, /autoSubmitConsumed\.value = true/)
  assert.match(page, /clearAutoSubmitRouteFlag\(\)/)
  assert.match(page, /void submitFromComposer\(routeComposerState\(\)\)/)
  assert.match(page, /watch\(\(\) => \[selectedToolDetail\.value, detailLoading\.value, loading\.value\] as const/)
})

test("creator workspace previews generated media with the asset modal from the result itself", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /import AssetPreviewModal from "@\/components\/AssetPreviewModal\.vue"/)
  assert.match(page, /import \{ assetFromTask \} from "@\/utils\/assetPreviewAdapter"/)
  assert.match(page, /const previewAsset = ref<AssetPreviewItem \| null>\(null\)/)
  assert.match(page, /function openTaskPreview\(task: TaskDetail\)/)
  assert.match(page, /previewAsset\.value = assetFromTask\(task/)
  assert.match(page, /class="workspace-create-result-link"/)
  assert.match(page, /@click="openTaskPreview\(item\.task\)"/)
  assert.match(page, /@keydown\.enter\.prevent="openTaskPreview\(item\.task\)"/)
  assert.match(page, /<AssetPreviewModal/)
  assert.match(page, /:asset="previewAsset"/)
  assert.doesNotMatch(page, /function openTaskDetail\(task: TaskDetail\)/)
  assert.doesNotMatch(page, /<RouterLink :to="\{ name: 'TaskResult'[\s\S]*?>查看详情<\/RouterLink>/)
})

test("creator workspace streams active task progress into the timeline", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /streamTaskStatus/)
  assert.match(page, /const taskStatusStreamControllers = new Map<number, AbortController>\(\)/)
  assert.match(page, /function applyTaskStatusUpdate\(payload: TaskStatusPayload\)/)
  assert.match(page, /function syncTaskStatusStreams\(\)/)
  assert.match(page, /streamTaskStatus\(task\.taskId, applyTaskStatusUpdate/)
  assert.match(page, /stopTaskStatusStream\(payload\.taskId\)/)
})

test("creator workspace keeps task progress monotonic across streams and refreshes", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /function monotonicTaskProgress\(existing: TaskDetail \| undefined, incomingProgress\?: number \| null\)/)
  assert.match(page, /progress:\s*monotonicTaskProgress\(existing,\s*payload\.progress\)/)
  assert.match(page, /progress:\s*monotonicTaskProgress\(existing,\s*task\.progress\)/)
})

test("creator workspace renders active generation progress as an aspect-ratio preview panel", async () => {
  const [page, css] = await Promise.all([
    readFile(pageUrl, "utf8"),
    readFile(workspaceCssUrl, "utf8"),
  ])

  assert.match(page, /buildTaskProgressView/)
  assert.match(page, /function progressView\(item: CreateTimelineItem\)/)
  assert.match(page, /GenerationLoadingPreview/)
  assert.match(page, /inferTaskAspectRatio\(item\.task\)/)
  assert.match(page, /progressView\(item\)\.percentLabel/)
  assert.match(page, /progressView\(item\)\.caption/)
  assert.match(css, /\.workspace-create-result-pending/)
})

test("creator workspace starts optimistic image tasks at one percent", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /function optimisticInitialProgress\(status: TaskStatus, state: ComposerState\)/)
  assert.match(page, /if \(status !== "PROCESSING"\) return 0/)
  assert.match(page, /return state\.mode === "image" \? 1 : 20/)
  assert.match(page, /progress:\s*optimisticInitialProgress\(response\.status, state\)/)
})
