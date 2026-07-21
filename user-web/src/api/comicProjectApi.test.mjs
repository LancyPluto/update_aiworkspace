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

test("comic workflow launch APIs forward required client request ids", async () => {
  const calls = []
  const api = (await loadApi()).createComicProjectApi(async (...args) => {
    calls.push(args)
    return { id: 9, projectId: 7, title: "第 1 集" }
  })

  await api.generateEpisode(7, {
    title: "雨夜来客",
    prompt: "少年在雨夜发现古城秘密",
    episodeNo: 1,
    clientRequestId: "script-request-1",
  })
  await api.generateStoryboard(7, 9, {
    expectedRevision: 3,
    clientRequestId: "storyboard-request-1",
  })

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["POST", "/api/v1/agents/comic-projects/7/episodes/generate"],
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/generate-storyboard"],
  ])
  assert.deepEqual(calls[0][2].body, {
    title: "雨夜来客",
    prompt: "少年在雨夜发现古城秘密",
    episodeNo: 1,
    clientRequestId: "script-request-1",
  })
  assert.deepEqual(calls[1][2].body, {
    expectedRevision: 3,
    clientRequestId: "storyboard-request-1",
  })
})

test("comic asset generation uses version-scoped character and scene routes", async () => {
  const calls = []
  const api = (await loadApi()).createComicProjectApi(async (...args) => {
    calls.push(args)
    return { ok: true }
  })

  await api.generateCharacterVersion("project/7", "character/2", "version/3", { clientRequestId: "character-request" })
  await api.generateSceneVersion("project/7", "scene/4", "version/5", { clientRequestId: "scene-request" })

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["POST", "/api/v1/agents/comic-projects/project%2F7/characters/character%2F2/versions/version%2F3/generate"],
    ["POST", "/api/v1/agents/comic-projects/project%2F7/scenes/scene%2F4/versions/version%2F5/generate"],
  ])
  assert.deepEqual(calls.map(([, , options]) => options.body), [
    { clientRequestId: "character-request" },
    { clientRequestId: "scene-request" },
  ])
})

test("comic assembly API sends only supported fields and normalizes final video URLs", async () => {
  const calls = []
  const backendBatch = {
    id: 31,
    episodeId: 9,
    status: "SUCCESS",
    rootTaskId: 42,
    selectedAttemptIds: [101, 102],
    finalVideoUrl: "https://cdn.example.com/final.mp4",
    subtitleUrl: "https://cdn.example.com/final.srt",
    result: { handlerKey: "comic.compose" },
  }
  const api = (await loadApi()).createComicProjectApi(async (...args) => {
    calls.push(args)
    return backendBatch
  })

  const created = await api.createAssemblyBatch(7, 9, {
    clientRequestId: "assembly-1",
    voiceEnabled: false,
    bgmEnabled: false,
    subtitlesEnabled: false,
    confirmed: true,
  })
  const loaded = await api.getAssemblyBatch(7, 9, 31)
  const latest = await api.getLatestAssemblyBatch(7, 9)

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["POST", "/api/v1/agents/comic-projects/7/episodes/9/assembly-batches"],
    ["GET", "/api/v1/agents/comic-projects/7/episodes/9/assembly-batches/31"],
    ["GET", "/api/v1/agents/comic-projects/7/episodes/9/assembly-batches/latest"],
  ])
  assert.deepEqual(calls[0][2].body, { clientRequestId: "assembly-1", confirmed: true })
  for (const batch of [created, loaded, latest]) {
    assert.equal(batch.videoUrl, backendBatch.finalVideoUrl)
    assert.equal(batch.rootTaskId, 42)
    assert.deepEqual(batch.selectedAttemptIds, [101, 102])
    assert.deepEqual(batch.result, { handlerKey: "comic.compose" })
  }
})

test("comic episode detail normalizes its embedded latest assembly batch", async () => {
  const api = (await loadApi()).createComicProjectApi(async () => ({
    id: 9,
    projectId: 7,
    title: "第 1 集",
    latestAssemblyBatch: {
      id: 31,
      episodeId: 9,
      status: "SUCCESS",
      finalVideoUrl: "https://cdn.example.com/final.mp4",
    },
  }))

  const episode = await api.getEpisode(7, 9)
  assert.equal(episode.latestAssemblyBatch.videoUrl, "https://cdn.example.com/final.mp4")
})

test("comic workspace binding resolves a workflow root task", async () => {
  const calls = []
  const api = (await loadApi()).createComicProjectApi(async (...args) => {
    calls.push(args)
    return { projectId: 7, episodeId: 9, rootTaskId: 42, workflowRunId: 43, workspacePath: "/agents/comic-projects/7?episode=9" }
  })

  const binding = await api.getWorkspaceByRun("task/42")

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["GET", "/api/v1/agents/comic-projects/by-run/task%2F42"],
  ])
  assert.equal(binding.workspacePath, "/agents/comic-projects/7?episode=9")
})
