import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const srcRoot = new URL("./", import.meta.url)
const readSource = (path) => readFile(new URL(path, srcRoot), "utf8")

test("membership profile contract exposes state and dates", async () => {
  const types = await readSource("api/types.ts")
  for (const field of [
    "membershipStatus",
    "membershipStartedAt",
    "membershipExpiresAt",
    "pendingMembershipOrderId",
  ]) {
    assert.match(types, new RegExp(field))
  }
})

test("recharge attempts use stable UUIDs and refresh membership after payment", async () => {
  for (const file of ["pages/Billing/RechargeSection.vue", "components/CreditRechargeModal.vue"]) {
    const source = await readSource(file)
    assert.match(source, /crypto\.randomUUID\(\)/)
    assert.doesNotMatch(source, /clientRequestId:[^\n]*Date\.now\(\)/)
    assert.match(source, /auth\.fetchCurrentUser/)
  }
})

test("billing membership purchase is disabled while active", async () => {
  const source = await readSource("pages/Billing/RechargeSection.vue")
  assert.match(source, /hasActiveMembership/)
  assert.match(source, /membershipExpiresAt/)
  assert.match(source, /:disabled="ordering \|\| hasActiveMembership"/)
})

test("billing uses structured package metadata and gift card orders without unit prices", async () => {
  const recharge = await readSource("pages/Billing/RechargeSection.vue")
  const gifts = await readSource("pages/Billing/GiftCardSection.vue")

  for (const field of ["tierCode", "billingCycle", "discountRate", "cycleDiscountRate"]) {
    assert.match(recharge, new RegExp(`pkg\\.${field}`))
  }
  assert.match(gifts, /pkg\.cardType/)
  assert.match(gifts, /pkg\.requiredMemberTier/)
  assert.doesNotMatch(gifts, /buyMemberPackage|RechargePackage/)
  assert.doesNotMatch(`${recharge}\n${gifts}`, /\/算力/)
  assert.match(recharge, /orderType:\s*['"]GIFT_CARD['"]/)
})

test("self-purchased member credit cards are gift-only in profile", async () => {
  const profile = await readSource("pages/Profile/Page.vue")
  assert.match(profile, /card\.cardType !== "MEMBER_CREDIT" \|\| card\.giftedFromUserId != null/)
  assert.match(profile, /canRedeemOwnedGiftCard\(card\) \? "使用" : "仅可赠送"/)
})
