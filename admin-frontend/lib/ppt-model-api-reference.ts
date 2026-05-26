import type { ToolApiReference } from "@/components/admin/api-reference-card"
import type { AgentModelConfig } from "@/lib/api/types"

function chatEndpointForProvider(provider: string): string {
  const p = (provider || "").trim().toLowerCase()
  if (p === "anthropic_compatible" || p === "anthropic") return "POST /v1/messages"
  return "POST /v1/chat/completions（或供应商兼容路径）"
}

export function buildPptTextModelReference(config: AgentModelConfig | null | undefined): ToolApiReference | null {
  if (!config) return null
  return {
    title: "文本大模型（大纲 / 描述等）",
    provider: config.provider,
    model: config.modelName,
    endpoint: chatEndpointForProvider(config.provider),
    baseUrl: config.baseUrl?.trim() || "由「系统配置 → 大模型接入」中该条目的 Base URL 决定",
    fields: [
      "绑定后随「保存并同步引擎」写入 banana-slides：text_model、text_model_source、text_api_key / lazyllm_api_keys 等",
    ],
    note: "密钥在超市模型配置中维护，本页仅选择绑定关系。",
  }
}

export function buildPptImageModelReference(config: AgentModelConfig | null | undefined): ToolApiReference | null {
  if (!config) return null
  return {
    title: "文生图模型（幻灯片配图）",
    provider: config.provider,
    model: config.modelName,
    endpoint: "POST /v1/images/generations（或火山 Ark / 其他图像网关）",
    baseUrl: config.baseUrl?.trim() || "由绑定模型配置决定",
    fields: [
      "绑定后同步至 banana-slides：image_model、image_model_source、image_api_key 等",
    ],
    note: "需选用具备「文生图」能力的模型配置（如 volcengine_images、siliconflow_images）。",
  }
}
