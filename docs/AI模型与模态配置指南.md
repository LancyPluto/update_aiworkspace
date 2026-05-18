# AI 模型与模态配置指南

本文说明当前项目中“模型配置、工具模态、后台绑定、Worker 执行路径”之间的关系，目标是让后续接入新模型时先判断是否能做到“配置即可用”，再决定是否需要补后端或 Worker 代码。

## 一、核心结论

当前架构已经把“模型供应商配置”和“工具配置”解耦到较清晰的层次：

1. 已有执行路径的同类模型，通常只需要后台配置即可使用。
2. 新增同一模态、同一协议兼容的模型，优先走 `AI 模型配置` + `AI 工具管理` 页面绑定。
3. 新增供应商但接口协议与现有 Worker client 兼容时，只需要补供应商注册和模型配置，不需要新增一套工具后端。
4. 新增完全不同的模态或返回结构，例如音乐、视频、文件解析、语音识别，通常需要补 Worker handler、client、结果持久化和前端渲染。

例如：

- MiniMax TTS、生音频、SiliconFlow TTS：都属于 `TEXT_TO_SPEECH` 能力，输入文本、输出音频，可以共用通用 TTS 执行路径。
- MiniMax 生音乐：属于 `MUSIC_GENERATION`，虽然输出也是音频，但输入参数、调用链、结果语义和工具模板不同，建议单独补音乐生成 Worker 路径。

## 二、关键概念

### 1. ToolType：工具能力类型

后端枚举位置：

`backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/ToolType.java`

常见值：

| ToolType | 说明 | 典型输入 | 典型输出 |
| --- | --- | --- | --- |
| `TEXT_GENERATION` | 文本生成 | 文本 / JSON | 文本 |
| `IMAGE_GENERATION` | 文生图 | 文本 | 图片 |
| `TEXT_TO_SPEECH` | 文字转语音 / 生音频 | 文本 | 音频 |
| `VIDEO_GENERATION` | 视频生成 | 文本 / 图片 | 视频 |
| `AGENT` | Agent 工具 | 多轮上下文 | 文本 / 工具调用 |

后台“AI 工具管理”里的“模型能力类型”本质上就是工具要绑定的 `ToolType`。

### 2. ToolModality：输入 / 输出模态

后端枚举位置：

`backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/ToolModality.java`

常见值：

| ToolModality | 说明 |
| --- | --- |
| `TEXT` | 文本 |
| `IMAGE` | 图片 |
| `AUDIO` | 音频 |
| `VIDEO` | 视频 |
| `JSON` | 结构化 JSON |
| `FILE` | 文件 |
| `MULTIMODAL` | 多模态 |

后台工具配置中会同时记录：

- `toolType`：工具能力类型，例如 `TEXT_TO_SPEECH`
- `inputModality`：输入模态，例如 `TEXT`
- `outputModality`：输出模态，例如 `AUDIO`

模型下拉列表会按工具能力匹配模型配置。也就是说，文字转语音工具只应该看到具备 `TEXT_TO_SPEECH` 能力的模型配置。

### 3. ExecutionHandler：Worker 执行处理器

后端枚举位置：

`backend/src/main/java/com/aiminilab/aitoolmarket/common/enums/ExecutionHandler.java`

它决定任务发到 Worker 后走哪条处理路径：

| ExecutionHandler | Worker 路径 | 用途 |
| --- | --- | --- |
| `TEXT_GENERATION` | 文本处理器 | 普通文案、文本生成 |
| `IMAGE_GENERATION` | 图片处理器 | 文生图 |
| `TEXT_TO_SPEECH` | TTS 处理器 | 文字转语音 / 生音频 |
| `VIDEO_GENERATION` | 视频处理器 | 视频生成 |
| `DIGITAL_HUMAN` | 数字人处理器 | 数字人视频 |

工具模板会决定默认的 `ExecutionHandler`。后台创建工具时选择模板，等于选择了默认执行路径。

## 三、模型供应商配置

供应商能力注册文件：

`backend/src/main/resources/model-providers.yml`

这里定义“系统知道哪些供应商具备哪些能力”，例如：

