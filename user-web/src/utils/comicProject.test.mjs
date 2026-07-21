import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function loadModule() {
  const source = await readFile(new URL("./comicProject.ts", import.meta.url), "utf8")
  const injectable = source.replace(/import\s+type\s+\{[^}]+\}\s+from\s+["']@\/api\/comicProjectApi["']\s*/, "")
  const outputText = stripTypeScriptTypes(injectable, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const comic = await loadModule()

test("comic stage gates only open after their persisted confirmation", () => {
  assert.equal(comic.currentComicStage(null), "script")
  assert.equal(comic.currentComicStage({ scriptText: "正文", shots: [] }), "script")
  assert.equal(comic.currentComicStage({ scriptText: "正文", shots: [{ sequenceNo: 1 }] }), "storyboard")
  assert.equal(comic.currentComicStage({ storyboardLocked: true }), "assets")
  assert.equal(comic.currentComicStage({ storyboardLocked: true, assetsConfirmed: true }), "shots")
  assert.equal(comic.currentComicStage({ assetsConfirmed: true, latestGenerationBatch: { status: "SUCCESS" } }), "delivery")
  assert.equal(comic.isComicStageAccessible("assets", { storyboardLocked: false }), false)
  assert.equal(comic.isComicStageAccessible("shots", { storyboardLocked: true, assetsConfirmed: false }), false)
})

test("episode generation polling only remains active for asynchronous workflow statuses", () => {
  assert.equal(comic.comicEpisodeGenerationStatus("SCRIPT_GENERATING"), "SCRIPT_GENERATING")
  assert.equal(comic.comicEpisodeGenerationStatus("storyboard_generating"), "STORYBOARD_GENERATING")
  assert.equal(comic.comicEpisodeGenerationStatus("SCRIPT_READY"), null)
  assert.equal(comic.comicEpisodeGenerationStatus("STORYBOARD_FAILED"), null)
  assert.equal(comic.currentComicStage({ status: "STORYBOARD_GENERATING", scriptText: "正文" }), "storyboard")
})

test("comic workflow retries retain an id until their signed input changes", () => {
  let sequence = 0
  const createId = () => `request-${++sequence}`

  const first = comic.retainComicClientRequestAttempt(null, "script:v1", createId)
  const retry = comic.retainComicClientRequestAttempt(first, "script:v1", createId)
  const changed = comic.retainComicClientRequestAttempt(retry, "script:v2", createId)

  assert.equal(first.clientRequestId, "request-1")
  assert.equal(retry, first)
  assert.equal(changed.clientRequestId, "request-2")
})

test("storyboard validation and reordering preserve production constraints", () => {
  const shots = Array.from({ length: 6 }, (_, index) => ({
    id: index + 1,
    sequenceNo: index + 1,
    durationMs: 5_000,
    visualDescription: `镜头 ${index + 1}`,
  }))
  assert.deepEqual(comic.validateComicStoryboard(shots), {
    valid: true,
    errors: [],
    shotCount: 6,
    totalDurationMs: 30_000,
  })
  const moved = comic.moveComicShot(shots, 0, 2)
  assert.deepEqual(moved.map((shot) => shot.id), [2, 3, 1, 4, 5, 6])
  assert.deepEqual(moved.map((shot) => shot.sequenceNo), [1, 2, 3, 4, 5, 6])
  assert.equal(comic.validateComicStoryboard(shots.slice(0, 5)).valid, false)
})

test("attempt media tolerates stored JSON strings and nested result payloads", () => {
  assert.deepEqual(comic.comicAttemptMedia({ outputJson: '{"result":{"videoUrl":"/generated/shot.mp4"}}' }), {
    videoUrl: "/generated/shot.mp4",
    imageUrl: null,
    prompt: null,
  })
  assert.equal(comic.comicAttemptMedia({ outputJson: "not-json" }).videoUrl, null)
})

test("selected attempt survives when the latest retry batch only contains another version", () => {
  const selected = { id: 11, shotId: 7, attemptNo: 1, status: "SUCCESS" }
  const retry = { id: 12, shotId: 7, attemptNo: 2, status: "SUCCESS" }
  const unrelated = { id: 13, shotId: 8, attemptNo: 1, status: "SUCCESS" }
  const merged = comic.mergeComicShotAttempts(
    { id: 7, sequenceNo: 1, durationMs: 5_000, visualDescription: "shot", selectedAttempt: selected },
    [retry, unrelated, selected],
  )
  assert.deepEqual(merged.map((attempt) => attempt.id), [12, 11])
})
