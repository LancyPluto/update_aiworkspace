from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的电商商品标题优化助手，擅长根据商品信息、平台场景、目标人群和核心卖点，"
    "生成搜索友好且有转化力的商品标题。请严格使用用户提供的信息，不要编造材质、功效、"
    "资质、检测数据或平台背书，不要使用绝对化广告词。"
)


def build_prompt_payload(context: dict[str, Any]) -> dict[str, str]:
    params = context.get("params") or {}
    return {
        "system_prompt": _build_system_prompt(context.get("systemPrompt", "")),
        "user_prompt": _build_user_prompt(params),
    }


def _build_system_prompt(system_prompt: str) -> str:
    normalized = (system_prompt or "").strip()
    return normalized or DEFAULT_SYSTEM_PROMPT


def _build_user_prompt(params: dict[str, Any]) -> str:
    lines = [
        "请根据以下商品信息优化电商商品标题：",
        "",
        f"- 商品名称：{_value(params, 'productName')}",
        f"- 商品类目：{_value(params, 'category')}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 目标人群：{_value(params, 'targetCustomer')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
        f"- 标题风格：{_value(params, 'style')}",
    ]

    keywords = _value(params, "keywords")
    if keywords:
        lines.append(f"- 希望包含关键词：{keywords}")

    avoid_words = _value(params, "avoidWords")
    if avoid_words:
        lines.append(f"- 避免使用词：{avoid_words}")

    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充信息：{extra_info}")

    lines.extend(
        [
            "",
            "请严格遵循以下要求：",
            "- 输出 3 个优化标题，并从中选择 1 个最推荐标题。",
            "- 标题要兼顾搜索关键词、商品卖点和点击转化。",
            "- 不要使用“最强、第一、永久、100%”等绝对化或高风险表达。",
            "- 不要编造未提供的材质、功效、检测资质、品牌背书或价格信息。",
            "- 如果提供了避免使用词，输出标题中不要出现这些词。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 优化标题",
            "1. ...",
            "2. ...",
            "3. ...",
            "",
            "## 推荐标题",
            "...",
            "",
            "## 优化理由",
            "- ...",
            "- ...",
            "",
            "## 关键词建议",
            "#...",
            "#...",
            "",
            "## 使用提醒",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
