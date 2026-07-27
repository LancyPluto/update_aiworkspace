---
status: accepted
---

# 按真实运营方界定模型厂商

模型接入以签发凭据并承担计费、余额和支持责任的真实运营方作为 Vendor；接口兼容性属于 Protocol，官方直连或第三方网关属于 Channel Kind，`upstreamVendor` 仅用于表达上游品牌和公开生态分组。我们拒绝把使用 OpenAI 兼容协议的第三方网关并入 OpenAI，因为其账户、账单、余额、支持和故障责任均由网关运营方承担；也拒绝使用 `openai_gateway` 这类渠道描述作为虚拟 Vendor，oFox 应归为 `ofox`。

历史 `openai_gateway` 账户只有在确认真实运营方后才能迁移；无法识别时中止迁移并要求人工指定，不猜测为 OpenAI，也不放入兜底虚拟厂商。
