import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const stageView = await importTsModule("./pptStageViewState.ts")

function job(jobId, jobType, status, updatedAt, extra = {}) {
  return {
    jobId,
    projectId: 1,
    jobType,
    status,
    progress: status === "SUCCEEDED" ? 100 : 0,
    engineCode: "BANANA_SLIDES",
    creditState: "SETTLED",
    reservedCredits: 10,
    actualCredits: 10,
    retryable: status === "FAILED",
    createdAt: updatedAt,
    updatedAt,
    finishedAt: updatedAt,
    ...extra,
  }
}

function project(overrides = {}) {
  return {
    projectId: 1,
    title: "Test",
    topic: "Test",
    creationType: "idea",
    language: "zh-CN",
    aspectRatio: "16:9",
    pageCount: 3,
    status: "READY",
    engineStrategy: "VISUAL",
    latestDeck: null,
    recentJobs: [],
    exports: [],
    createdAt: "2026-07-24T10:00:00",
    updatedAt: "2026-07-24T10:00:00",
    ...overrides,
  }
}

test("an untouched project starts with every stage not started", () => {
  const states = stageView.derivePptStageStates(project())
  for (const type of stageView.PPT_STAGE_TYPES) {
    assert.equal(states[type].status, "NOT_STARTED")
  }
})

test("available content remains usable when the latest regeneration fails", () => {
  const states = stageView.derivePptStageStates(project({
    latestDeck: { slides: [{ title: "One", description: "Body" }] },
    recentJobs: [
      job(2, "GENERATE_DESCRIPTIONS", "FAILED", "2026-07-24T12:00:00", {
        error: { code: "ENGINE_FAILED", message: "Description generation failed for 3/3 pages" },
      }),
      job(1, "GENERATE_DESCRIPTIONS", "SUCCEEDED", "2026-07-24T11:00:00"),
    ],
  }))
  assert.equal(states.GENERATE_DESCRIPTIONS.status, "AVAILABLE_WITH_WARNING")
  assert.equal(states.GENERATE_DESCRIPTIONS.artifactAvailable, true)
  assert.equal(
    stageView.pptStageErrorSummary(states.GENERATE_DESCRIPTIONS.latestAttempt),
    "页面描述生成失败（3/3 页）",
  )
})

test("a later success supersedes an older failed attempt", () => {
  const states = stageView.derivePptStageStates(project({
    latestDeck: { slides: [{ title: "One", description: "Body" }] },
    recentJobs: [
      job(3, "GENERATE_DESCRIPTIONS", "SUCCEEDED", "2026-07-24T13:00:00"),
      job(2, "GENERATE_DESCRIPTIONS", "FAILED", "2026-07-24T12:00:00"),
    ],
  }))
  assert.equal(states.GENERATE_DESCRIPTIONS.status, "AVAILABLE")
})

test("a failed stage without an older artifact is failed", () => {
  const states = stageView.derivePptStageStates(project({
    recentJobs: [job(2, "GENERATE_IMAGES", "FAILED", "2026-07-24T12:00:00")],
  }))
  assert.equal(states.GENERATE_IMAGES.status, "FAILED")
})

test("engine failures use actionable Chinese summaries", () => {
  const unavailable = job(3, "GENERATE_IMAGES", "FAILED", "2026-07-24T12:00:00", {
    error: { code: "PPT_ENGINE_UNAVAILABLE", message: "Connection refused" },
  })
  const interrupted = job(4, "GENERATE_IMAGES", "FAILED", "2026-07-24T12:01:00", {
    error: { code: "ENGINE_INTERRUPTED", message: "process restarted" },
  })

  assert.equal(
    stageView.pptStageErrorSummary(unavailable),
    "PPT 引擎暂时不可用，请稍后重试",
  )
  assert.equal(
    stageView.pptStageErrorSummary(interrupted),
    "生成服务重启导致任务中断，可从本阶段重试",
  )
})

test("a downstream artifact becomes stale after a newer upstream success", () => {
  const states = stageView.derivePptStageStates(project({
    latestDeck: { slides: [{ title: "One", description: "New", previewUrl: "/generated/old.png" }] },
    recentJobs: [
      job(4, "GENERATE_DESCRIPTIONS", "SUCCEEDED", "2026-07-24T14:00:00"),
      job(3, "GENERATE_IMAGES", "SUCCEEDED", "2026-07-24T13:00:00"),
      job(2, "GENERATE_OUTLINE", "SUCCEEDED", "2026-07-24T12:00:00"),
    ],
  }))
  assert.equal(states.GENERATE_IMAGES.status, "STALE")
})

test("the preferred view opens the deepest inspectable content, not export", () => {
  const states = stageView.derivePptStageStates(project({
    latestDeck: { slides: [{ title: "One", description: "Body", previewUrl: "/generated/one.png" }] },
    exports: [{
      exportId: 1,
      projectId: 1,
      exportType: "PPTX",
      status: "READY",
      createdAt: "2026-07-24T14:00:00",
    }],
  }))
  assert.equal(stageView.preferredPptStage(states), "GENERATE_IMAGES")
})
