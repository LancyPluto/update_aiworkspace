# Vidu 模型 API 接入文档

> 整理自 Vidu 官方文档：[https://platform.vidu.com/docs/pricing](https://platform.vidu.com/docs/pricing) 及 API Reference。本文档面向本项目 Worker / 后端接入实现,聚焦计费规则、模型矩阵、调用协议三大块。

---

## 1. 概览


| 项             | 说明                                          |
| ------------- | ------------------------------------------- |
| 厂商            | Vidu (生数科技)                                 |
| API 基础域名 (国际) | `https://api.vidu.com`                      |
| API 基础域名 (国内) | `https://api.vidu.cn`                       |
| 认证方式          | Header `Authorization: Token {API_KEY}`     |
| 数据格式          | `Content-Type: application/json`            |
| 任务模式          | **异步** —— POST 创建任务 → 回调 / 轮询获取结果           |
| 结果有效期         | 生成结果 URL **仅 24 小时有效**,必须及时下载落库             |
| Off-Peak      | 错峰模式,价格通常为原价 **50%**,通过 `off_peak: true` 启用 |


### 能力矩阵


| 能力大类      | 端点                                      | 说明                           |
| --------- | --------------------------------------- | ---------------------------- |
| 文生视频      | `POST /ent/v2/text2video`               | 文本到视频                        |
| 图生视频      | `POST /ent/v2/img2video`                | 首帧图生成视频 (可音视频直出)             |
| 首尾帧生视频    | `POST /ent/v2/start-end2video`          | 首帧 + 尾帧补全中间                  |
| 参考生视频     | `POST /ent/v2/reference2video`          | 角色/物体一致性参考生视频                |
| 视频续写      | `POST /ent/v2/extend`                   | Video Extension              |
| 多帧生成      | `POST /ent/v2/multi-frame`              | Smart Multi-Frame            |
| 视频超分      | `POST /ent/v2/upscale`                  | Upscale Pro (1080P/2K/4K/8K) |
| 对口型       | `POST /ent/v2/lipsync`                  | Lip Sync                     |
| 动作同步      | `POST /ent/v2/motion-sync`              | Motion Sync                  |
| 数字人       | `POST /ent/v2/digital-human`            | Digital Human                |
| 文生图       | `POST /ent/v2/text2image`               | viduq1 / viduq2              |
| 参考生图      | `POST /ent/v2/reference2image`          | viduq1 / viduq2              |
| 文生音频      | `POST /ent/v2/text2audio`               | Audio 1.0                    |
| Timing 音频 | `POST /ent/v2/timing2audio`             | 按拍点生成                        |
| 文本转语音     | `POST /ent/v2/text2speech`              | TTS                          |
| 声音克隆      | `POST /ent/v2/voice-clone`              | 30 credits / voice           |
| Prompt 推荐 | `POST /ent/v2/prompt-suggest`           | 10 credits / 5 prompts       |
| 解决方案      | `POST /ent/v2/solutions/`*              | 一键成片、广告片、热门复刻、AI-MV          |
| 任务详情      | `GET /ent/v2/tasks/{task_id}/creations` | 获取结果(URL 24h 有效)             |
| 任务列表      | `POST /ent/v2/tasks`                    | 分页查询任务                       |


---

## 2. 计费规则

### 2.1 积分单价


| 单位       | 价格             |
| -------- | -------------- |
| 1 credit | **$0.005 USD** |


> 计费四舍五入规则:**积分含小数时向上取整**。

### 2.2 视频生成 — Vidu Q3 系列(主推)

#### Q3 Pro / Turbo / Pro-fast (img2video / text2video / start-end2video)


| 模型              | 分辨率   | Credits/秒 | 单价     | Off-Peak Credits/秒 | Off-Peak 单价 |
| --------------- | ----- | --------- | ------ | ------------------ | ----------- |
| viduq3-pro      | 1080P | 24        | $0.12  | 12                 | $0.06       |
| viduq3-pro      | 720P  | 20        | $0.10  | 10                 | $0.05       |
| viduq3-pro      | 540P  | 9         | $0.045 | 5                  | $0.025      |
| viduq3-turbo    | 1080P | 13        | $0.065 | 7                  | $0.035      |
| viduq3-turbo    | 720P  | 11        | $0.055 | 6                  | $0.03       |
| viduq3-turbo    | 540P  | 7         | $0.035 | 4                  | $0.02       |
| viduq3-pro-fast | 1080P | 25        | $0.125 | 13                 | $0.065      |
| viduq3-pro-fast | 720P  | 20        | $0.10  | 10                 | $0.05       |


时长范围:**1–16 秒**。`viduq3-pro-fast` 仅支持 img2video。

#### Q3 reference2video


| 模型           | 分辨率   | Credits/秒 | 单价     | Off-Peak   |
| ------------ | ----- | --------- | ------ | ---------- |
| viduq3-mix   | 720P  | 24        | $0.12  | 不支持        |
| viduq3-mix   | 1080P | 29        | $0.145 | 不支持        |
| viduq3-turbo | 540P  | 4         | $0.02  | 2 / $0.01  |
| viduq3-turbo | 720P  | 10        | $0.05  | 5 / $0.025 |
| viduq3-turbo | 1080P | 13        | $0.065 | 7 / $0.035 |
| viduq3       | 540P  | 7         | $0.035 | 4 / $0.02  |
| viduq3       | 720P  | 12        | $0.06  | 6 / $0.03  |
| viduq3       | 1080P | 15        | $0.075 | 7 / $0.035 |


时长范围:**3–16 秒**。

### 2.3 视频生成 — Vidu Q2 系列

> 计费模式为「**起步价 + 每秒增量**」。img2video / reference2video 启用音频功能需 **+15 credits**。

#### img2video / start-end2video


| 模型              | 分辨率   | 起步                | 每秒增量           |
| --------------- | ----- | ----------------- | -------------- |
| viduq2-turbo    | 540P  | 6 credits ($0.03) | +2 ($0.01/s)   |
| viduq2-turbo    | 720P  | 8 → 10@2s         | +10 ($0.05/s)  |
| viduq2-turbo    | 1080P | 35 ($0.175)       | +10 ($0.05/s)  |
| viduq2-pro      | 540P  | 8 → 10@2s         | +5 ($0.025/s)  |
| viduq2-pro      | 720P  | 15 ($0.075)       | +10 ($0.05/s)  |
| viduq2-pro      | 1080P | 55 ($0.275)       | +15 ($0.075/s) |
| viduq2-pro-fast | 720P  | 8 ($0.04)         | +2 ($0.01/s)   |
| viduq2-pro-fast | 1080P | 16 ($0.08)        | +4 ($0.02/s)   |


#### text2video / reference2video


| 模型         | Type            | 分辨率   | 起步  | 每秒增量 |
| ---------- | --------------- | ----- | --- | ---- |
| viduq2     | text2video      | 540P  | 10  | +2   |
| viduq2     | text2video      | 720P  | 15  | +5   |
| viduq2     | text2video      | 1080P | 20  | +10  |
| viduq2     | reference2video | 540P  | 15  | +5   |
| viduq2     | reference2video | 720P  | 25  | +5   |
| viduq2     | reference2video | 1080P | 75  | +10  |
| viduq2-pro | reference2video | 540P  | 20  | +5   |
| viduq2-pro | reference2video | 720P  | 30  | +5   |
| viduq2-pro | reference2video | 1080P | 85  | +10  |


### 2.4 视频生成 — Q1 / 2.0(保留)

#### Vidu Q1(固定 5s / 1080P / 80 credits = $0.4)

包含 reference2video / img2video / start-end2video / text2video,以及 viduq1-classic 的 img2video/start-end2video,以及 anime 风格 text2video。Off-Peak 一律 40 credits。

#### Vidu 2.0


| Type            | Duration | Resolution | Credits | Off-Peak |
| --------------- | -------- | ---------- | ------- | -------- |
| img2video       | 4s       | 360P       | 20      | 10       |
| img2video       | 4s       | 720P       | 40      | 20       |
| img2video       | 4s       | 1080P      | 100     | 50       |
| img2video       | 8s       | 720P       | 100     | 50       |
| reference2video | 4s       | 360P       | 80      | 40       |
| reference2video | 4s       | 720P       | 80      | 40       |
| start-end2video | 4s       | 360P       | 20      | 10       |
| start-end2video | 4s       | 720P       | 40      | 20       |
| start-end2video | 4s       | 1080P      | 100     | 50       |
| start-end2video | 8s       | 720P       | 100     | 50       |


### 2.5 视频续写 & 多帧


| 能力              | 模型           | 分辨率   | 起步  | 每秒增量 |
| --------------- | ------------ | ----- | --- | ---- |
| Video Extension | viduq2-turbo | 540P  | 10  | +2   |
| Video Extension | viduq2-turbo | 720P  | 15  | +5   |
| Video Extension | viduq2-turbo | 1080P | 40  | +10  |
| Video Extension | viduq2-pro   | 540P  | 15  | +5   |
| Video Extension | viduq2-pro   | 720P  | 30  | +10  |
| Video Extension | viduq2-pro   | 1080P | 60  | +15  |


Video Extension 仅支持加 1–7 秒。Smart Multi-Frame 按"段"计费,每两张图组成一段,最多 9 段,单段价格与上表对齐。

### 2.6 音频/图像/其他


| 能力                                  | 规则                           | Credits                          |
| ----------------------------------- | ---------------------------- | -------------------------------- |
| Audio 1.0 text2audio / timing2audio | <5s                          | 10                               |
| Audio 1.0 text2audio / timing2audio | <10s                         | 20                               |
| Voice Clone                         | per voice                    | 30 ($1.5)                        |
| Text to Speech                      | per 500 chars                | 1 ($0.05)                        |
| viduq2 text2image                   | 1080P                        | 6                                |
| viduq2 text2image                   | 2K                           | 8                                |
| viduq2 text2image                   | 4K                           | 10                               |
| viduq2 reference2image (1–3 ref)    | 1080P / 2K / 4K              | 8 / 12 / 20                      |
| viduq2 reference2image (4–7 ref)    | 1080P / 2K / 4K              | 10 / 16 / 30                     |
| viduq1 reference2image              | 1080P (1–7 ref)              | 20                               |
| Upscale Pro                         | 1080P / 2K / 4K / 8K (per s) | 10 / 20 / 40 / 160               |
| Lip Sync                            | 每 5s                         | 20                               |
| Motion Sync                         | 每秒                           | 10                               |
| Digital Human (Q2-turbo)            | 视频                           | 120,Lip-sync +20/5s,TTS +10/500字 |
| Digital Human (Q2-pro)              | 视频                           | 200,Lip-sync +20/5s,TTS +10/500字 |
| Prompt 推荐                           | 每 5 prompts                  | 10                               |


### 2.7 计费结算建议(项目侧)

1. **计费单位**:以 credit 为统一原子,展示价以 `credits × $0.005` 换算。落库时同时记录 credits 与折算后金额,避免后续单价变更需要重算。
2. **取整方向**:Vidu 自身按 credits 向上取整,在我们的 `pricing_engine`(`sql/061_pricing_engine.sql`)中需为 Vidu 加规则:`Math.ceil(base + per_second × duration)`。
3. **off_peak**:作为请求参数与计费分支同时存在,需要把"是否错峰"作为 `pricing_unit` 的维度之一,而非两套独立的 model。
4. **音频附加费**:img2video / reference2video 时 `audio=true` 必须叠加 +15 credits;DigitalHuman 的 lip-sync/TTS 叠加规则同理。

---

## 3. 调用协议

### 3.1 通用请求头

```http
POST /ent/v2/{endpoint} HTTP/1.1
Host: api.vidu.com
Content-Type: application/json
Authorization: Token {YOUR_API_KEY}
```

### 3.2 文生视频 (text2video)

```bash
curl -X POST https://api.vidu.com/ent/v2/text2video \
  -H "Authorization: Token {API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "viduq3-pro",
    "style": "general",
    "prompt": "An astronaut walking through fog...",
    "duration": 5,
    "seed": 0,
    "aspect_ratio": "16:9",
    "resolution": "720p",
    "movement_amplitude": "auto",
    "audio": true,
    "off_peak": false,
    "callback_url": "https://your-backend/api/internal/vidu/callback"
  }'
```

**请求字段:**


| 字段                 | 类型     | 必填  | 说明                                                  |
| ------------------ | ------ | --- | --------------------------------------------------- |
| model              | String | 是   | `viduq3-turbo` / `viduq3-pro` / `viduq2` / `viduq1` |
| style              | String | 否   | `general`(默认) / `anime`(仅 viduq1)                   |
| prompt             | String | 是   | 文本提示词                                               |
| duration           | Int    | 否   | 时长(秒),依模型而定                                         |
| seed               | Int    | 否   | 随机种子                                                |
| aspect_ratio       | String | 否   | `16:9` / `9:16` / `1:1` / `4:3` 等                   |
| resolution         | String | 否   | `540p` / `720p` / `1080p`                           |
| movement_amplitude | String | 否   | `auto` / `small` / `medium` / `large`               |
| audio              | Bool   | 否   | Q3 默认 true,生成同步音频                                   |
| off_peak           | Bool   | 否   | 错峰模式                                                |
| watermark          | Bool   | 否   | 是否带水印                                               |
| payload            | String | 否   | 透传参数                                                |
| meta_data          | String | 否   | 透传 JSON 字符串                                         |
| callback_url       | String | 否   | 状态变更回调 URL                                          |


### 3.3 图生视频 (img2video)

```bash
curl -X POST https://api.vidu.com/ent/v2/img2video \
  -H "Authorization: Token {API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "viduq3-pro",
    "images": ["https://.../start.png"],
    "prompt": "The astronaut waved and the camera moved up.",
    "audio": true,
    "voice_id": "professional_host",
    "duration": 5,
    "seed": 0,
    "resolution": "1080p",
    "movement_amplitude": "auto",
    "off_peak": false
  }'
```

**请求字段(差异部分):**


| 字段         | 类型            | 必填  | 说明                                                                                                                                              |
| ---------- | ------------- | --- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| model      | String        | 是   | `viduq3-pro-fast` / `viduq3-turbo` / `viduq3-pro` / `viduq2-pro-fast` / `viduq2-pro` / `viduq2-turbo` / `viduq1` / `viduq1-classic` / `vidu2.0` |
| images     | Array[String] | 是   | 首帧图像 URL 或 Base64 (`data:image/png;base64,...`),POST body ≤ 20MB,单图 ≤ 50MB                                                                      |
| voice_id   | String        | 否   | 配音音色 ID,**Q3 系列不生效**                                                                                                                            |
| audio_type | String        | 否   | `audio=true` 时必填,默认 `all`                                                                                                                       |


### 3.4 首尾帧生视频 (start-end2video)

```json
{
  "model": "viduq3-pro",
  "images": ["https://.../start.png", "https://.../end.png"],
  "prompt": "...",
  "duration": 5,
  "resolution": "1080p"
}
```

`images` 数组要求 **2 张**,顺序为 [首帧, 尾帧]。

### 3.5 参考生视频 (reference2video)

```json
{
  "model": "viduq3",
  "images": ["https://.../ref1.png", "https://.../ref2.png"],
  "prompt": "...",
  "duration": 5,
  "resolution": "1080p",
  "aspect_ratio": "16:9"
}
```

`images` 最多 **7 张参考图**,用于角色/物体一致性。

### 3.6 任务创建响应

所有视频生成接口的响应体结构一致:

```json
{
  "task_id": "abcd-1234",
  "state": "created",
  "model": "viduq3-pro",
  "prompt": "...",
  "duration": 5,
  "resolution": "1080p",
  "movement_amplitude": "auto",
  "off_peak": false,
  "credits": 100,
  "created_at": "2026-06-15T03:00:00Z"
}
```

`**state` 枚举:**


| state        | 含义   |
| ------------ | ---- |
| `created`    | 创建成功 |
| `queueing`   | 排队中  |
| `processing` | 处理中  |
| `success`    | 成功   |
| `failed`     | 失败   |


### 3.7 任务查询 (GET creations)

```bash
curl -X GET https://api.vidu.com/ent/v2/tasks/{task_id}/creations \
  -H "Authorization: Token {API_KEY}"
```

**响应:**

```json
{
  "state": "success",
  "creations": [
    {
      "id": "...",
      "url": "https://.../result.mp4",
      "cover_url": "https://.../cover.jpg",
      "video": {
        "duration": 5.0,
        "fps": 24,
        "resolution": { "width": 1920, "height": 1080 }
      }
    }
  ],
  "credits": 100,
  "created_at": "..."
}
```

> ⚠️ `url` 与 `cover_url` 仅 **24 小时有效**,Worker 拉到结果后必须立刻下载并落到本地/OSS。

### 3.8 任务列表 (POST tasks)

```json
{
  "created_at": { "from": "2026-06-01T00:00:00Z", "to": "2026-06-15T00:00:00Z" },
  "task_ids": ["..."],
  "model_versions": ["q3-pro", "q2-pro"],
  "states": ["success", "failed"],
  "pager": { "page": 0, "pagesz": 50 }
}
```

### 3.9 回调协议

创建任务时传入 `callback_url`,Vidu 会在状态变更时 POST 到该地址,**body 结构与 `GET creations` 完全一致**。

- 触发时机:`processing` / `success` / `failed`
- 失败重试:发送失败时最多重试 3 次
- 验签:使用 Vidu 的 Callback Signature 算法,基于账户的 `TokenSecret`,需查阅最新文档实现

**项目侧建议**:Worker 同时支持**回调**和**轮询兜底**(每 5–10s 拉一次 `GET creations`),避免回调丢失导致任务卡死。

---

## 4. 项目接入清单

> 本仓库现有的 `worker/`、`backend/`、`sql/061_pricing_engine.sql`、`sql/044_model_vendor_accounts.sql`、`sql/045_model_vendors.sql` 已具备多厂商接入框架,Vidu 接入按以下步骤补齐:

1. **厂商注册**(`model_vendors`):新增 `vidu` 厂商,记录国际/国内域名、鉴权模板、回调路径。
2. **账户配置**(`model_vendor_accounts`):录入 API Key、TokenSecret(回调验签用),区分海外/国内账号。
3. **模型清单**(`agent_model_configs` / 工具配置):为每个 Vidu 模型 × 分辨率 × 时长档建一条工具或模型配置,并标记是否支持 audio / off_peak / reference 等能力。
4. **字段 schema**(`tool_field_schemas`):
  - 必填:`prompt`、`model`、`duration`、`resolution`
  - 模态相关:`images`(单图/多图/首尾帧/参考图)
  - 计费相关:`off_peak`、`audio`、`audio_type`、`voice_id`
  - 其他:`seed`、`aspect_ratio`、`movement_amplitude`、`style`、`watermark`
5. **Pricing Engine 规则**(`sql/061_pricing_engine.sql` + seed):
  - 视频类:`credits = ceil(base[model,resolution] + per_second[model,resolution] × duration) + (audio ? 15 : 0)`
  - off_peak:接入价格表上的 off_peak 列,以独立 `pricing_unit` 维度存在
  - 图像/音频:按 5 节中的不同规则单建 unit
6. **Worker handler**(`worker/handlers/`):新增 `vidu_video_handler.py`,实现:
  - 调用对应 `/ent/v2/{endpoint}`
  - 落库 `ai_tasks.external_task_id = task_id`
  - 注册 callback,异步等待 / 轮询
  - 拿到 `state=success` 后立即 `aria2c/httpx` 下载 url、cover_url 到本地媒体目录,并写 `ai_result_resources`
7. **回调路由**(`backend/.../task/internal`):新增 `POST /api/internal/vidu/callback`,验签 → 透传给 Worker 回写任务状态。
8. **失败兜底**:超过预期时长(如 10 分钟)未收到回调,Worker 主动调 `GET creations` 查询;`state=failed` 时按 Vidu 错误信息走我们的退款/重试链路。
9. **计费日志**(`billing_usage_logs`):落入 `vendor=vidu`、`credits`、`unit_price_usd=0.005`、`off_peak` 等字段,便于后续对账。

---

## 5. 参考链接

- 计费总表:[https://platform.vidu.com/docs/pricing](https://platform.vidu.com/docs/pricing)
- Text to Video:[https://platform.vidu.com/docs/text-to-video](https://platform.vidu.com/docs/text-to-video)
- Image to Video:[https://platform.vidu.com/docs/image-to-video](https://platform.vidu.com/docs/image-to-video)
- Reference to Video:[https://platform.vidu.com/docs/reference-to-video](https://platform.vidu.com/docs/reference-to-video)
- Start End to Video:[https://platform.vidu.com/docs/start-end-to-video](https://platform.vidu.com/docs/start-end-to-video)
- Task List:[https://platform.vidu.com/docs/tasks-list](https://platform.vidu.com/docs/tasks-list)
- 中文站(国内域名):[https://platform.vidu.cn/docs/text-to-video](https://platform.vidu.cn/docs/text-to-video)
- 商务对接:[mailto:platform@vidu.studio](mailto:platform@vidu.studio)

