import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const componentUrl = new URL("./AgentChatPane.vue", import.meta.url)

test("switching sessions clears the previous run state before loading history", async () => {
  const source = await readFile(componentUrl, "utf8")
  const watcher = source.match(/watch\(\s*\(\) => props\.sessionId,[\s\S]*?\n\)/)?.[0] ?? ""

  assert.match(watcher, /resetSessionTransientState\(\)[\s\S]*loadPane\(\)/)
  assert.match(source, /function resetSessionTransientState\(\)[\s\S]*?activeRunId\.value = null/)
  assert.match(source, /function resetSessionTransientState\(\)[\s\S]*?runConnectionStatus\.value = "idle"/)
  assert.match(source, /function resetSessionTransientState\(\)[\s\S]*?stopRunEventStream\(\)/)
})

test("generation loading is scoped to a real current operation and stays visually compact", async () => {
  const source = await readFile(componentUrl, "utf8")
  const loadingState = source.match(/const showGenerationLoading = computed\([\s\S]*?\n\)/)?.[0] ?? ""
  const loadingMarkup = source.match(/<article\s+v-if="showGenerationLoading"[\s\S]*?<\/article>/)?.[0] ?? ""

  assert.match(loadingState, /hasActiveRun\.value/)
  assert.doesNotMatch(loadingState, /runConnectionStatus\.value/)
  assert.match(loadingMarkup, /思考中/)
  assert.doesNotMatch(loadingMarkup, /AgentAvatar|科创点AI/)
})
