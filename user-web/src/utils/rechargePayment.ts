import type { RechargeOrder } from "@/api/types"

export type RechargePaymentChannel = "WECHAT_NATIVE" | "ALIPAY_PAGE"

export interface RechargePaymentChannelOption {
  channel: RechargePaymentChannel
  title: string
  description: string
}

/** 充值页固定支付方式：微信扫码 + 支付宝电脑网站支付 */
export const DEFAULT_RECHARGE_PAYMENT_CHANNELS: RechargePaymentChannelOption[] = [
  {
    channel: "WECHAT_NATIVE",
    title: "微信扫码支付",
    description: "使用微信扫一扫完成付款",
  },
  {
    channel: "ALIPAY_PAGE",
    title: "支付宝电脑支付",
    description: "跳转支付宝官方收银台完成付款",
  },
]

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
    return "支付宝电脑支付链接生成失败，请确认支付宝应用已签约「电脑网站支付」且后端配置已启用"
  }
  if (channel === "WECHAT_NATIVE") {
    return "微信收款码生成失败，请检查微信支付配置或稍后重试"
  }
  return "支付二维码生成失败，请检查支付配置或稍后重试"
}
