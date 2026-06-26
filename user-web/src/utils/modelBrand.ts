import type { AITool } from "@/api/aiToolTypes"

export interface ModelBrand {
  name: string
  iconUrl: string
  color: string
}

type ModelBrandInput = Pick<
  AITool,
  "id" | "name" | "iconUrl" | "modelIconUrl" | "modelConfigName" | "modelName" | "primaryColor"
>

const BRAND_RULES: Array<{ patterns: string[]; brand: ModelBrand }> = [
  {
    patterns: ["deepseek"],
    brand: { name: "DeepSeek", iconUrl: "https://www.deepseek.com/favicon.ico", color: "#4d6bfe" },
  },
  {
    patterns: ["doubao", "seedance", "seedream", "volcengine"],
    brand: { name: "Doubao", iconUrl: "https://www.doubao.com/favicon.ico", color: "#4f46e5" },
  },
  {
    patterns: ["qwen", "tongyi", "aliyun"],
    brand: { name: "Qwen", iconUrl: "https://tongyi.aliyun.com/favicon.ico", color: "#615ced" },
  },
  {
    patterns: ["glm", "zhipu", "chatglm"],
    brand: { name: "GLM", iconUrl: "https://chatglm.cn/favicon.ico", color: "#2563eb" },
  },
  {
    patterns: ["kimi", "moonshot"],
    brand: { name: "Kimi", iconUrl: "https://kimi.moonshot.cn/favicon.ico", color: "#111827" },
  },
  {
    patterns: ["ernie", "wenxin", "baidu"],
    brand: { name: "ERNIE", iconUrl: "https://yiyan.baidu.com/favicon.ico", color: "#1d4ed8" },
  },
  {
    patterns: ["hunyuan", "tencent"],
    brand: { name: "Hunyuan", iconUrl: "https://hunyuan.tencent.com/favicon.ico", color: "#2563eb" },
  },
  {
    patterns: ["minimax"],
    brand: { name: "MiniMax", iconUrl: "https://www.minimaxi.com/favicon.ico", color: "#0f172a" },
  },
  {
    patterns: ["kling"],
    brand: { name: "Kling", iconUrl: "https://app.klingai.com/favicon.ico", color: "#111827" },
  },
  {
    patterns: ["vidu"],
    brand: { name: "Vidu", iconUrl: "/assets/vendor-icons/vidu.svg", color: "#4f46e5" },
  },
  {
    patterns: ["agnes"],
    brand: { name: "Agnes AI", iconUrl: "/assets/vendor-icons/agnes.svg", color: "#ff2f6d" },
  },
  {
    patterns: ["siliconflow"],
    brand: { name: "SiliconFlow", iconUrl: "https://siliconflow.cn/favicon.ico", color: "#111827" },
  },
  {
    patterns: ["infinitetalk", "meigen"],
    brand: { name: "InfiniteTalk", iconUrl: "https://github.com/MeiGen-AI.png", color: "#111827" },
  },
  {
    patterns: ["yi-lightning", "01.ai", "lingyi"],
    brand: { name: "Yi", iconUrl: "https://www.lingyiwanwu.com/favicon.ico", color: "#111827" },
  },
  {
    patterns: ["stepfun", "step-"],
    brand: { name: "StepFun", iconUrl: "https://www.stepfun.com/favicon.ico", color: "#111827" },
  },
  {
    patterns: ["openai", "gpt"],
    brand: { name: "OpenAI", iconUrl: "https://cdn.simpleicons.org/openai/111827", color: "#111827" },
  },
  {
    patterns: ["claude", "anthropic"],
    brand: { name: "Anthropic", iconUrl: "https://cdn.simpleicons.org/anthropic/111827", color: "#111827" },
  },
]

function searchText(tool: ModelBrandInput): string {
  return [tool.modelConfigName, tool.modelName, tool.name, tool.id].filter(Boolean).join(" ").toLowerCase()
}

export function resolveModelBrand(tool: ModelBrandInput): ModelBrand {
  if (tool.modelIconUrl) {
    return {
      name: tool.modelConfigName || tool.modelName || tool.name,
      iconUrl: tool.modelIconUrl,
      color: tool.primaryColor || "#2563eb",
    }
  }

  const text = searchText(tool)
  const matched = BRAND_RULES.find((rule) => rule.patterns.some((pattern) => text.includes(pattern.toLowerCase())))
  if (matched) return matched.brand

  if (tool.iconUrl) {
    return {
      name: tool.modelConfigName || tool.modelName || tool.name,
      iconUrl: tool.iconUrl,
      color: tool.primaryColor || "#2563eb",
    }
  }

  return {
    name: tool.modelConfigName || tool.modelName || "WLCloud AI",
    iconUrl: "https://cdn.wlcloudai.com/static/logo.svg",
    color: tool.primaryColor || "#2563eb",
  }
}
