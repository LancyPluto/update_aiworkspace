import assert from "node:assert/strict"
import test from "node:test"
import {
  GIFT_CARD_MIN_DISCOUNT,
  MEMBER_GIFT_TIERS,
  MEMBER_TIERS,
  canBuyGiftCard,
  formatChineseDiscount,
  getCreditCardDiscount,
  getUserMemberLevel,
} from "./giftCardTierConfig.ts"

test("member tiers stay ordered from standard to flagship", () => {
  assert.deepEqual(MEMBER_TIERS.map((item) => item.key), ["starter", "growth", "pro", "flagship"])
  assert.deepEqual(MEMBER_GIFT_TIERS.map((item) => item.key), ["starter", "growth", "pro", "flagship"])
})

test("member gift discounts become better as tier rises", () => {
  const discounts = MEMBER_GIFT_TIERS.map((item) => item.giftDiscount)
  assert.deepEqual(discounts, [0.86, 0.8, 0.77, 0.75])
  for (let i = 1; i < discounts.length; i++) {
    assert.ok(discounts[i] <= discounts[i - 1], `discount at index ${i} should be no worse than the previous tier`)
  }
})

test("discounts use the normal Chinese tenths format", () => {
  assert.equal(formatChineseDiscount(0.99), "9.9折")
  assert.equal(formatChineseDiscount(0.975), "9.8折")
  assert.equal(formatChineseDiscount(0.8), "8折")
})

test("gift card discount respects the configured floor", () => {
  assert.equal(GIFT_CARD_MIN_DISCOUNT, 0.9)
  assert.equal(getCreditCardDiscount(18, 1000), 0.9)
})

test("member level parsing matches tier order", () => {
  assert.equal(getUserMemberLevel("monthly_starter"), 0)
  assert.equal(getUserMemberLevel("yearly_growth"), 1)
  assert.equal(getUserMemberLevel("yearly_pro"), 2)
  assert.equal(getUserMemberLevel("yearly_flagship"), 3)
  assert.equal(canBuyGiftCard(3, 1), true)
  assert.equal(canBuyGiftCard(1, 3), false)
})
