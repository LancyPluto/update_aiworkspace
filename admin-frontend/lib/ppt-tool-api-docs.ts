import type { ToolApiReference } from "@/components/admin/api-reference-card"

/**
 * banana-slides 引擎内除「文本 / 文生图大模型」外，PPT 链路常见依赖的第三方 API。
 * 与「PPT 引擎」模型绑定（超市同步）互补：此处为 MinerU / 百度等在引擎侧配置的说明。
 */
export const PPT_EXTERNAL_API_REFERENCE_LIST: ToolApiReference[] = [
  {
    title: "MinerU（PDF / Office 版式解析）",
    provider: "MinerU 服务（banana-slides 内嵌调用）",
    model: "layout / 文档解析",
    endpoint: "引擎按 MinerU 协议上传与轮询解析结果",
    baseUrl: "settings.mineru_api_base 或环境变量 MINERU_API_BASE",
    fields: [
      "mineru_token：调用凭证（settings.mineru_token / MINERU_TOKEN）",
      "典型场景：参考文档导入、翻新流程中按页拆分 PDF 后的内容抽取",
    ],
    note: "该类密钥与基地址在 banana-slides 应用设置（PUT /api/settings）或部署环境变量中维护；当前平台仅从本页同步「文本 / 生图」大模型，MinerU 请在引擎侧配置。",
    docUrl: "https://github.com/opendatalab/MinerU",
  },
  {
    title: "百度通用文字识别（高精度含位置版）",
    provider: "百度智能云 OCR",
    model: "accurate",
    endpoint: "POST https://aip.baidubce.com/rest/2.0/ocr/v1/accurate",
    baseUrl: "https://aip.baidubce.com",
    fields: [
      "baidu_api_key：BCE v3 API Key 或兼容 Access Token 模式（见引擎实现）",
      "用于导出可编辑 PPTX、样式/文字区域提取等需要「高精度整图 OCR」的步骤",
    ],
    note: "对应 banana-slides 中的百度高精度 OCR Provider；密钥写入引擎 settings.baidu_api_key。",
    docUrl: "https://ai.baidu.com/ai-doc/OCR/1k3h7y3db",
  },
  {
    title: "百度图像内容理解 / 修复（可选）",
    provider: "百度智能云（图像类）",
    model: "依导出/翻新配置",
    endpoint: "依 banana-slides 导出 inpaint 策略（如百度修复）",
    baseUrl: "与 OCR 共用 baidu_api_key 或独立配置（见引擎）",
    fields: ["部分导出/背景修复管线可选择百度图像能力"],
    note: "是否启用取决于项目在 banana-slides 内的导出选项；与高精度 OCR 同属百度侧凭证体系。",
    docUrl: "https://ai.baidu.com/tech/imageprocess/inpaint",
  },
]
