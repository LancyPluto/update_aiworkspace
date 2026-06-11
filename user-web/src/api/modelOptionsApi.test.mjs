import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const apiUrl = new URL("./modelOptionsApi.ts", import.meta.url)
const typesUrl = new URL("./types.ts", import.meta.url)

test("model options api calls the public grouped endpoint with mode", async () => {
  const [api, types] = await Promise.all([
    readFile(apiUrl, "utf8"),
    readFile(typesUrl, "utf8"),
  ])

  assert.match(types, /export interface ModelOptionGroup/)
  assert.match(types, /export interface ModelOptionItem/)
  assert.match(types, /export interface ImageGenerationParameters/)
  assert.match(types, /imageParameters\?: ImageGenerationParameters/)
  assert.match(types, /export interface ModelOptionsResponse/)
  assert.match(api, /export async function fetchModelOptions/)
  assert.match(api, /"GET",\s*"\/api\/v1\/model-options"/)
  assert.match(api, /query:\s*\{\s*mode/)
})