```yaml
providers:
  - code: minimax_speech
    label: MiniMax speech
    capabilities:
      - TEXT_TO_SPEECH
    defaultBaseUrl: https://api.minimaxi.com
    defaultModel: speech-2.8-hd
    billingDefault: PER_CALL
    testStrategy: accept_only
    workerReady: true
    description: MiniMax /v1/t2a_async_v2 asynchronous text-to-speech.
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `code` | 供应商唯一编码，会被模型配置和 Worker 使用 |
| `label` | 后台展示名称 |
| `capabilities` | 供应商支持的能力列表，决定模型能否被对应工具选择 |
| `defaultBaseUrl` | 默认 API 地址 |
| `defaultModel` | 默认模型名 |
| `billingDefault` | 默认计费方式 |
| `testStrategy` | 后台测试策略 |
| `workerReady` | Worker 是否已经支持实际调用 |
| `description` | 后台说明 |

如果只是新增一个已有能力的供应商，例如新增另一个兼容 OpenAI chat completions 的文本模型，一般只需要在这里注册供应商能力，然后在后台添加模型配置。

## 四、后台新增模型配置

后台路径：

`系统配置 / AI 模型配置`

新增模型配置时重点填写：

1. 供应商：选择 `model-providers.yml` 中已经注册的 provider。
2. 模型名称：供应商真实模型名，例如 `speech-2.8-hd`。
3. Base URL：供应商接口地址。
4. API Key：供应商密钥。
5. 能力：必须包含对应工具需要的能力，例如 `TEXT_TO_SPEECH`。
6. 状态：启用。

配置完成后，`AI 工具管理` 页顶部的“已配置模型能力”列表会显示当前可绑定的模型能力，方便确认是否配置成功。

## 五、后台新增或绑定 AI 工具

后台路径：

`AI 工具管理`

新增工具建议流程：

1. 点击 `Add Tool`。
2. 选择工具模板，例如 `Text to speech (TEXT_TO_SPEECH)`。
3. 确认工具能力类型、输入模态、输出模态。
4. 选择模型配置。
5. 填写工具名称、分类、算力消耗、配置说明。
6. 保存工具。

模型下拉列表的匹配逻辑：

1. 先根据工具的 `toolType` 过滤模型能力。
2. `TEXT_TO_SPEECH` 工具只展示包含 `TEXT_TO_SPEECH` 能力的模型。
3. `IMAGE_GENERATION` 工具只展示包含 `IMAGE_GENERATION` 能力的模型。
4. 如果没有匹配模型，会提示需要配置对应能力模型。

因此，如果 MiniMax TTS 模型没有显示，优先检查：

1. `model-providers.yml` 是否注册了 `TEXT_TO_SPEECH` 能力。
2. 后台模型配置是否选择了正确 provider。
3. 模型配置是否启用。
4. 模型配置能力字段是否包含 `TEXT_TO_SPEECH`。
5. 前端页面是否刷新了最新模型配置。

## 六、TTS / 生音频接入说明

当前通用 TTS 路径已经支持：

- `TEXT_TO_SPEECH` 工具类型
- 输入文本，输出音频
- MiniMax speech provider
- SiliconFlow speech provider
- 音频保存到项目目录 `data/generated-media/audio/{taskId}/`
- 前端任务结果页音频播放器渲染

Worker 相关文件：

| 文件 | 作用 |
| --- | --- |
| `worker/client/text_to_speech_client.py` | 调用不同 TTS 供应商 |
| `worker/handlers/text_to_speech_handler.py` | 处理 `TEXT_TO_SPEECH` 任务 |
| `worker/handlers/generated_audio_persister.py` | 保存音频文件并生成 `/generated/...` URL |
| `worker/task_queue/redis_consumer.py` | 将任务路由到 TTS handler |
| `worker/providers/registry.py` | Worker 侧 provider 能力注册 |

新增 TTS 模型时，如果满足以下条件，通常配置即可用：

1. 输入是文本。
2. 输出是常规音频文件或可下载音频。
3. provider 已经在 `text_to_speech_client.py` 中支持。
4. 后台模型配置能力包含 `TEXT_TO_SPEECH`。
5. 工具选择 `TEXT_TO_SPEECH` 模板。

需要补 Worker 代码的情况：

1. 供应商 API 协议与现有 MiniMax / SiliconFlow 都不兼容。
2. 返回结果不是直接音频、URL、base64 或当前已支持格式。
3. 需要异步任务轮询、签名、分片上传、回调通知等特殊逻辑。
4. 输出不是普通音频，而是歌词、工程文件、多轨音频等复杂结果。

## 七、MiniMax TTS 配置参考

供应商注册建议：

```yaml
code: minimax_speech
capabilities:
  - TEXT_TO_SPEECH
defaultBaseUrl: https://api.minimaxi.com
defaultModel: speech-2.8-hd
workerReady: true
```

后台模型配置建议：

| 字段 | 建议值 |
| --- | --- |
| Provider | `minimax_speech` |
| Model | `speech-2.8-hd` |
| Base URL | `https://api.minimaxi.com` |
| Capability | `TEXT_TO_SPEECH` |
| API Key | MiniMax API Key |
| Status | Enabled |

工具配置建议：

| 字段 | 建议值 |
| --- | --- |
| 模板 | `Text to speech (TEXT_TO_SPEECH)` |
| ToolType | `TEXT_TO_SPEECH` |
| InputModality | `TEXT` |
| OutputModality | `AUDIO` |
| Model config | 选择 MiniMax speech 模型配置 |

