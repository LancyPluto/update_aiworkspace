import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const apiUrl = new URL("./modelOptionsApi.ts", import.meta.url)
const typesUrl = new URL("./types.ts", import.meta.url)

function interfaceFields(source, name) {
  const match = source.match(new RegExp(`export interface ${name}\\s*\\{([\\s\\S]*?)\\n\\}`))
  assert.ok(match, `${name} interface should exist`)
  return [...match[1].matchAll(/^\s*([A-Za-z][A-Za-z0-9]*)\??:/gm)].map((field) => field[1])
}

test("model options api calls the public grouped endpoint with mode", async () => {
  const [api, types] = await Promise.all([
    readFile(apiUrl, "utf8"),
    readFile(typesUrl, "utf8"),
  ])

  assert.deepEqual(interfaceFields(types, "ModelOptionItem"), [
    "id",
    "displayName",
    "capabilities",
    "imageParameters",
    "isDefault",
  ])
  assert.deepEqual(interfaceFields(types, "ModelOptionGroup"), [
    "vendorCode",
    "vendorName",
    "iconUrl",
    "models",
  ])
  assert.match(types, /export interface ImageGenerationParameters/)
  assert.match(types, /imageParameters: ImageGenerationParameters \| null/)
  assert.match(types, /export interface ModelOptionsResponse/)
  assert.match(api, /export async function fetchModelOptions/)
  assert.match(api, /"GET",\s*"\/api\/v1\/model-options"/)
  assert.match(api, /query:\s*\{\s*mode/)
})
