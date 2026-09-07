# Vidu 模型链接清单

> 数据来源：[Vidu 官方 API 文档](https://platform.vidu.com/docs/llms.txt) 与 [Pricing 页](https://platform.vidu.com/docs/pricing)，整理对应 `docs/Vidu模型接入文档.md` 中提到的全部模型/能力。  
> 单价口径：`1 credit = $0.005 USD`；价格四舍五入向上取整。  
> Off-Peak 通常为常规价 50%，需在请求体里传 `off_peak: true`，部分模型不支持。

---

## 0. 全局控制台 / 余额 / 文档入口

| 类目 | 链接 |
| --- | --- |
| 海外平台首页 | <https://platform.vidu.com/> |
| 海外控制台（登录后） | <https://platform.vidu.com/dashboard> |
| API Keys 管理 | <https://platform.vidu.com/dashboard/api-keys> |
| **余额 / 充值 / 发票（Billing）** | <https://platform.vidu.com/dashboard/billing> |
| **用量明细（Usage）** | <https://platform.vidu.com/dashboard/usage> |
| 国内站首页 | <https://platform.vidu.cn/> |
| 国内站控制台 | <https://platform.vidu.cn/dashboard> |
| 文档索引（llms.txt） | <https://platform.vidu.com/docs/llms.txt> |
| 计费总表 | <https://platform.vidu.com/docs/pricing> |
| Function List（能力总览） | <https://platform.vidu.com/docs/function-list> |
| Model Map（模型矩阵） | <https://platform.vidu.com/docs/model-map> |
| 错误码 | <https://platform.vidu.com/docs/error-code> |
| 回调验签 | <https://platform.vidu.com/docs/callback-signature> |
| 商务对接 | <mailto:platform@vidu.studio> |

> 控制台子路径来自官方 Quickstart 描述（dashboard 下的 `API Keys` / `Billing` / `Usage` 标签页），如界面后续改版以登录后实际路径为准。

---

## 1. 视频生成 · Vidu Q3 系列

### 1.1 `viduq3-pro`

| 项 | 内容 |
| --- | --- |
| 支持能力 | text2video / img2video / start-end2video / reference2video |
| 时长 | text/img/start-end: 1–16s；reference2video: 3–16s |
| 分辨率 & 费用（常规 / Off-Peak） | 540P: 9 / 5 credits·s（$0.045 / $0.025）<br>720P: 20 / 10 credits·s（$0.10 / $0.05）<br>1080P: 24 / 12 credits·s（$0.12 / $0.06） |
| reference2video 单价 | 540P: 7/4；720P: 12/6；1080P: 15/7 credits·s |
| 文档（按能力） | [Text to Video](https://platform.vidu.com/docs/text-to-video) · [Image to Video](https://platform.vidu.com/docs/image-to-video) · [Start end to Video](https://platform.vidu.com/docs/start-end-to-video) · [Reference to Video](https://platform.vidu.com/docs/reference-to-video) |
| 控制台 / 余额 | [Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing) |

### 1.2 `viduq3-turbo`

| 项 | 内容 |
| --- | --- |
| 支持能力 | text2video / img2video / start-end2video / reference2video |
| 时长 | text/img/start-end: 1–16s；reference2video: 3–16s |
| 分辨率 & 费用（常规 / Off-Peak） | 540P: 7 / 4（$0.035 / $0.02）<br>720P: 11 / 6（$0.055 / $0.03）<br>1080P: 13 / 7（$0.065 / $0.035） |
| reference2video 单价 | 540P: 4/2；720P: 10/5；1080P: 13/7 credits·s |
| 文档 | [text2video](https://platform.vidu.com/docs/text-to-video) · [img2video](https://platform.vidu.com/docs/image-to-video) · [start-end2video](https://platform.vidu.com/docs/start-end-to-video) · [reference2video](https://platform.vidu.com/docs/reference-to-video) |
| 控制台 / 余额 | [Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing) |

### 1.3 `viduq3-pro-fast`（仅 img2video）

| 项 | 内容 |
| --- | --- |
| 时长 | 1–16s |
| 分辨率 & 费用（常规 / Off-Peak） | 720P: 20 / 10 credits·s（$0.10 / $0.05）<br>1080P: 25 / 13 credits·s（$0.125 / $0.065） |
| 文档 | [Image to Video](https://platform.vidu.com/docs/image-to-video) |
| 控制台 / 余额 | [Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing) |

### 1.4 `viduq3-mix`（仅 reference2video）

| 项 | 内容 |
| --- | --- |
| 时长 | 1–16s |
| 分辨率 & 费用 | 720P: 24 credits·s（$0.12，**不支持 Off-Peak**）<br>1080P: 29 credits·s（$0.145，**不支持 Off-Peak**） |
| 文档 | [Reference to Video](https://platform.vidu.com/docs/reference-to-video) |
| 控制台 / 余额 | [Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing) |

### 1.5 `viduq3`（reference2video，基础档）

| 项 | 内容 |
| --- | --- |
| 时长 | 3–16s |
| 费用（常规 / Off-Peak） | 540P: 7/4；720P: 12/6；1080P: 15/7 credits·s |
| 文档 | [Reference to Video](https://platform.vidu.com/docs/reference-to-video) |
| 控制台 / 余额 | [Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing) |

---

## 2. 视频生成 · Vidu Q2 系列（起步价 + 每秒增量）

> 通用规则：img2video / reference2video 启用 `audio=true` **额外叠加 +15 credits**。

### 2.1 `viduq2-turbo`

| 能力 | 分辨率 | 计费 |
| --- | --- | --- |
| img2video / start-end2video | 540P | 起步 6 credits（$0.03），+2 credits/s |
| img2video / start-end2video | 720P | 起步 8 → 第 2 秒到 10，之后 +10 credits/s |
| img2video / start-end2video | 1080P | 起步 35 credits（$0.175），+10 credits/s |
| Video Extension | 540P / 720P / 1080P | 起步 10/15/40，每秒 +2/+5/+10 |

文档：[Image to Video](https://platform.vidu.com/docs/image-to-video) · [Start end to Video](https://platform.vidu.com/docs/start-end-to-video) · [Video Extension](https://platform.vidu.com/docs/video-extension) · [Multi-Frame](https://platform.vidu.com/docs/multi-frame)  
控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

### 2.2 `viduq2-pro`

| 能力 | 分辨率 | 计费 |
| --- | --- | --- |
| img2video / start-end2video | 540P | 起步 8 → 第 2 秒 10，+5/s |
| img2video / start-end2video | 720P | 起步 15（$0.075），+10/s |
| img2video / start-end2video | 1080P | 起步 55（$0.275），+15/s |
| reference2video | 540P / 720P / 1080P | 起步 20/30/85，每秒 +5/+5/+10 |
| Video Extension | 540P / 720P / 1080P | 起步 15/30/60，每秒 +5/+10/+15 |

文档：[Image to Video](https://platform.vidu.com/docs/image-to-video) · [Start end to Video](https://platform.vidu.com/docs/start-end-to-video) · [Reference to Video](https://platform.vidu.com/docs/reference-to-video) · [Video Extension](https://platform.vidu.com/docs/video-extension)  
控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

### 2.3 `viduq2-pro-fast`（仅 img2video / start-end2video）

| 分辨率 | 计费 |
| --- | --- |
| 720P | 起步 8 credits（$0.04），+2/s |
| 1080P | 起步 16 credits（$0.08），+4/s |

文档：[Image to Video](https://platform.vidu.com/docs/image-to-video) · [Start end to Video](https://platform.vidu.com/docs/start-end-to-video)  
控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

### 2.4 `viduq2`（text2video / reference2video / 图像 / 音频）

| 能力 | 分辨率 | 计费 |
| --- | --- | --- |
| text2video | 540P / 720P / 1080P | 起步 10/15/20，每秒 +2/+5/+10 |
| reference2video | 540P / 720P / 1080P | 起步 15/25/75，每秒 +5/+5/+10 |
| text2image | 1080P / 2K / 4K | 6 / 8 / 10 credits/张 |
| reference2image（1–3 张） | 1080P / 2K / 4K | 8 / 12 / 20 |
| reference2image（4–7 张） | 1080P / 2K / 4K | 10 / 16 / 30 |

文档：[Text to Video](https://platform.vidu.com/docs/text-to-video) · [Reference to Video](https://platform.vidu.com/docs/reference-to-video) · [Reference to Image](https://platform.vidu.com/docs/reference-to-image)  
控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 3. 视频生成 · Vidu Q1 系列

固定档：**5s / 1080P / 80 credits（$0.4）；Off-Peak 40 credits（$0.2）**。

| 模型 | 支持能力 | 文档 |
| --- | --- | --- |
| `viduq1` | text2video（general/anime）· img2video · start-end2video · reference2video | [text2video](https://platform.vidu.com/docs/text-to-video) · [img2video](https://platform.vidu.com/docs/image-to-video) · [start-end2video](https://platform.vidu.com/docs/start-end-to-video) · [reference2video](https://platform.vidu.com/docs/reference-to-video) |
| `viduq1-classic` | img2video · start-end2video | [img2video](https://platform.vidu.com/docs/image-to-video) · [start-end2video](https://platform.vidu.com/docs/start-end-to-video) |
| `viduq1` reference2image | 1080P（1–7 张）20 credits/张 | [Reference to Image](https://platform.vidu.com/docs/reference-to-image) |

控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 4. 视频生成 · Vidu 2.0

| 能力 | 时长 | 分辨率 | Credits（常规 / Off-Peak） |
| --- | --- | --- | --- |
| img2video | 4s | 360P / 720P / 1080P | 20/10 · 40/20 · 100/50 |
| img2video | 8s | 720P | 100 / 50 |
| reference2video | 4s | 360P / 720P | 80/40 · 80/40 |
| start-end2video | 4s | 360P / 720P / 1080P | 20/10 · 40/20 · 100/50 |
| start-end2video | 8s | 720P | 100 / 50 |

文档：[Image to Video](https://platform.vidu.com/docs/image-to-video) · [Reference to Video](https://platform.vidu.com/docs/reference-to-video) · [Start end to Video](https://platform.vidu.com/docs/start-end-to-video)  
控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 5. 音频类

| 模型 / 能力 | 计费 | 文档 |
| --- | --- | --- |
| Audio 1.0 · text2audio | <5s: 10 credits（$0.05）；<10s: 20 credits（$0.1） | [Text to Audio](https://platform.vidu.com/docs/text-to-audio) |
| Audio 1.0 · timing2audio | <5s: 10；<10s: 20 | [Timing to Audio](https://platform.vidu.com/docs/timing-to-audio) |
| Text to Speech (TTS) | 1 credit / 500 字符（$0.05） | [Text to Speech](https://platform.vidu.com/docs/text-to-speech) |
| Voice Clone | 30 credits / voice（$1.5） | [Voice Clone](https://platform.vidu.com/docs/voice-clone) |

控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 6. 视频处理增强类

| 能力 | 计费 | 文档 |
| --- | --- | --- |
| Upscale Pro | 1080P / 2K / 4K / 8K = 10 / 20 / 40 / 160 credits·s | [Upscale Pro](https://platform.vidu.com/docs/upscale-pro) |
| Lip Sync | 每 5s 收 20 credits | [Lip Sync](https://platform.vidu.com/docs/lip-sync) |
| Motion Sync | 10 credits / s | [Motion Sync](https://platform.vidu.com/docs/motion-sync) |
| Digital Human · Q2-turbo | 视频 120 credits；+Lip-sync 20/5s；+TTS 10/500 字 | [Digital Human](https://platform.vidu.com/docs/digital-human) |
| Digital Human · Q2-pro | 视频 200 credits；+Lip-sync 20/5s；+TTS 10/500 字 | [Digital Human](https://platform.vidu.com/docs/digital-human) |
| Prompt 推荐 | 10 credits / 5 prompts | [Prompt Rec](https://platform.vidu.com/docs/prompt-rec) |
| Video Extension | 见上表（Q2-turbo / Q2-pro） | [Video Extension](https://platform.vidu.com/docs/video-extension) |
| Smart Multi-Frame | 与对应分辨率/时长的 Q2 单段单价对齐，最多 9 段 | [Multi-Frame](https://platform.vidu.com/docs/multi-frame) |

控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 7. Solutions（一键成片）

| 模板 | 计费 | 文档 |
| --- | --- | --- |
| One Click General Film | 1080P 40 credits/s（$0.2/s）；Edit Footage 同价；Edit Narration 10c/500 字；Edit BGM 2c/s；Compose 1c/call | [One Click General Film](https://platform.vidu.com/docs/one-click-general-film) |
| One Click AD-Film | 同 General Film 结构（1080P 40c/s） | [One Click AD-Film](https://platform.vidu.com/docs/one-click-ad-film) |
| One Click Trending Replicate | 540/720/1080P = 12 / 16 / 20 credits/s | [One Click Trending Replicate](https://platform.vidu.com/docs/one-click-trending-replicate) |
| One Click AI-MV | 540/720/1080P = 6 / 8 / 10 credits/s；Compose 1c/call | [One Click AI-MV](https://platform.vidu.com/docs/one-click-ai-mv) |

控制台 / 余额：[Dashboard](https://platform.vidu.com/dashboard) · [Billing](https://platform.vidu.com/dashboard/billing)

---

## 8. 通用任务接口（所有模型共用）

| 接口 | 文档 |
| --- | --- |
| 任务详情（24h URL） | [Get Creation](https://platform.vidu.com/docs/get-generation) |
| 任务列表 | [Get Task List](https://platform.vidu.com/docs/tasks-list) |
| 取消任务 | [Cancel Generation](https://platform.vidu.com/docs/cancel-generation) |
| 账户信息 | [Get Account Info](https://platform.vidu.com/docs/get-account-info) |
| 图片上传 | [Image Upload](https://platform.vidu.com/docs/image-upload) |
| 错误码 | [Error Code](https://platform.vidu.com/docs/error-code) |
| 回调验签 | [Callback Signature](https://platform.vidu.com/docs/callback-signature) |
| MCP 概览 | [MCP Overview](https://platform.vidu.com/docs/mcp-overview) |
| 用量与限制 | [Usage and Limits](https://platform.vidu.com/docs/usage-and-limits) |
| 内容审核 | [Content Moderation](https://platform.vidu.com/docs/content-moderation) |

> 单价口径：1 credit = $0.005。所有费用四舍五入向上取整；img2video / reference2video 启用 `audio=true` 时额外 +15 credits。
