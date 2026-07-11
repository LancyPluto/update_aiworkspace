import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const componentUrl = new URL("./ResultRenderer.vue", import.meta.url)

test("compact media downloads stop click bubbling to parent result links", async () => {
  const component = await readFile(componentUrl, "utf8")
  const floatingImageDownload = component.match(/<button\s+v-if="props\.mode === 'compact'"[\s\S]*?<\/button>/)?.[0] || ""

  assert.match(floatingImageDownload, /:class="floatingDownloadClass\(\)"/)
  assert.match(floatingImageDownload, /@click\.stop/)
  assert.match(component, /:class="mediaActionClass\(\)"[\s\S]*@click\.stop/)
})
