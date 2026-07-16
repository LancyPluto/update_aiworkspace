import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

test("admin layout uses a desktop-only sidebar offset and exposes mobile navigation", async () => {
  const [layout, header, sidebar, proxyPage] = await Promise.all([
    readFile(new URL("./admin-layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("./header.tsx", import.meta.url), "utf8"),
    readFile(new URL("./sidebar.tsx", import.meta.url), "utf8"),
    readFile(new URL("../../app/proxy-nodes/page.tsx", import.meta.url), "utf8"),
  ])

  assert.match(layout, /className="[^"]*\blg:ml-64\b[^"]*"/)
  assert.doesNotMatch(layout, /className="ml-64"/)
  assert.match(header, /<AdminMobileNavigation\s*\/>/)
  assert.match(sidebar, /export function AdminMobileNavigation/)
  assert.match(sidebar, /<SheetContent[^>]*side="left"/)
  assert.match(proxyPage, /space-y-5 p-4 sm:p-6/)
  assert.match(proxyPage, /TabsTrigger value="websites"/)
  assert.match(proxyPage, /TabsTrigger value="nodes"/)
  assert.doesNotMatch(proxyPage, /xl:grid-cols-\[minmax\(0,1fr\)_340px\]/)
})
