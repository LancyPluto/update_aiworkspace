import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("../../../docs/api/openapi.yml", import.meta.url), "utf8")

test("tool schemas expose multi-capability requirements without a digital-human tool type", () => {
  const toolSummary = source.match(/    ToolSummary:\n[\s\S]*?\n    ToolDetailApiResponse:/)?.[0] || ""
  const upsert = source.match(/    UpsertToolRequest:\n[\s\S]*?\n    UpdateToolFieldsRequest:/)?.[0] || ""

  assert.match(toolSummary, /requiredModelCapabilities:/)
  assert.match(toolSummary, /requiredModelCapabilities:[\s\S]*?items:\s*\n\s*type: string\s*\n\s*enum: \[TEXT_GENERATION, VISION_INPUT, IMAGE_GENERATION, VIDEO_GENERATION, TEXT_TO_SPEECH, SPEECH_TO_TEXT, MUSIC_GENERATION, AUDIO_GENERATION, EMBEDDING, RERANK\]/)
  assert.match(toolSummary, /executionHandler:[\s\S]*DIGITAL_HUMAN/)
  assert.doesNotMatch(toolSummary, /toolType:\s*\n\s*type:\s*string\s*\n\s*enum:[^\n]*DIGITAL_HUMAN/)
  assert.match(upsert, /requiredModelCapabilities:/)
  assert.match(upsert, /requiredModelCapabilities:[\s\S]*?items:\s*\n\s*type: string\s*\n\s*enum: \[TEXT_GENERATION, VISION_INPUT, IMAGE_GENERATION, VIDEO_GENERATION, TEXT_TO_SPEECH, SPEECH_TO_TEXT, MUSIC_GENERATION, AUDIO_GENERATION, EMBEDDING, RERANK\]/)
  assert.match(upsert, /executionHandler:/)
  assert.match(upsert, /templateCode:/)
})

test("unified API overview documents provider choices for configured and unconfigured vendors", () => {
  assert.match(source, /\/api\/admin\/v1\/unified-api\/overview:/)
  assert.match(source, /UnifiedApiOverviewApiResponse:/)
  assert.match(source, /UnifiedApiVendorGroup:[\s\S]*supportedProviders:/)
  assert.match(source, /UnifiedApiUnconfiguredVendor:[\s\S]*supportedProviders:/)
})
