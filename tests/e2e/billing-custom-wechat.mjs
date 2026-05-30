import { chromium } from "playwright"

const BASE = process.env.USER_WEB_URL || "http://127.0.0.1:5173"

async function main() {
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  const log = (msg) => console.log(`[e2e] ${msg}`)

  try {
    await page.goto(`${BASE}/`, { waitUntil: "networkidle", timeout: 30000 })
    const loginOpen = page.getByRole("button", { name: /^登录$/ }).or(page.getByRole("link", { name: /^登录$/ }))
    await loginOpen.first().click({ timeout: 10000 })
    await page.waitForSelector(".login-modal", { state: "visible", timeout: 10000 })
    await page.locator(".login-modal a").filter({ hasText: "密码登录" }).click()
    await page.getByPlaceholder("请输入手机号/账号").fill("user1")
    await page.getByPlaceholder("请输入密码").fill("123456")
    await page.locator(".login-modal button.login-btn, .login-modal button[type='submit']").click()
    await page.waitForURL(/\/(marketplace|dashboard|agent|billing)/, { timeout: 20000 })
    log(`logged in -> ${page.url()}`)

    await page.goto(`${BASE}/billing`, { waitUntil: "networkidle" })
    await page.waitForSelector('input[placeholder="输入金额"]', { timeout: 15000 })
    await page.fill('input[placeholder="输入金额"]', "1")
    log("custom amount set to 1")

    const buyButtons = page.locator('button:has-text("立即购买")')
    const count = await buyButtons.count()
    await buyButtons.nth(count - 1).click()
    log("clicked custom card 立即购买")

    const wechatBtn = page.getByRole("button", { name: /微信扫码支付/ })
    await wechatBtn.waitFor({ state: "visible", timeout: 8000 })
    const wechatVisible = await wechatBtn.isVisible()
    const wechatEnabled = await wechatBtn.isEnabled()
    log(`WeChat option visible=${wechatVisible} enabled=${wechatEnabled}`)

    if (!wechatVisible || !wechatEnabled) {
      throw new Error("WeChat pay option not visible or not enabled in channel modal")
    }

    await wechatBtn.click()
    log("clicked WeChat pay")

    await page.waitForTimeout(2000)

    const errText = await page.locator("text=支付二维码生成失败").isVisible().catch(() => false)
    const channelModal = await page.locator("#payment-channel-title").isVisible().catch(() => false)
    const payTitle = await page.locator("#pay-modal-title").isVisible().catch(() => false)
    const qrImg = await page.locator('img[alt*="微信"]').isVisible().catch(() => false)
    const ordering = await page.locator("text=下单中").isVisible().catch(() => false)

    log(`after click: channelModal=${channelModal} payModal=${payTitle} qr=${qrImg} error=${errText} ordering=${ordering}`)

    if (errText) {
      const banner = await page.locator(".text-destructive").first().textContent().catch(() => "")
      throw new Error(`WeChat order failed: ${banner}`)
    }

    if (!payTitle && channelModal) {
      throw new Error("Still on channel modal after WeChat click (order may have failed silently)")
    }

    if (payTitle) {
      log("SUCCESS: pay modal opened after WeChat click")
      if (qrImg) log("SUCCESS: WeChat QR image visible")
      else log("NOTE: pay modal open but QR not yet visible (may still be loading)")
    }

    process.exitCode = 0
  } catch (e) {
    console.error("[e2e] FAILED:", e.message)
    await page.screenshot({ path: "tests/e2e/billing-custom-wechat-failure.png", fullPage: true }).catch(() => {})
    log("screenshot saved to tests/e2e/billing-custom-wechat-failure.png")
    process.exitCode = 1
  } finally {
    await browser.close()
  }
}

main()
