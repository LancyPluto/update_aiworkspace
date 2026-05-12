from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的电商直播话术策划，擅长根据直播主题、平台、人群、商品/服务、"
    "核心卖点和直播目标，生成可直接口播和执行的直播话术。请严格使用用户提供的信息，"
    "不要编造价格、库存、销量、功效、资质、检测数据或平台背书，不要使用绝对化广告词。"
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
        "请根据以下信息生成一套可用于直播间执行的直播话术：",
        "",
        f"- 直播主题：{_value(params, 'liveTheme')}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 目标人群：{_value(params, 'targetAudience')}",
        f"- 产品/服务：{_value(params, 'productOrService')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
        f"- 直播目标：{_value(params, 'liveGoal')}",
        f"- 直播时长：{_value(params, 'liveDuration')}",
        f"- 话术风格：{_value(params, 'tone')}",
    ]

    promotion = _value(params, "promotionMechanism")
    if promotion:
        lines.append(f"- 活动机制（仅使用以下已确认信息）：{promotion}")

    interaction = _value(params, "interactionFocus")
    if interaction:
        lines.append(f"- 互动重点：{interaction}")

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
            "- 话术要适合直播间实时口播，句子自然、有节奏，不要写成生硬说明书。",
            "- 产品讲解要围绕用户已提供的卖点，不要扩写未提供的材质、效果、资质或检测数据。",
            "- 互动引导要能激发评论、停留和咨询，围绕目标人群常见问题展开。",
            "- 促单转化话术可以强调行动，但不得编造价格、库存、销量、限时优惠或平台背书。",
            "- 异议处理至少覆盖尺码/适用性、价格/价值、材质/效果、售后/服务等方向，不能承诺未提供事项。",
            "- 直播节奏安排要按直播时长拆分阶段，便于主播和助播执行。",
            "- 不要使用“最强、第一、永久、100%”等绝对化或高风险表达。",
            "- 如果提供了避免使用词，输出中不要出现这些词。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 开场话术",
            "...",
            "",
            "## 产品讲解话术",
            "- ...",
            "- ...",
            "",
            "## 互动引导话术",
            "- ...",
            "- ...",
            "",
            "## 促单转化话术",
            "...",
            "",
            "## 异议处理话术",
            "- 问：...",
            "  答：...",
            "",
            "## 直播节奏安排",
            "1. ...",
            "2. ...",
            "",
            "## 合规提醒",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
