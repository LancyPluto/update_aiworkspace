import assert from "node:assert/strict"
import test from "node:test"
import { inferTaskAspectRatio, parseAspectRatio } from "./taskAspectRatio.ts"

test("parseAspectRatio reads colon and slash ratios", () => {
  assert.equal(parseAspectRatio("16:9"), 16 / 9)
  assert.equal(parseAspectRatio("9/16"), 9 / 16)
  assert.equal(parseAspectRatio("9：16"), 9 / 16)
  assert.equal(parseAspectRatio("portrait"), 9 / 16)
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

test("inferTaskAspectRatio reads nested localized ratio labels and size values", () => {
  assert.equal(
    inferTaskAspectRatio({
      params: { options: { ratio: { label: "竖屏 9：16", value: "9：16" } } },
      outputModality: "image",
      toolType: "IMAGE",
    }),
    9 / 16,
  )
  assert.equal(
    inferTaskAspectRatio({
      params: { size: "1080*1920" },
      outputModality: "image",
      toolType: "IMAGE",
    }),
    9 / 16,
  )
})

test("inferTaskAspectRatio prefers concrete output size over display ratio label", () => {
  assert.equal(
    inferTaskAspectRatio({
      params: { aspectRatio: { label: "9:16", value: "1024x1536" } },
      outputModality: "image",
      toolType: "IMAGE",
    }),
    1024 / 1536,
  )
})
