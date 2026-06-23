# 工具注册 Agent Skill 设计方案

## 结论

建议改成 **Skill 式渐进式披露 + 现有字段策略编译产物** 的混合方案。

不要把 admin-frontend 里现有的 `执行必填 / 缺失时追问用户 / 填充策略 / 风险等级` 全部删掉。它们已经被后端和 agent-service 用作真实执行边界：

- 后端会把字段编译成 JSON Schema 的 `required`、`x-user-required`、`x-agent-fill-strategy`、`x-risk-level`。
- agent-service 会据此判断缺参追问、默认值锁定、低风险补全和工具调用安全。
- `agent-service/app/runtime/tool_disclosure.py` 已经有工具目录的渐进式披露机制，只是当前还缺少“每个工具自己的能力说明书”。

更好的改法是：后台新增一层 **Tool Agent Skill Spec**，让运营或开发者维护一份像 Codex `SKILL.md` 一样的工具说明书；系统再把它编译成当前字段配置、schema 扩展、工具提示和运行时披露内容。

## 为什么会更好

当前字段级配置的问题不是能力不够，而是认知负担太重：

- 每个字段都要单独理解“接口必填”和“是否追问用户”的差别。
- 运营很难从字段列表看出这个工具整体什么时候该用、什么时候不该用、怎么补全参数、哪些场景必须确认。
- 类似工具会重复配置同一套 prompt 字段、参考图字段、比例、质量、数量、风险策略。
- 运行时只知道字段，不一定知道工具的产品边界和 fallback 规则。

Skill 式设计能把这些隐性判断写成显式规则：

- `description` 做第一层意图匹配。
- `When to use / When not to use` 固化工具边界。
- `Field policy` 统一声明哪些字段必须问、哪些字段默认填、哪些字段由上下文推断。
- `Fallback / Confirmation` 固化异常、成本、风险和二次确认策略。
- `Examples` 提供 LLM 生成、审核和回归测试的样本。

## 推荐的信息分层

### 第一层：工具目录摘要

用于所有工具列表和路由召回，保持短。

```yaml
toolCode: gpt_image_generation
displayName: GPT-image2 文生图
description: 根据文字提示生成图片，适合海报、插画、商品图和创意视觉；不适合视频、音频或需要真实授权素材的任务。
modalities: [image]
estimatedCreditCost: 10
```

### 第二层：Tool Agent Skill Spec

只在工具被选中、进入 shortlist、或 LLM 请求展开时加载。

```markdown
---
name: gpt-image-generation-agent
description: Use this tool when the user wants to generate a new image from text, with optional aspect ratio, count, quality and reference images. Do not use for video, audio, account operations, or editing an existing generated artifact unless the user asks for image editing.
---

# GPT-image2 文生图

## When to use

- 用户明确要生成图片、海报、插画、商品图、封面、角色图。
- 用户只给了简短主题时，可以扩写为生产级 prompt。

## When not to use

- 用户要视频、音频、PPT、文案或真实摄影检索。
- 用户只是在追问上一张图的结果位置，不要再次调用。

## Field policy

| fieldKey | role | executionRequired | userRequired | fillStrategy | risk |
| --- | --- | --- | --- | --- | --- |
| prompt | core_prompt | true | true | ask_user | LOW |
| aspectRatio | generation_option | true | false | default | LOW |
| count | generation_option | true | false | default | LOW |
| referenceImageUrl | optional_reference | false | false | infer_from_user | LOW |
| quality | cost_quality | true | false | default | MEDIUM |

## Clarification policy

- 只在核心主体、授权、账号、支付、发布、高成本或不可逆操作不明确时追问。
- 比例、数量、质量、风格强度可以使用默认值或上下文补全。

## Default policy

- aspectRatio 默认 `智能`。
- count 默认 `1`。
- quality 默认 `low`，除非用户明确要求高清、精细、商用成片。

## Output policy

- 返回任务 ID、预览资源和可继续编辑的上下文摘要。
- 不要把临时 URL 当作长期素材记忆。
```

### 第三层：编译后的执行配置

继续沿用当前数据库和 API 字段：

- `tool_field_items.required`
- `tool_field_items.execution_required`
- `tool_field_items.user_required`
- `tool_field_items.default_value`
- `tool_field_items.agent_fill_strategy`
- `tool_field_items.risk_level`
- `options_json` 里的 UI meta 和 core 标记

也就是说，Skill Spec 是维护源，字段配置是执行产物。

## 后台交互建议

把截图中的“Agent 交互策略”改成渐进式 UI：

1. 字段卡片默认只展示策略摘要，例如：`执行必填 · 默认填充 · 低风险`。
2. 高级配置折叠展示当前四个控件，保留给开发者精调。
3. 工具级新增「Agent Skill」页签：
   - 能力描述
   - 适用/不适用场景
   - 追问策略
   - 默认值策略
   - 风险/确认策略
   - 示例请求和期望工具参数
4. 新增「AI 生成 Agent Skill 草稿」按钮，只生成草稿，不自动发布。
5. 发布前展示 diff：Skill Spec 变更、字段策略变更、schema required 变更、风险等级变更。

