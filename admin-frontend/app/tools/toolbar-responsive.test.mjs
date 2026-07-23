import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./page.tsx", import.meta.url), "utf8")

test("tool actions stack below search and wrap on narrow viewports", () => {
  assert.match(
    source,
    /className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4"/,
  )
  assert.match(source, /className="relative w-full min-w-0 sm:max-w-md sm:flex-1"/)
  assert.match(
    source,
    /className="flex w-full min-w-0 flex-wrap items-center gap-2 sm:w-auto sm:flex-nowrap"/,
  )
})

test("tool editor content can shrink without horizontal scrolling", () => {
  assert.match(
    source,
    /"max-h-\[92vh\] overflow-y-auto border-border bg-card \[&>\*\]:min-w-0"/,
  )
  const fullWidthSelectTriggers = [
    ...source.matchAll(/<SelectTrigger className="([^"]*\bw-full\b[^"]*)">/g),
  ]
  assert.ok(fullWidthSelectTriggers.length > 0)
  for (const [, className] of fullWidthSelectTriggers) {
    assert.match(className, /(?:^|\s)min-w-0(?:\s|$)/)
  }
})

test("tool cards use a fluid single column below the small breakpoint", () => {
  assert.match(
    source,
    /className="grid grid-cols-1 gap-4 sm:grid-cols-\[repeat\(auto-fill,minmax\(280px,1fr\)\)\]"/,
  )
})
