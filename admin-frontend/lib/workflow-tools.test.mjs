import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { test } from "node:test"
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

const { isToolAvailableToUsers, isWorkflowTool } = await importTsModule("./workflow-tools.ts")

test("model-only audio tools stay in large-model management", () => {
  assert.equal(isWorkflowTool({ toolCode: "suno_music", toolType: "MUSIC_GENERATION", outputModality: "AUDIO" }), false)
  assert.equal(isWorkflowTool({ toolCode: "tts_mm", toolType: "TEXT_TO_SPEECH", outputModality: "AUDIO" }), false)
  assert.equal(isWorkflowTool({ toolCode: "suno", toolType: "MUSIC_GENERATION", outputModality: "AUDIO" }), false)
})

test("true workflow agents remain classified as workflow tools", () => {
  assert.equal(isWorkflowTool({ toolCode: "ai_comic_drama_agent" }), true)
  assert.equal(isWorkflowTool({ toolCode: "digital_human_agent" }), true)
  assert.equal(isWorkflowTool({ toolCode: "banana_ppt_generator" }), true)
})

test("workflow availability prefers the backend runtime verdict over tool status", () => {
  assert.equal(isToolAvailableToUsers({ toolCode: "ai_comic_drama_agent", status: "ONLINE", workflowConfigured: true, workflowUsable: false }), false)
  assert.equal(isToolAvailableToUsers({ toolCode: "ai_comic_drama_agent", status: "ONLINE", workflowConfigured: true, workflowUsable: true }), true)
  assert.equal(isToolAvailableToUsers({ toolCode: "ai_comic_drama_agent", status: "ONLINE", workflowConfigured: false, workflowUsable: false }), true)
})

test("workflow availability supports transition fields and legacy responses", () => {
  assert.equal(isToolAvailableToUsers({
    toolCode: "ai_comic_drama_agent",
    status: "ONLINE",
    executionMode: "DIRECT",
    agentSurfaceEnabled: true,
    workflowExecutionEnabled: true,
    publishedWorkflowVersionId: 12,
  }), false)
  assert.equal(isToolAvailableToUsers({ toolCode: "ai_comic_drama_agent", status: "ONLINE" }), true)
  assert.equal(isToolAvailableToUsers({ toolCode: "suno_music", status: "ONLINE", workflowUsable: false }), true)
})
