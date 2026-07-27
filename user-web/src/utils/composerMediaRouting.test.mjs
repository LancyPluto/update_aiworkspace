import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

let source = await readFile(new URL("./composerMediaRouting.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const routing = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)

function slot(overrides) {
  return {
    fieldKey: "referenceImage",
    kind: "image",
    canAdd: true,
    uploading: false,
    uploadPriority: 100,
    count: 0,
    maxCount: 1,
    previewUrls: [],
    ...overrides,
  }
}

test("routes first and last frame uploads before optional media and reports remaining capacity", () => {
  const slots = [
    slot({ fieldKey: "optionalReference", uploadPriority: 1000 }),
    slot({ fieldKey: "firstFrameImage", uploadPriority: 0 }),
    slot({ fieldKey: "lastFrameImage", uploadPriority: 1 }),
  ]

  assert.deepEqual(routing.availableComposerMediaSlots(slots, "image").map((item) => item.fieldKey), [
    "firstFrameImage",
    "lastFrameImage",
    "optionalReference",
  ])

  slots[1] = slot({
    fieldKey: "firstFrameImage",
    uploadPriority: 0,
    canAdd: false,
    count: 1,
    previewUrls: ["first.png"],
  })
  const firstOnly = [slots[1]]
  const firstAndLast = [slots[1], slots[2]]
  assert.equal(routing.remainingComposerMediaCapacity(firstOnly), 0)
  assert.equal(routing.availableComposerMediaSlots(firstAndLast, "image")[0].fieldKey, "lastFrameImage")
  assert.equal(routing.remainingComposerMediaCapacity(firstAndLast), 1)
  assert.equal(routing.lastComposerMediaPreviewKey(firstOnly), "firstFrameImage:0")
})
