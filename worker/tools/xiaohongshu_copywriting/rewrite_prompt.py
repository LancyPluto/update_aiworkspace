from typing import Any


REWRITE_SYSTEM_APPENDIX = (
    "如果本次任务为二次优化，请优先根据“本轮反馈要求”修改上一版内容，"
    "避免完全偏离原始输入，不要无关扩写。"
)

FEEDBACK_INSTRUCTION_MAP = {
    "moreColloquial": "请让表达更口语化、更自然，减少书面化表达。",
    "lessAdvertising": "请减少广告感和促销感，避免硬广语气。",
    "highlightSellingPoints": "请更明确地突出核心卖点，让内容更具体。",
    "shorterLength": "请缩短正文篇幅，保留核心信息即可。",
    "retitle": "请更换标题风格，让标题更有吸引力但不要夸张。",
}


def build_system_prompt(system_prompt: str, *, generation_mode: str = "INITIAL") -> str:
    normalized = system_prompt.strip()
    if generation_mode != "REWRITE":
        return normalized
    if not normalized:
        return REWRITE_SYSTEM_APPENDIX
    return f"{normalized}\n\n{REWRITE_SYSTEM_APPENDIX}"


def build_user_prompt(
    params: dict[str, Any],
    *,
    generation_mode: str = "INITIAL",
    rewrite_context: dict[str, Any] | None = None,
    user_preference: dict[str, Any] | None = None,
) -> str:
    if generation_mode != "REWRITE":
        return _build_initial_user_prompt(params)
    return _build_rewrite_user_prompt(
        params,
        rewrite_context=rewrite_context or {},
        user_preference=user_preference or {},
    )


def build_prompt_payload(context: dict[str, Any]) -> dict[str, str]:
    generation_mode = str(context.get("generationMode") or "INITIAL").upper()
    params = context.get("params") or {}
    rewrite_context = context.get("rewriteContext") or {}
    user_preference = context.get("userPreference") or {}

    return {
        "system_prompt": build_system_prompt(
            context.get("systemPrompt", ""),
            generation_mode=generation_mode,
        ),
        "user_prompt": build_user_prompt(
            params,
            generation_mode=generation_mode,
            rewrite_context=rewrite_context,
            user_preference=user_preference,
        ),
    }


def _build_initial_user_prompt(params: dict[str, Any]) -> str:
    lines = [
        "请根据以下信息生成一篇适合发布在小红书的平台风格文案：",
        "",
        f"- 产品/服务名称：{_value(params, 'productName')}",
        f"- 目标用户：{_value(params, 'targetCustomer')}",
        f"- 文案风格：{_value(params, 'style')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
    ]
    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充说明：{extra_info}")

    lines.extend(
        [
            "",
            "请满足以下要求：",
            "- 输出 3 个标题建议，标题要有吸引力但不过度夸张。",
            "- 正文要自然、有代入感，适合直接修改后发布。",
            "- 标签建议给 5 到 8 个，尽量贴合内容和目标人群。",
            "- 行动引导只写 1 条，语气自然，不要生硬促销。",
            "- 紧扣用户提供的信息，不要编造未提供的价格、功效、地址、资质或活动细节。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _build_rewrite_user_prompt(
    params: dict[str, Any],
    *,
    rewrite_context: dict[str, Any],
    user_preference: dict[str, Any],
) -> str:
    previous_result = rewrite_context.get("previousResult") or {}
    previous_titles = previous_result.get("titles") or []
    previous_hashtags = previous_result.get("hashtags") or []
    feedback_instructions = _map_feedback_instructions(rewrite_context.get("lastFeedback") or [])

    lines = [
        "请基于以下原始信息，对上一版小红书文案进行定向优化：",
        "",
        "【原始输入】",
        f"- 产品/服务名称：{_value(params, 'productName')}",
        f"- 目标用户：{_value(params, 'targetCustomer')}",
        f"- 文案风格：{_value(params, 'style')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
    ]
    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充说明：{extra_info}")

    lines.extend(
        [
            "",
            "【上一版结果】",
            "标题：",
            "\n".join(f"- {title}" for title in previous_titles) or "无",
            "",
            "正文：",
            _string_or_default(previous_result.get("content"), "无"),
            "",
            "标签：",
            " ".join(previous_hashtags) or "无",
            "",
            "行动引导：",
            _string_or_default(previous_result.get("cta"), "无"),
            "",
            "【本轮反馈要求】",
            "结构化反馈：",
            "\n".join(f"- {item}" for item in feedback_instructions) or "- 无",
            f"补充反馈：{_string_or_default(rewrite_context.get('customFeedback'), '无')}",
            "",
            "【用户长期偏好】",
            f"- 偏好风格：{_string_or_default(user_preference.get('preferredStyle'), '无')}",
            f"- 偏好篇幅：{_string_or_default(user_preference.get('preferredLength'), '无')}",
            f"- 避免词：{_join_list(user_preference.get('avoidWords')) or '无'}",
            f"- 希望突出：{_join_list(user_preference.get('emphasizePoints')) or '无'}",
            "",
            "请按以下要求优化：",
            "- 优先保留原始输入的核心信息，不要偏题。",
            "- 重点根据“本轮反馈要求”修改，而不是完全重写成无关内容。",
            "- 如果本轮反馈和长期偏好冲突，以本轮反馈为准。",
            "- 不要编造未提供的信息。",
            "- 继续严格按固定 Markdown 结构输出。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _map_feedback_instructions(feedback_items: list[Any]) -> list[str]:
    instructions: list[str] = []
    for item in feedback_items:
        if not isinstance(item, str):
            continue
        instruction = FEEDBACK_INSTRUCTION_MAP.get(item)
        if instruction:
            instructions.append(instruction)
    return instructions


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面格式输出：",
            "## 标题建议",
            "1. ...",
            "2. ...",
            "3. ...",
            "",
            "## 正文",
            "...",
            "",
            "## 标签建议",
            "#...",
            "#...",
            "#...",
            "",
            "## 行动引导",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()


def _string_or_default(value: Any, default: str) -> str:
    text = "" if value is None else str(value).strip()
    return text or default


def _join_list(value: Any) -> str:
    if not isinstance(value, list):
        return ""
    return "、".join(str(item).strip() for item in value if str(item).strip())
