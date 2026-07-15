# AI漫剧工作流完整交付计划书

更新时间：2026-06-03

## 1. 项目目标

面向 AI 工具超市现有“智能体工具 + 后台工作流画布 + 任务队列 + 模型配置”体系，建设一套可配置、可运营、可计费、可追踪的 AI 漫剧生产工作流。

目标不是只做一个“一键生成视频”的工具，而是把 AI 漫剧拆成可管理的生产阶段：故事策划、剧本拆解、分镜、角色定妆、关键帧、图生视频、配音配乐、剪辑合成、审核交付。后台可以配置每一步使用的模型、消耗积分、是否人工审核、失败重试策略和产物展示方式。

## 2. 参考教程要点

参考来源：

- CSDN《AI短剧/漫剧制作教程：从零到一的完整创作指南》：[https://blog.csdn.net/wangzhae/article/details/160806037](https://blog.csdn.net/wangzhae/article/details/160806037)
- 知乎专栏链接：[https://zhuanlan.zhihu.com/p/1990797931272491458。当前页面在抓取环境下不可稳定读取，计划中仅使用其主题方向，并以可访问教程与本项目现有架构做落地设计。](https://zhuanlan.zhihu.com/p/1990797931272491458。当前页面在抓取环境下不可稳定读取，计划中仅使用其主题方向，并以可访问教程与本项目现有架构做落地设计。)

从可访问教程中提炼出的关键方法：

- 标准链路：剧本 -> 分镜 -> 角色定妆 -> 图片素材 -> 首尾帧/图生视频 -> 配音配乐 -> 合成发布。
- 剧本侧要结构化输入：场景、角色、情绪、动作、旁白、台词。
- 分镜侧要表格化输出：镜号、景别、运镜、画面描述、音效、台词、时长。
- 角色一致性是质量关键：需要角色身份卡、四视图或至少正面/侧面/背面锚点图、固定风格词和否定词。
- 视频生成不宜一次性完成：采用关键帧、首尾帧和多片段接力，按 3-6 秒镜头生成，再统一合成。
- 商用必须保留模型、素材、音乐、配音授权和 AIGC 标识记录。

## 3. 交付范围

### 3.1 用户侧交付

用户提交：

- 故事主题、题材、受众、集数、单集时长。
- 主要角色、剧情梗概、参考素材、禁用元素。
- 画风、画幅、清晰度、输出粒度。
- 可选：小说片段、剧本、角色图、参考视频、口播音频。

用户获得：

- 项目策划案：卖点、世界观、人物关系、分集大纲。
- 角色资产包：角色身份卡、定妆提示词、参考图。
- 分镜脚本：镜号、景别、运镜、时长、台词、音效、Prompt。
- 关键帧资产：首帧、尾帧、场景图、角色图。
- 视频片段：按镜头生成的 clip。
- 音频资产：角色配音、旁白、BGM、音效建议。
- 成片：final.mp4、字幕文件、封面图、发布文案、授权记录。

### 3.2 管理侧交付

后台需要支持：

- 工作流画布配置 AI 漫剧节点。
- 每个节点绑定模型配置、积分成本、超时、重试、输入输出字段。
- 工作流版本管理和回滚。
- 节点级人工审核开关。
- 任务队列监控：排队、处理中、失败、重试、完成。
- 资产追踪：每个产物绑定 taskId、nodeId、episodeId、shotId。
- 成本和消耗报表：按模型、节点、用户、项目统计。
- 合规配置：禁用题材、敏感词、版权声明、AIGC 标识模板。

## 4. 标准生产 SOP

### 阶段 A：项目初始化

输入：

- storyTheme、genre、targetAudience、episodeCount、episodeDuration、visualStyle、aspectRatio。

输出：

- comicProjectId。
- 项目配置快照。
- 生产目标：单集时长、镜头数、画幅、质量档位、预计积分。

验收：

- 必填字段完整。
- 成本预估可见。
- 工作流版本已锁定，后续任务按该版本执行。

### 阶段 B：剧本与系列策划

处理逻辑：

- 使用文本模型生成世界观、人物关系、分集大纲。
- 每集必须包含钩子、冲突、反转、结尾悬念。
- 对原始小说或长文做压缩改编时，保留爽点、冲突和情绪曲线。

输出：

- series_bible.md。
- episodes.json。
- 每集 script.md。

后台节点：

- nodeDefType：script_planner。
- 建议绑定：DeepSeek/Kimi/豆包/通义等长文本模型。

### 阶段 C：分镜拆解

处理逻辑：

- 把每集剧本文字翻译为镜头语言。
- 一集 60 秒建议 8-15 个分镜；3-5 分钟建议 30-60 个分镜。
- 每个镜头控制在 3-6 秒，便于视频模型稳定生成。

分镜字段：

- shotNo、scene、景别、运镜、画面描述、角色动作、表情、台词、音效、BGM 情绪、时长、首帧提示词、尾帧提示词、视频提示词、否定词。

输出：

- storyboard.json。
- storyboard.md。

后台节点：

- nodeDefType：storyboard_generator。

### 阶段 D：角色定妆与视觉圣经

处理逻辑：

- 为主角、反派、关键配角生成身份卡。
- 至少生成正面半身、正面全身、侧面全身、背面全身。
- 固定面部、发型、服装、配饰、色彩、否定词。

输出：

- character_cards.json。
- character_reference_images。
- visual_bible.md。

后台节点：

- nodeDefType：character_design。
- 建议支持 image_model、image_edit_model、reference_image 输入。

质量规则：

- 同一角色跨镜头脸型、瞳色、发型、标志配饰不得明显漂移。
- 多角色同屏时必须标注方位和角色身份。

### 阶段 E：场景与关键帧生成

处理逻辑：

- 先批量生成场景锚点图。
- 再按分镜生成首帧/尾帧。
- 对不稳定镜头允许局部重绘或重新抽卡。

Prompt 公式：

- 风格 + 场景 + 角色动作/表情 + 视角构图 + 光影质感 + 情绪氛围 + 画幅 + 否定词。

输出：

- shot_frames/{episodeId}/{shotNo}/first.png。
- shot_frames/{episodeId}/{shotNo}/last.png。
- frame_qc_result.json。

后台节点：

- nodeDefType：keyframe_generator。
- 可复用现有 image_generation_handler 和 generated_image_persister。

### 阶段 F：图生视频与片段接力

处理逻辑：

- 使用首尾帧控制镜头运动。
- 单镜头 3-6 秒。
- 超过 10 秒的连续动作采用片段接力：上一段末帧作为下一段首帧。
- 对打斗、追逐、大幅运动镜头允许切换更适合动态的视频模型。

输出：

- clips/{episodeId}/{shotNo}.mp4。
- clip_generation_log.json。

后台节点：

- nodeDefType：image_to_video。
- 建议绑定：可灵、海螺、Wan、Seedance 等视频模型配置。

失败处理：

- 脸部崩坏：回退到关键帧重生。
- 运镜不符合：只重试视频节点。
- 动作过大：拆分镜头并降低单段运动量。

### 阶段 G：配音、配乐和音效

处理逻辑：

- 按角色建立音色。
- 台词带情绪标签、停顿、语速。
- BGM 按剧情情绪匹配。
- 音量原则：对白最清晰，音效居中，配乐最低。

输出：

- voice_tracks/{character}/{lineId}.wav。
- episode_voice_mix.wav。
- bgm.wav。
- sfx_manifest.json。

后台节点：

- nodeDefType：tts_model。
- nodeDefType：music_sfx。

### 阶段 H：剪辑合成与字幕

处理逻辑：

- 按 storyboard 时间轴拼接 clip。
- 对齐配音、字幕、音效、BGM。
- 生成封面、片头、片尾、AIGC 标识。

输出：

- final.mp4。
- subtitles.srt。
- cover.png。
- publish_copy.md。
- license_manifest.json。

后台节点：

- nodeDefType：subtitle。
- nodeDefType：video_composer。
- 可复用 FFmpeg 合成思路。

### 阶段 I：审核与交付

审核维度：

- 内容安全：低俗、血腥、未成年人不适宜、平台敏感内容。
- 版权：参考素材、音乐、声音、人物肖像、IP 改编授权。
- 质量：角色一致性、字幕准确、音画同步、画面变形、节奏。
- 商用：AIGC 声明、授权留痕、模型使用记录。

输出：

- review_report.json。
- delivery_package.zip。

后台节点：

- nodeDefType：human_review。
- nodeDefType：output。

## 5. 后台工作流画布设计

建议在 `tool_workflows` 中为 `ai_comic_drama_agent` 保存默认工作流，节点如下：

1. Start：用户提交表单、素材和会话上下文。
2. Field Input：整理项目参数。
3. Script Planner：生成系列策划、分集大纲、样例剧本。
4. Storyboard Generator：生成分镜表和镜头 Prompt。
5. Character Design：生成角色身份卡和定妆图。
6. Scene Design：生成场景视觉设定。
7. Keyframe Generator：生成每个镜头首帧/尾帧。
8. Frame QC：检测角色一致性、画幅、清晰度、违规元素。
9. Image To Video：生成分镜视频片段。
10. Clip QC：检测脸部崩坏、视频黑屏、时长、比例、运动稳定性。
11. Voice TTS：生成旁白和角色配音。
12. Music/SFX：生成或匹配 BGM、音效。
13. Subtitle + Composer：字幕、音频、视频合成。
14. Human Review：人工审核，可配置为必审或抽审。
15. End：输出成片、资产包和发布材料。

建议节点分组：

- 创意策划组：Start、Field Input、Script Planner、Storyboard Generator。
- 视觉资产组：Character Design、Scene Design、Keyframe Generator、Frame QC。
- 动态生成组：Image To Video、Clip QC。
- 声音剪辑组：Voice TTS、Music/SFX、Subtitle + Composer。
- 审核交付组：Human Review、End。

## 6. 工作流配置 JSON 建议

建议在 workflow.configJson 中保存：

```json
{
  "workflowType": "AI_COMIC_DRAMA",
  "integrationMode": "STANDARD_TASK",
  "customUiRoute": "/tools/ai_comic_drama_agent/workspace",
  "requiredModelConfigCodes": [
    "text_planner",
    "image_generation",
    "image_to_video",
    "voice_tts",
    "asr_or_subtitle"
  ],
  "qualityGates": {
    "frameQc": true,
    "clipQc": true,
    "contentSafetyReview": true,
    "humanReviewMode": "required_before_final"
  },
  "episodeDefaults": {
    "shotDurationSeconds": 5,
    "shotsPerMinute": 12,
    "maxRetryPerShot": 3
  },
  "billing": {
    "mode": "step_based",
    "previewBeforeExpensiveSteps": true
  },
  "delivery": {
    "includeStoryboard": true,
    "includePromptPack": true,
    "includeLicenseManifest": true,
    "includeAigcDisclosure": true
  }
}
```

## 7. 数据模型建议

现有系统可先以 `ai_tasks` + `ai_result_resources` 承载主任务和结果，MVP 不一定立刻新增全量项目表。若要做成可持续生产工作台，建议新增以下表：

### comic_projects

- id
- user_id
- tool_id
- workflow_id
- workflow_version
- title
- genre
- target_audience
- visual_style
- aspect_ratio
- status
- config_json
- created_at
- updated_at

### comic_episodes

- id
- project_id
- episode_no
- title
- duration_seconds
- script_json
- status

### comic_shots

- id
- episode_id
- shot_no
- scene
- camera_type
- camera_motion
- duration_seconds
- dialogue_text
- prompt_json
- first_frame_resource_id
- last_frame_resource_id
- clip_resource_id
- qc_status
- status

### comic_assets

- id
- project_id
- episode_id
- shot_id
- asset_type
- resource_id
- model_config_id
- prompt_hash
- license_json
- created_at

### comic_reviews

- id
- project_id
- episode_id
- review_type
- reviewer_id
- status
- issues_json
- created_at

## 8. 任务状态设计

项目状态：

- DRAFT：草稿。
- PLANNING：策划中。
- ASSET_GENERATING：资产生成中。
- VIDEO_GENERATING：视频生成中。
- COMPOSING：合成中。
- REVIEWING：审核中。
- COMPLETED：完成。
- FAILED：失败。
- CANCELLED：取消。

镜头状态：

- PENDING。
- FRAME_READY。
- FRAME_FAILED。
- CLIP_GENERATING。
- CLIP_READY。
- CLIP_FAILED。
- APPROVED。
- NEEDS_RETRY。

节点失败策略：

- 文本节点失败：直接重试。
- 图片节点失败：重试 2-3 次，仍失败进入人工处理。
- 视频节点失败：允许单镜头重试，不影响其他镜头继续排队。
- 合成节点失败：保留所有 clip 和 audio，重试 composer。
- 审核失败：生成整改任务，不自动扣除完整二次费用，可按重生成节点计费。

## 9. 管理侧页面改造

### 工具列表

在“智能体工具”模式下，`ai_comic_drama_agent` 显示：

- 工作流画布入口。
- 模型绑定状态。
- 近 7 天生成量、失败率、平均成本。

### 工作流画布

增强点：

- 节点 inspector 增加：模型配置、积分、超时、重试、审核开关。
- 节点入参/出参支持资产类型：script、storyboard、image、video、audio、subtitle、manifest。
- 支持节点分组模板：AI 漫剧模板一键初始化。

### 任务管理

增加筛选：

- projectId、episodeNo、shotNo、nodeDefType、modelConfigId。

增加展示：

- 分镜级进度条。
- 单镜头失败原因。
- 重试入口。
- 产物预览：图片、视频、音频、字幕、Markdown。

### 配置管理

增加模板：

- 轻量版：只输出策划案 + 分镜 + Prompt。
- 标准版：输出单集成片。
- 工业版：多集批量生产 + 人审 + 资产包。

## 10. 用户侧工作台建议

MVP 可以沿用普通工具提交页和任务结果页。正式版本建议做自定义工作台：

- 项目页：基础信息、预算、进度。
- 剧本页：分集大纲、脚本编辑、锁定版本。
- 分镜页：表格编辑、镜头重排、批量生成。
- 角色页：身份卡、参考图、重绘。
- 资产页：首帧/尾帧/clip 预览。
- 合成页：时间轴、字幕、音频、导出。
- 发布页：封面、标题、简介、AIGC 声明、授权记录。

## 11. 积分与成本策略

建议分段计费：

- 策划/剧本：低成本，可一次性扣费。
- 分镜：低成本，可一次性扣费。
- 角色定妆：按图片张数扣费。
- 关键帧：按镜头和重绘次数扣费。
- 图生视频：高成本，生成前二次确认。
- 配音配乐：按字数、音频时长或调用次数扣费。
- 合成导出：固定少量积分。

运营策略：

- 免费试用只开放“策划案 + 前 3 个分镜 Prompt”。
- 标准版开放“15-30 秒样片”。
- 完整版开放“单集成片 + 资产包”。
- 批量多集必须预估积分并提示余额。

## 12. 质量验收标准

MVP 验收：

- 管理侧可看到 AI 漫剧工具和工作流画布。
- 用户能提交主题并生成结构化策划案、分镜、角色卡、图/视频 Prompt。
- 任务结果能展示 Markdown 和资源链接。
- 工作流保存、版本记录、恢复可用。

标准版验收：

- 能生成至少 1 集 30-60 秒 AI 漫剧。
- 至少 8 个镜头。
- 至少 2 个主要角色身份卡。
- 每个镜头有首帧、尾帧、clip。
- 成片含字幕、配音、BGM、封面。
- 单镜头失败可重试，不需要重跑全流程。

工业版验收：

- 支持 3-5 集批量生产。
- 支持分镜表人工编辑后继续生成。
- 支持角色参考图复用。
- 支持节点级成本统计。
- 支持审核报告和授权记录导出。

## 13. 实施计划

### 第 1 周：MVP 策划型工具

交付：

- 修正 `ai_comic_drama_agent` 种子数据乱码。
- 完善字段：题材、受众、集数、时长、画风、参考素材、输出粒度。
- Prompt 输出结构化 Markdown + JSON 片段。
- 后台默认工作流从数字人口播模板改为 AI 漫剧模板。

技术范围：

- SQL seed。
- tool_prompts。
- tool_workflows 默认 nodes/edges/config。
- 任务结果展示适配 Markdown。

### 第 2 周：分镜与资产生成

交付：

- 增加分镜 JSON schema。
- 增加角色身份卡和关键帧 Prompt 生成。
- 串接图片生成模型，生成角色定妆图和首帧。
- 后台任务详情展示分镜表和图片资产。

技术范围：

- worker 文本解析器。
- image_generation_handler 复用。
- ai_result_resources 资产绑定。

### 第 3 周：视频片段生成

交付：

- 串接图生视频模型。
- 每个 shot 生成 clip。
- 单镜头重试。
- 视频片段预览。

技术范围：

- video_generation_handler 扩展 shotId。
- task progress message 增加 episode/shot 进度。
- 失败原因结构化。

### 第 4 周：配音合成与审核交付

交付：

- TTS 配音。
- 字幕生成。
- FFmpeg 合成 final.mp4。
- 审核报告和授权 manifest。

技术范围：

- text_to_speech_handler。
- subtitle/composer worker。
- delivery package。

### 第 5-6 周：工作台和工业化

交付：

- 用户侧 AI 漫剧项目工作台。
- 分镜编辑。
- 角色参考图复用。
- 批量多集生成。
- 成本报表和运营模板。

## 14. 风险与应对

角色一致性风险：

- 使用角色身份卡、参考图、固定否定词、四视图锚点。
- 对关键角色建立项目级视觉圣经。

视频质量不稳定：

- 单镜头短时长生成。
- 首尾帧控制。
- 失败只重试单镜头。

成本不可控：

- 高成本节点前预估积分并确认。
- 默认生成低清样片，确认后升档。
- 管理侧设置每项目最大重试次数和最大积分。

版权风险：

- 保留模型调用记录、素材来源、授权文件。
- 禁止用户上传无权改编的 IP 素材作为商用输出。
- 成片默认加入 AIGC 辅助创作声明。

平台内容风险：

- 审核节点前置敏感题材过滤。
- 输出发布文案时自动附带平台适配建议。

## 15. 推荐落地路径

优先选择“先策划，后成片”的路径：

1. 先把 `ai_comic_drama_agent` 做成稳定的策划/分镜/Prompt 工具。
2. 再接图片生成，解决角色定妆和关键帧。
3. 最后接图生视频、TTS、合成。

这样可以最快上线一个可运营工具，同时给后续工业化工作流留下清晰扩展点。

首个上线版本建议命名：

- 工具名称：AI 漫剧工作流生成器。
- 工作流模板：AI_COMIC_DRAMA_STANDARD。
- 输出粒度：策划案、分镜脚本、角色卡、图生视频 Prompt。
- 后续升级：一键样片、单集成片、多集工业化。

