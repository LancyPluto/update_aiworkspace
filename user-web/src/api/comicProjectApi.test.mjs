import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function loadApi() {
  const source = await readFile(new URL("./comicProjectApi.ts", import.meta.url), "utf8")
  const injectable = source.replace(
    /import\s+\{\s*apiRequest\s*\}\s+from\s+["']\.\/client["']\s*/,
    "const apiRequest = undefined\n",
  ).replace(/import\s+type\s+\{\s*RequestOptions\s*\}\s+from\s+["']\.\/client["']\s*/, "")
  const outputText = stripTypeScriptTypes(injectable, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

test("comic project API uses the stable project, episode, gate, asset and batch paths", async () => {
  const calls = []
  const api = (await loadApi()).createComicProjectApi(async (...args) => {
    calls.push(args)
    return { ok: true }
  })

  await api.list()
  await api.create({ title: "第一季", aspectRatio: "9:16" })
  await api.get("project/7")
  await api.createEpisode(7, { title: "第 1 集", scriptText: "剧本", sourceType: "PASTE" })
  await api.replaceShots(7, 9, { shots: [] })
  await api.lockStoryboard(7, 9, 3)
  await api.patchShotAssetRefs(7, 9, 12, { characterVersionIds: [2, 3], sceneVersionId: 4, expectedRevision: 5 })
  await api.confirmAssets(7, 9, 6)
  await api.createGenerationBatch(7, 9, { clientRequestId: "batch-1", maxParallelism: 4, confirmed: true })
  await api.dispatchGenerationBatch(7, 9, 22)
  await api.getGenerationBatch(7, 9, 22)
  await api.getLatestGenerationBatch(7, 9)
  await api.retryShot(7, 9, 12, { clientRequestId: "retry-1" })
  await api.selectShotAttempt(7, 9, 12, 18, 8)

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["GET", "/api/v1/agents/comic-projects"],
    ["POST", "/api/v1/agents/comic-projects"],
    ["GET", "/api/v1/agents/comic-projects/project%2F7"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes"],
    ["PUT", "/api/v1/agents/comic-projects/7/episodes/9/shots"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/lock-storyboard"],
    ["PATCH", "/api/v1/agents/comic-projects/7/episodes/9/shots/12/asset-refs"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/confirm-assets"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/generation-batches"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/generation-batches/22/dispatch"],
    ["GET", "/api/v1/agents/comic-projects/7/episodes/9/generation-batches/22"],
    ["GET", "/api/v1/agents/comic-projects/7/episodes/9/generation-batches/latest"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/shots/12/retry"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/shots/12/select-attempt"],
  ])
  assert.deepEqual(calls[6][2].body, { characterVersionIds: [2, 3], sceneVersionId: 4, expectedRevision: 5 })
  assert.deepEqual(calls[13][2].body, { attemptId: 18, expectedRevision: 8 })
})