常见错误：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `invalid api key` | API Key 错误或不是对应 MiniMax 平台 Key | 重新生成并更新后台模型配置 |
| SSL EOF | 网络或域名不稳定，也可能是旧域名 / endpoint | 优先使用 `https://api.minimaxi.com` |
| 播放器显示 0 秒 | 文件 URL 没有正确映射到音频文件，或后端未重启读取新目录 | 检查 `/generated/audio/...` 是否返回 200，并重启 backend / worker |
| 模型下拉不显示 | 模型能力没有包含 `TEXT_TO_SPEECH` | 检查 provider 和模型配置能力 |

## 八、生音乐与 TTS 是否共用路径

不建议把生音乐强行塞进通用 TTS 路径。

虽然 TTS 和生音乐最终都可能输出音频，但它们的业务语义不同：

| 项目 | TTS / 生音频 | 生音乐 |
| --- | --- | --- |
| ToolType | `TEXT_TO_SPEECH` | 建议新增或使用 `MUSIC_GENERATION` |
| 输入 | 文本、声音、语速、格式 | 歌词、风格、曲风、时长、参考音频等 |
| 输出 | 语音音频 | 音乐音频，可能含歌词、封面、任务 metadata |
| Worker client | `text_to_speech_client.py` | 建议新增 `music_generation_client.py` |
| Handler | `text_to_speech_handler.py` | 建议新增 `music_generation_handler.py` |
| 前端渲染 | 音频播放器 | 音频播放器 + 音乐元信息 |

建议做法：

1. TTS 和普通生音频继续走 `TEXT_TO_SPEECH`。
2. 生音乐新增 `MUSIC_GENERATION` 工具能力和模板。
3. 生音乐可以复用 `generated_audio_persister.py` 保存音频文件。
4. 生音乐应单独实现 Worker client 和 handler，避免 TTS handler 参数越来越混乱。

## 九、新增模型判断清单

新增前先回答以下问题：

1. 这个模型属于已有 ToolType 吗？
2. 输入 / 输出模态是否和已有路径一致？
3. provider API 是否和已有 client 兼容？
4. 结果是否能被现有前端 ResultRenderer 渲染？
5. 是否需要异步轮询或特殊下载？
6. 是否需要新增工具字段模板？

判断结果：

| 判断 | 操作 |
| --- | --- |
| 同能力、同协议、同结果格式 | 后台配置模型即可 |
| 同能力、不同供应商协议 | 补 Worker client 分支 |
| 同输出模态、不同业务语义 | 建议新增 ToolType / handler |
| 新模态 | 补后端枚举、模板、Worker handler、前端渲染 |

## 十、新增模态的开发步骤

如果确认是新模态，建议按下面顺序补齐：

1. 后端新增 `ToolType`。
2. 必要时新增 `ExecutionHandler`。
3. 更新 `model-providers.yml`，注册 provider capability。
4. 更新工具模板 Bootstrap，提供默认工具模板。
5. 更新后台 `AI 工具管理` 的模板映射和中文展示。
6. 更新 Worker provider registry。
7. 新增 Worker client 和 handler。
8. 更新 Redis consumer 路由。
9. 更新结果持久化，例如图片、音频、视频、文件。
10. 更新用户端 `ResultRenderer`。
11. 补 backend / worker / frontend 测试。

## 十一、静态文件与生成结果目录

生成媒体文件统一放在项目根目录：

`data/generated-media/`

后端通过 `/generated/**` 暴露静态资源，Worker 返回结果中保存类似：

```json
{
  "audios": [
    {
      "url": "/generated/audio/21/audio-1.mp3",
      "contentType": "audio/mpeg"
    }
  ]
}
```

注意：

1. `data/generated-media/` 是运行产物，不应该提交到 Git。
2. 修改 `GENERATED_MEDIA_DIR` 后需要重启 backend 和 worker。
3. 播放器显示 0 秒时，先在浏览器 Network 中检查 `/generated/...` 是否返回 200 和正确文件大小。

## 十二、上线前检查

每次新增模型或模态后建议检查：

1. 后台模型能力列表能看到对应模型。
2. AI 工具管理中对应工具只展示匹配能力的模型。
3. 创建工具、编辑工具、删除工具正常。
4. 用户端能提交任务。
5. Worker 日志能看到正确 provider / model。
6. 任务成功后结果能渲染。
7. `/generated/...` 资源能直接访问。
8. 失败时有清晰错误信息和 traceId。

推荐验证命令：

```powershell
mvn test -Dtest=ModelProviderRegistryTest,ToolApiTest
npm.cmd run build
python -m unittest discover -s worker\tests
```

