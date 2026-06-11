import assert from "node:assert/strict"
import test from "node:test"
import nextConfig from "./next.config.mjs"

test("admin proxy accepts tool cover uploads up to the backend request limit", () => {
  assert.equal(nextConfig.experimental?.proxyClientMaxBodySize, "30mb")
})

test("admin proxy forwards generated media previews to the backend", async () => {
  const rewrites = await nextConfig.rewrites()
  const apiTarget = process.env.NEXT_PUBLIC_API_PROXY_TARGET || "http://localhost:8080"
  assert.deepEqual(
    rewrites.find((rewrite) => rewrite.source === "/generated/:path*"),
    {
      source: "/generated/:path*",
      destination: `${apiTarget}/generated/:path*`,
      basePath: false,
    },
  )
})

test("admin build does not ignore TypeScript errors", () => {
  assert.notEqual(nextConfig.typescript?.ignoreBuildErrors, true)
})
