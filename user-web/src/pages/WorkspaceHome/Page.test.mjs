import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const pageUrl = new URL("./Page.vue", import.meta.url)

test("workspace home forwards selected model config id into create route", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /if \(state\.modelConfigId\) query\.modelConfigId = String\(state\.modelConfigId\)/)
  assert.match(page, /if \(state\.modelLabel\) query\.modelLabel = state\.modelLabel/)
})

test("workspace home marks image and video composer submissions for auto task creation", async () => {
  const page = await readFile(pageUrl, "utf8")

  assert.match(page, /if \(\["image", "video"\]\.includes\(state\.mode\)\) query\.autoSubmit = "1"/)
  assert.match(page, /void router\.push\(\{ path: "\/create", query \}\)/)
})
