import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./WorkspaceToolCard.vue", import.meta.url), "utf8")

test("video cover errors switch to a stable placeholder instead of assigning jpg to video", () => {
  assert.match(source, /const coverFailed = ref\(false\)/)
  assert.match(source, /coverFailed\.value = true/)
  assert.match(source, /const hasRenderableCover = computed/)
  assert.doesNotMatch(source, /media\.src = DEFAULT_TOOL_COVER_URL/)
  assert.match(source, /v-if="tool\.mediaType === 'video'"/)
  assert.match(source, /v-else class="workspace-official-tool-placeholder"/)
})
