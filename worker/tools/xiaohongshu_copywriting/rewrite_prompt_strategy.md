# 二次生成 / 反馈优化 Prompt 拼装方案

## 目标

- 用户对首版结果不满意时，不是重新“裸生成”，而是基于原始输入和反馈做定向优化。
- 保持当前输入优先级最高，避免历史偏好覆盖本轮需求。

## 一、推荐输入结构

```json
{
  "params": {
    "productName": "五一肩颈护理套餐",
    "targetCustomer": "久坐上班族女性",
    "style": "种草",
    "sellingPoints": "价格划算、放松明显、适合节前放松",
    "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区"
  },
  "generationMode": "REWRITE",
  "rewriteContext": {
    "lastFeedback": ["moreColloquial", "lessAdvertising"],
    "customFeedback": "不要太像活动宣传，更像朋友真实分享",
    "previousResult": {
      "titles": [
        "五一前放松一下真的太重要了",
        "久坐党终于找到肩颈放松的好选择",
        "假期前我先去把肩颈状态救回来了"
      ],
      "content": "上一版正文",
      "hashtags": ["#肩颈护理", "#门店种草"],
      "cta": "上一版行动引导"
    }
  },
  "userPreference": {
    "preferredStyle": "口语化",
    "preferredLength": "中等",
    "avoidWords": ["顶级", "闭眼冲"],
    "emphasizePoints": ["真实体验", "价格友好"]
  }
}
```

## 二、优先级规则

- `当前输入 params` 优先级最高
- `rewriteContext` 次高
- `userPreference` 最后兜底

## 三、Prompt 拼装思路

建议仍保留：

- `system prompt`: 定义角色、平台风格、输出格式、禁止行为
- `user prompt`: 定义本次输入、上一版结果、反馈要求、本次改写目标

## 四、推荐的 System Prompt 追加规则

首版生成与二次生成共用同一个基础 system prompt，不建议为二次生成完全重写系统角色。

如果是二次生成，只在 system prompt 末尾追加一段约束：

```text
如果本次任务为二次优化，请优先根据“本轮反馈要求”修改上一版内容，避免完全偏离原始输入，不要无关扩写。
```

## 五、推荐的 User Prompt 拼装模板

### 1. 初次生成模板

```text
请根据以下信息生成一篇适合发布在小红书的平台风格文案：

- 产品/服务名称：{productName}
- 目标用户：{targetCustomer}
- 文案风格：{style}
- 核心卖点：{sellingPoints}
- 补充说明：{extraInfo}

请严格按照以下 Markdown 结构输出：
## 标题建议
## 正文
## 标签建议
## 行动引导
```

### 2. 二次生成模板

```text
请基于以下原始信息，对上一版小红书文案进行定向优化：

【原始输入】
- 产品/服务名称：{productName}
- 目标用户：{targetCustomer}
- 文案风格：{style}
- 核心卖点：{sellingPoints}
- 补充说明：{extraInfo}

【上一版结果】
标题：
{previous_titles}

正文：
{previous_content}

标签：
{previous_hashtags}

行动引导：
{previous_cta}

【本轮反馈要求】
结构化反馈：{feedback_instructions}
补充反馈：{custom_feedback}

【用户长期偏好】
- 偏好风格：{preferred_style}
- 偏好篇幅：{preferred_length}
- 避免词：{avoid_words}
- 希望突出：{emphasize_points}

请按以下要求优化：
- 优先保留原始输入的核心信息，不要偏题
- 重点根据“本轮反馈要求”修改，而不是完全重写成无关内容
- 如果本轮反馈和长期偏好冲突，以本轮反馈为准
- 不要编造未提供的信息
- 继续严格按固定 Markdown 结构输出

请严格按照以下 Markdown 结构输出：
## 标题建议
## 正文
## 标签建议
## 行动引导
```

## 六、结构化反馈到自然语言指令的映射

### 推荐映射表

- `moreColloquial` -> `请让表达更口语化、更自然，减少书面化表达`
- `lessAdvertising` -> `请减少广告感和促销感，避免硬广语气`
- `highlightSellingPoints` -> `请更明确地突出核心卖点，让内容更具体`
- `shorterLength` -> `请缩短正文篇幅，保留核心信息即可`
- `retitle` -> `请更换标题风格，让标题更有吸引力但不要夸张`

## 七、推荐拼装步骤

- 读取 `params`
- 判断 `generationMode`
- 如果是 `INITIAL`，直接走首版模板
- 如果是 `REWRITE`，提取 `previousResult`
- 把 `lastFeedback` 映射为自然语言优化指令
- 再补入 `customFeedback`
- 如果有 `userPreference`，作为最后兜底补充
- 生成完整 prompt 后交给模型

## 八、推荐的伪代码

```python
def build_rewrite_prompt(params, rewrite_context=None, user_preference=None):
    if not rewrite_context:
        return build_initial_prompt(params)

    feedback_map = {
        "moreColloquial": "请让表达更口语化、更自然，减少书面化表达",
        "lessAdvertising": "请减少广告感和促销感，避免硬广语气",
        "highlightSellingPoints": "请更明确地突出核心卖点，让内容更具体",
        "shorterLength": "请缩短正文篇幅，保留核心信息即可",
        "retitle": "请更换标题风格，让标题更有吸引力但不要夸张",
    }

    feedback_instructions = [
        feedback_map[item]
        for item in rewrite_context.get("lastFeedback", [])
        if item in feedback_map
    ]

    return REWRITE_TEMPLATE.format(
        productName=params.get("productName", ""),
        targetCustomer=params.get("targetCustomer", ""),
        style=params.get("style", ""),
        sellingPoints=params.get("sellingPoints", ""),
        extraInfo=params.get("extraInfo", ""),
        previous_titles="\n".join(rewrite_context["previousResult"].get("titles", [])),
        previous_content=rewrite_context["previousResult"].get("content", ""),
        previous_hashtags=" ".join(rewrite_context["previousResult"].get("hashtags", [])),
        previous_cta=rewrite_context["previousResult"].get("cta", ""),
        feedback_instructions="\n".join(feedback_instructions),
        custom_feedback=rewrite_context.get("customFeedback", ""),
        preferred_style=(user_preference or {}).get("preferredStyle", ""),
        preferred_length=(user_preference or {}).get("preferredLength", ""),
        avoid_words="、".join((user_preference or {}).get("avoidWords", [])),
        emphasize_points="、".join((user_preference or {}).get("emphasizePoints", [])),
    )
```

## 九、V1 实施建议

- V1 不做复杂记忆检索
- V1 只接受结构化反馈和上一版结果
- V1 只做一轮一轮可控改写
- V1 避免无限拼接历史结果，防止 prompt 变长和记忆污染
