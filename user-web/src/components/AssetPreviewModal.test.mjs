import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const componentUrl = new URL("./AssetPreviewModal.vue", import.meta.url)
const nestedModalUrls = [
  new URL("./CommunityPublishModal.vue", import.meta.url),
  new URL("./CommunityReportModal.vue", import.meta.url),
]

async function readComponent() {
  return readFile(componentUrl, "utf8")
}

test("compact asset previews scroll naturally while desktop previews stay viewport-bound", async () => {
  const component = await readComponent()

  assert.match(component, /overflow-x-hidden/)
  assert.match(component, /overflow-y-auto[^"\n]*xl:overflow-hidden/)
  assert.match(component, /class="fixed right-4 top-4[^"\n]*sm:right-6 sm:top-6"/)
  assert.doesNotMatch(component, /class="fixed inset-0[^"\n]*backdrop-blur/)
  assert.match(component, /min-h-full[^"\n]*xl:grid[^"\n]*xl:h-full/)
  assert.match(component, /<main class="[^"\n]*xl:overflow-y-auto/)
  assert.match(component, /<section class="[^"\n]*xl:min-h-\[360px\]/)
  assert.doesNotMatch(component, /max-xl:max-h-\[48vh\]/)
})

test("the media stage contains arbitrary image ratios inside a definite responsive height", async () => {
  const component = await readComponent()

  assert.match(
    component,
    /data-testid="asset-preview-stage"[\s\S]*?h-\[clamp\(280px,58svh,680px\)\][^"\n]*overflow-hidden/,
  )
  assert.match(component, /max-h-full max-w-full[^"\n]*object-contain/)
  assert.doesNotMatch(component, /min-h-\[420px\]|overflow-visible/)
})

test("metadata stacks after the media on compact screens and becomes a desktop side rail", async () => {
  const component = await readComponent()

  assert.match(component, /grid-cols-1[^"\n]*xl:grid-cols-\[minmax\(0,1fr\)_minmax\(220px,0\.32fr\)\]/)
  assert.match(component, /data-testid="asset-preview-metadata"[\s\S]*?sm:grid-cols-3[^"\n]*xl:grid-cols-1/)
})

test("overflowing thumbnail strips keep their first item reachable", async () => {
  const component = await readComponent()

  assert.match(component, /data-testid="asset-preview-thumbnails"[\s\S]*?overflow-x-auto/)
  assert.match(component, /w-max min-w-full justify-center/)
  assert.doesNotMatch(component, /overflow-x-auto[^"\n]*justify-center/)
})

test("the modal owns keyboard focus and thumbnails expose their selected state", async () => {
  const component = await readComponent()

  assert.match(component, /ref="modalRoot"[\s\S]*?tabindex="-1"/)
  assert.match(component, /@keydown\.esc\.stop="handleDialogEscape"/)
  assert.match(component, /@keydown\.tab="trapDialogFocus"/)
  assert.match(component, /watch\(\s*\(\) => Boolean\(props\.asset\)/)
  assert.match(component, /modalRoot\.value\?\.focus\(\{ preventScroll: true \}\)/)
  assert.match(component, /document\.body\.style\.overflow = "hidden"/)
  assert.match(
    component,
    /function trapDialogFocus\(event: KeyboardEvent\) \{[\s\S]*?if \(publishModalOpen\.value \|\| reportModalOpen\.value\) return/,
  )
  assert.match(
    component,
    /function handleDialogEscape\(\) \{[\s\S]*?if \(publishModalOpen\.value \|\| reportModalOpen\.value\) return[\s\S]*?emit\("close"\)/,
  )
  assert.match(component, /:aria-pressed="url === mediaUrl"/)
  assert.match(component, /:aria-label="`查看第 \$\{index \+ 1\} 张图片`"/)
})

test("nested publish and report dialogs take over keyboard focus", async () => {
  const nestedModals = await Promise.all(nestedModalUrls.map((url) => readFile(url, "utf8")))

  for (const modal of nestedModals) {
    assert.match(modal, /ref="modalRoot"[\s\S]*?role="dialog"[\s\S]*?aria-modal="true"[\s\S]*?tabindex="-1"/)
    assert.match(modal, /@keydown\.esc\.stop="close"/)
    assert.match(modal, /@keydown\.tab="trapDialogFocus"/)
    assert.match(modal, /modalRoot\.value\?\.focus\(\{ preventScroll: true \}\)/)
    assert.match(modal, /previouslyFocusedElement/)
  }
})
