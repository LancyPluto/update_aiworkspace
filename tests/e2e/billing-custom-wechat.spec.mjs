/**
 * Manual E2E: custom recharge -> WeChat pay option visible and clickable.
 * Run: npx playwright test tests/e2e/billing-custom-wechat.spec.mjs --headed
 */
import { test, expect } from "@playwright/test"

const BASE = process.env.USER_WEB_URL || "http://127.0.0.1:5173"

test("custom recharge card shows WeChat pay and opens pay modal", async ({ page }) => {
  await page.goto(`${BASE}/`)
  await page.getByPlaceholder(/账号|手机|用户名/i).first().fill("user1")
  await page.getByPlaceholder(/密码/i).first().fill("123456")
  await page.getByRole("button", { name: /登录|登 录/i }).click()
  await page.waitForURL(/\/(marketplace|dashboard|agent|billing)/, { timeout: 15000 })

  await page.goto(`${BASE}/billing`)
  await expect(page.getByRole("heading", { name: /会员与算力|算力/i }).first()).toBeVisible({ timeout: 10000 })

  const customInput = page.getByPlaceholder("输入金额")
  await expect(customInput).toBeVisible()
  await customInput.fill("1")

  const customBuy = page.locator("button", { hasText: "立即购买" }).last()
  await expect(customBuy).toBeEnabled()
  await customBuy.click()

  const wechatBtn = page.getByRole("button", { name: /微信扫码支付/ })
  await expect(wechatBtn).toBeVisible({ timeout: 5000 })
  await expect(wechatBtn).toBeEnabled()
  await wechatBtn.click()

  await expect(page.getByRole("dialog").filter({ hasText: /扫码支付|微信/ })).toBeVisible({ timeout: 20000 })

  const errBanner = page.locator("text=支付二维码生成失败")
  const qr = page.locator('img[alt*="微信"]')
  const payPending = page.getByText("二维码生成中")

  await expect
    .poll(
      async () => {
        if (await errBanner.isVisible().catch(() => false)) return "error"
        if (await qr.isVisible().catch(() => false)) return "qr"
        if (await payPending.isVisible().catch(() => false)) return "pending"
        return "waiting"
      },
      { timeout: 20000 },
    )
    .not.toBe("waiting")

  const state = await (async () => {
    if (await errBanner.isVisible().catch(() => false)) return "error"
    if (await qr.isVisible().catch(() => false)) return "qr"
    return "pending"
  })()

  if (state === "error") {
    const msg = await errBanner.textContent()
    test.info().annotations.push({ type: "wechat-pay", description: `WeChat option clicked but QR failed: ${msg}` })
  } else {
    test.info().annotations.push({ type: "wechat-pay", description: `Pay modal state after WeChat click: ${state}` })
  }

  expect(state).not.toBe("error")
})