## “工具注册 Agent 的 Skill 的 Skill”

可以做一个元 skill，建议命名为 `tool-agent-registrar`。它不是运行时工具，而是给后台注册工具时使用的生成规范。

### 触发场景

- 新增一个 AI 工具，需要生成工具字段、Agent 交互策略和运行时说明。
- 现有工具接入新模型，需要重新生成字段 schema 和默认值策略。
- 运营把 API 文档、模型参数或竞品工具说明贴进后台，希望一键生成可审核配置。
- 工具调用经常追问太多或误调用，需要优化策略。

### 输入

```json
{
  "toolName": "GPT-image2 文生图",
  "toolCode": "gpt_image_generation",
  "toolType": "image",
  "executionHandler": "openai_image",
  "providerModel": "gpt-image-2",
  "apiDocs": "可选：模型/API 参数说明",
  "operatorIntent": "希望用户一句话生成图，比例数量质量走默认，参考图可选",
  "existingFields": [],
  "costPolicy": "质量越高成本越高，默认 low",
  "riskPolicy": "不涉及账号和支付，参考图涉及用户上传素材"
}
```

### 输出

```json
{
  "skillSpecMarkdown": "...",
  "fields": [
    {
      "fieldKey": "prompt",
      "fieldName": "画面描述",
      "fieldType": "textarea",
      "required": true,
      "executionRequired": true,
      "userRequired": true,
      "agentFillStrategy": "ask_user",
      "riskLevel": "LOW",
      "isCore": true
    }
  ],
  "inputSchemaPatch": {
    "required": ["prompt", "aspectRatio", "count", "quality"]
  },
  "reviewNotes": [
    "quality 使用默认 low，避免无意提高成本。",
    "referenceImageUrl 不应强制追问，用户上传时自动填充。"
  ],
  "testCases": [
    {
      "userMessage": "帮我画一张赛博猫海报",
      "expectedTool": "gpt_image_generation",
      "expectedMissingFields": [],
      "expectedArgs": {
        "aspectRatio": "智能",
        "count": "1",
        "quality": "low"
      }
    }
  ]
}
```

### 生成规则

- LLM 只负责生成草稿和解释，不直接发布。
- 必须把字段分成三类：核心用户意图、可安全默认、必须人工确认。
- `executionRequired=true` 不等于 `userRequired=true`。接口必须有值但可以默认填的字段，不应打断对话。
- 涉及账号、支付、发布、授权、删除、外部系统写入、高成本、高风险质量档位时，默认 `userRequired=true` 或要求二次确认。
- 比例、数量、质量、风格强度等低风险生成参数，默认走 `default` 或 `infer_from_user`。
- prompt 类字段通常是唯一核心字段，除非该工具确实是多主体结构化任务。

## 推荐落地路径

### 阶段 1：不改运行时，只改维护体验

- 在 admin-frontend 增加工具级 Agent Skill 编辑区。
- 增加“根据当前字段生成 Skill Spec 草稿”。
- 增加“根据 Skill Spec 回填字段策略草稿”。
- 字段卡片里的 Agent 交互策略默认折叠，只展示摘要。

这个阶段风险最低，因为最终仍然写回现有字段表。

### 阶段 2：增加版本化和审核

- 新增 `tool_agent_skill_specs` 表，或先临时存入工具扩展配置 JSON。
- 每次 AI 生成都保存 draft 版本。
- 发布时生成 diff，并要求管理员确认。
- 发布后编译到字段项和 Agent tool descriptor。

### 阶段 3：运行时真正按需展开

- 工具目录只给 LLM 看短描述。
- shortlist 工具加载压缩 schema。
- 选中工具后加载 Tool Agent Skill Spec 的核心段落。
- 需要更详细的字段说明或示例时再展开 examples/reference。

这能和现有 `tool_disclosure.py` 的思路对齐。

## 最小实现边界

第一版不要做复杂文件系统 skill 插件，也不要把每个后台工具都落成真实 Codex skill 目录。建议先做数据库里的 **skill-like spec**：

- 更适合管理后台动态工具。
- 可以版本化、审核、回滚。
- 可以编译到现有字段表。
- 不需要改部署结构。

等这套稳定后，再考虑导出成类似：

```text
tool-skills/
  gpt-image-generation/
    SKILL.md
    references/api.md
    references/examples.md
```

## 需要新增的测试

- LLM 生成草稿不会把低风险默认字段标成必须追问。
- `executionRequired=true` 且 `userRequired=false` 的字段不会触发缺参追问。
- 默认值策略会进入工具调用参数，但用户显式指定时可以覆盖。
- 高风险字段会触发确认或追问。
- Skill Spec 发布前后生成的 JSON Schema `required` 和 `x-*` 元数据符合预期。

## 一句话判断

换成 skill 式渐进式披露会更好，也更利于后续维护；但正确姿势不是让 skill 取代现有字段策略，而是让 skill 成为上层“说明书和生成源”，再编译出当前运行时需要的字段、schema 和工具披露内容。
