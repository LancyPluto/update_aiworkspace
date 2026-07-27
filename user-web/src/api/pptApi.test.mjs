import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function loadApi() {
  const source = await readFile(new URL("./pptApi.ts", import.meta.url), "utf8")
  const injectable = source
    .replace(
      /import\s+\{\s*apiRequest,\s*type\s+RequestOptions\s*\}\s+from\s+["']\.\/client["']\s*/,
      "const apiRequest = undefined\n",
    )
  const outputText = stripTypeScriptTypes(injectable, { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

test("PPT API uses the isolated v2 project and job contract", async () => {
  const calls = []
  const api = (await loadApi()).createPptApi(async (...args) => {
    calls.push(args)
    return { ok: true }
  })

  await api.status()
  await api.modelOptions()
  await api.listProjects(2, 12)
  await api.createProject({ title: "发布会", topic: "讲清增长逻辑" })
  await api.project("project/7")
  await api.modelBinding(7)
  await api.updateModelBinding(7, { textModelConfigId: 10, imageModelConfigId: 12 })
  await api.submitJob(7, { jobType: "GENERATE_OUTLINE", clientRequestId: "ppt-outline-1" })
  await api.job("job/8")
  await api.retryJob(8, "ppt-retry-1")
  await api.cancelJob(8)
  await api.exports(7)

  assert.deepEqual(calls.map(([method, path]) => [method, path]), [
    ["GET", "/api/v2/ppt/status"],
    ["GET", "/api/v2/ppt/model-options"],
    ["GET", "/api/v2/ppt/projects"],
    ["POST", "/api/v2/ppt/projects"],
    ["GET", "/api/v2/ppt/projects/project%2F7"],
    ["GET", "/api/v2/ppt/projects/7/model-binding"],
    ["PUT", "/api/v2/ppt/projects/7/model-binding"],
    ["POST", "/api/v2/ppt/projects/7/jobs"],
    ["GET", "/api/v2/ppt/jobs/job%2F8"],
    ["POST", "/api/v2/ppt/jobs/8/retry"],
    ["POST", "/api/v2/ppt/jobs/8/cancel"],
    ["GET", "/api/v2/ppt/projects/7/exports"],
  ])
  assert.deepEqual(calls[2][2].query, { pageNo: 2, pageSize: 12 })
  assert.deepEqual(calls[9][2].body, { clientRequestId: "ppt-retry-1" })
})

test("PPT API repairs only reversible historical UTF-8 mojibake", async () => {
  const { repairPptMojibake } = await loadApi()

  assert.equal(repairPptMojibake("ä¸ºä»ä¹è¦æ¥èå¤§å­¦"), "为什么要报考大学")
  assert.equal(repairPptMojibake("正常中文标题"), "正常中文标题")
  assert.equal(repairPptMojibake("Märchen and café"), "Märchen and café")
})

test("PPT project responses normalize historical slide text", async () => {
  const api = (await loadApi()).createPptApi(async () => ({
    projectId: 6,
    title: "1",
    topic: "topic",
    creationType: "idea",
    language: "zh-CN",
    aspectRatio: "16:9",
    pageCount: 1,
    status: "READY",
    engineStrategy: "banana",
    latestDeck: {
      slides: [{ title: "ä¸ºä»ä¹", description: "é¡µé¢æå­" }],
    },
    recentJobs: [],
    exports: [],
    createdAt: "2026-07-25T00:00:00Z",
    updatedAt: "2026-07-25T00:00:00Z",
  }))

  const project = await api.project(6)
  assert.equal(project.latestDeck.slides[0].title, "为什么")
  assert.equal(project.latestDeck.slides[0].description, "页面文字")
})
