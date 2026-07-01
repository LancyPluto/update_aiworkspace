import assert from "node:assert/strict"
import test from "node:test"
import { inferTaskAspectRatio, parseAspectRatio } from "./taskAspectRatio.ts"

test("parseAspectRatio reads colon and slash ratios", () => {
  assert.equal(parseAspectRatio("16:9"), 16 / 9)
  assert.equal(parseAspectRatio("9/16"), 9 / 16)
})

test("inferTaskAspectRatio prefers task params over modality defaults", () => {
  assert.equal(
    inferTaskAspectRatio({
      params: { aspectRatio: "9:16" },
      outputModality: "video",
      toolType: "VIDEO",
    }),
    9 / 16,
  )
  assert.equal(
    inferTaskAspectRatio({
      params: {},
      outputModality: "video",
      toolType: "VIDEO",
    }),
    16 / 9,
  )
})
