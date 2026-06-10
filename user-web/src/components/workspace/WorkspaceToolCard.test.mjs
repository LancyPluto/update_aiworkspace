import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./WorkspaceToolCard.vue", import.meta.url), "utf8")

test("video cover errors switch to an image fallback instead of assigning jpg to video", () => {
  assert.match(source, /const fallbackAsImage = ref\(false\)/)
  assert.match(source, /fallbackAsImage\.value = true/)
  assert.doesNotMatch(source, /media\.src = DEFAULT_TOOL_COVER_URL/)
  assert.match(source, /v-if="tool\.mediaType === 'video' && !fallbackAsImage"/)
  assert.match(source, /:src="fallbackAsImage \? DEFAULT_TOOL_COVER_URL : tool\.image"/)
})
