import type { RechargeOrder } from "@/api/types"

export type RechargePaymentChannel = "WECHAT_NATIVE" | "ALIPAY_PAGE" | "MOCK"

export function isAlipayPageRedirectOrder(
  order: RechargeOrder,
  channel?: RechargePaymentChannel,
): boolean {
  const paymentChannel = (order.paymentChannel ?? channel ?? "").toUpperCase()
  if (paymentChannel !== "ALIPAY_PAGE") return false
  const payUrl = order.payUrl?.trim()
  if (!payUrl) return false
  return payUrl.includes("/pay/alipay/page/launch") || payUrl.includes("alipay/page/launch")
}

export function resolveAlipayLaunchUrl(payUrl: string) {
  const normalized = payUrl.trim()
  return normalized.startsWith("http") ? normalized : `${window.location.origin}${normalized}`
}

export function rechargePaymentFailureMessage(
  order: RechargeOrder,
  channel: RechargePaymentChannel,
): string {
  if (channel === "ALIPAY_PAGE") {
    if (order.status === "FAILED" && order.statusReason) {
      return order.statusReason
    }
    return "支付宝收银台链接生成失败，请确认后端已配置 ALIPAY_PAY_MODE=PAGE 且支付宝应用已启用"
  }
  if (channel === "WECHAT_NATIVE") {
    return "微信收款码生成失败，请检查微信支付配置或稍后重试"
  }
  return "支付二维码生成失败，请检查支付配置或稍后重试"
}
