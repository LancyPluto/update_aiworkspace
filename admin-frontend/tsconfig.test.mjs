import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

test("typecheck excludes transient Next dev generated types", async () => {
  const tsconfig = JSON.parse(await readFile(new URL("./tsconfig.json", import.meta.url), "utf8"))

  assert.ok(tsconfig.include.includes(".next/types/**/*.ts"))
  assert.ok(tsconfig.exclude.includes(".next/dev"))
})
