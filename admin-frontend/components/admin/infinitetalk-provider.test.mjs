import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

test("unified API keeps InfiniteTalk available as an unconfigured vendor", async () => {
  const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

  assert.doesNotMatch(source, /code\s*!==\s*["']infinitetalk["']/i)
  assert.doesNotMatch(source, /code\s*!==\s*["']infinite_talk["']/i)
})
