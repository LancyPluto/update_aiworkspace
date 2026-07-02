import test from "node:test"
import assert from "node:assert/strict"
import {
  buildTaskImageOutputPlan,
  inferParallelSlotPercent,
  inferSerialActiveSlot,
  inferTaskImageOutputLayout,
  resolveTaskImageOutputCount,
} from "./taskImageOutput.ts"

test("resolveTaskImageOutputCount reads image task count from params", () => {
  assert.equal(
    resolveTaskImageOutputCount({
      outputModality: "IMAGE",
      toolType: "TOOL",
      params: { count: "3", imageRatio: "1024x1536" },
    }),
    3,
  )
})

test("inferTaskImageOutputLayout treats sequential generation as serial", () => {
  assert.equal(
    inferTaskImageOutputLayout({
      outputModality: "IMAGE",
      toolType: "TOOL",
      params: { sequentialImageGeneration: "auto" },
    }),
    "serial",
  )
})

test("buildTaskImageOutputPlan enables multi preview for parallel multi-count image tasks", () => {
  const plan = buildTaskImageOutputPlan({
    outputModality: "IMAGE",
    toolType: "TOOL",
    params: { count: 4 },
  })
  assert.equal(plan.count, 4)
  assert.equal(plan.layout, "parallel")
  assert.equal(plan.showMultiPreview, true)
})

test("inferSerialActiveSlot advances with percent", () => {
  assert.equal(inferSerialActiveSlot(10, 3), 0)
  assert.equal(inferSerialActiveSlot(70, 3), 2)
})

test("inferParallelSlotPercent staggers progress across parallel slots", () => {
  const first = inferParallelSlotPercent(13, 0, 3)
  const second = inferParallelSlotPercent(13, 1, 3)
  const third = inferParallelSlotPercent(13, 2, 3)
  assert.ok(first > second)
  assert.ok(second > third)
  assert.equal(inferParallelSlotPercent(100, 2, 3), 100)
})
