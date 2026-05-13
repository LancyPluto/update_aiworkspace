from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的短视频内容选题策划，擅长根据账号定位、平台、人群、内容目标和产品信息，"
    "生成具体、可拍、可转化的短视频选题。请不要编造实时热点、平台数据、销量、功效、"
    "资质或检测信息，不要输出低俗擦边或绝对化表达。"
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
        "请根据以下信息生成一组可执行的短视频选题：",
        "",
        f"- 账号/品牌定位：{_value(params, 'accountPositioning')}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 目标人群：{_value(params, 'targetAudience')}",
        f"- 内容目标：{_value(params, 'contentGoal')}",
        f"- 选题方向：{_value(params, 'topicDirection')}",
        f"- 选题数量：{_value(params, 'topicCount')}",
        f"- 内容风格：{_value(params, 'stylePreference')}",
    ]

    product_or_service = _value(params, "productOrService")
    if product_or_service:
        lines.append(f"- 产品/服务信息：{product_or_service}")

    avoid_topics = _value(params, "avoidTopics")
    if avoid_topics:
        lines.append(f"- 避免方向：{avoid_topics}")

    keywords = _value(params, "keywords")
    if keywords:
        lines.append(f"- 希望包含关键词：{keywords}")

    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充信息：{extra_info}")

    lines.extend(
        [
            "",
            "请严格遵循以下要求：",
            "- 选题必须具体可拍，能直接交给内容同事继续写脚本。",
            "- 选题应贴合账号定位、目标平台、目标人群和内容目标，不要泛泛而谈。",
            "- 推荐优先级要说明先做哪些选题以及原因。",
            "- 创作角度要给出痛点、场景、冲突、对比或教程等可执行方向。",
            "- 标题与标签建议要贴合平台搜索和推荐语境。",
            "- 不要编造实时热点、平台数据、销量、功效、检测数据或资质背书。",
            "- 如果提供了避免方向，输出中不要包含这些方向。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 选题清单",
            "1. ...",
            "2. ...",
            "3. ...",
            "",
            "## 推荐优先级",
            "- ...",
            "",
            "## 创作角度",
            "- ...",
            "- ...",
            "",
            "## 标题与标签建议",
            "- 标题：...",
            "- 标签：#... #...",
            "",
            "## 执行建议",
            "...",
            "",
            "## 风险提醒",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
