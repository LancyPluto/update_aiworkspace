import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("vendor balance warnings are based on a negative numeric balance", () => {
  assert.match(source, /function isNegativeBalance\(account: ModelVendorAccount\)/)
  assert.match(source, /account\.balanceAmount != null && account\.balanceAmount < 0/)
  assert.doesNotMatch(source, /balanceStatus === "LOW" \|\| .*balanceStatus === "SUSPECTED_INSUFFICIENT"/)
})

test("vendor rows do not render persistent balance query failures", () => {
  assert.doesNotMatch(source, /primaryAccount\.balanceErrorMessage \?/)
  assert.doesNotMatch(source, />查询失败</)
  assert.doesNotMatch(source, /余额查询失败：/)
})

test("account balance badges keep actionable states but omit a positive normal badge", () => {
  const badgeBlock = source.match(/function balanceStatusBadge[\s\S]*?function formatBalance/)?.[0] || ""

  assert.match(badgeBlock, />欠费</)
  assert.match(badgeBlock, />余额异常</)
  assert.match(badgeBlock, />待手填</)
  assert.match(badgeBlock, />仅外链</)
  assert.doesNotMatch(badgeBlock, />正常</)
})
