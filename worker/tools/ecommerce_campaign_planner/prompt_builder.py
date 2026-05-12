from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的电商活动策划助手。你需要根据用户输入生成可执行的活动方案，"
    "覆盖目标拆解、阶段节奏、玩法设计、资源分配、投放建议和复盘指标。"
    "不要编造未提供的数据、政策、平台官方资源或不合规承诺。"
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
        "请根据以下信息生成电商活动方案：",
        "",
        f"- 活动目标：{_value(params, 'campaignGoal')}",
        f"- 活动主题：{_value(params, 'campaignName')}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 活动周期：{_value(params, 'campaignPeriod')}",
        f"- 目标人群：{_value(params, 'targetAudience')}",
        f"- 活动货品范围：{_value(params, 'productScope')}",
    ]

    budget_range = _value(params, "budgetRange")
    if budget_range:
        lines.append(f"- 预算范围：{budget_range}")

    discount_policy = _value(params, "discountPolicy")
    if discount_policy:
        lines.append(f"- 优惠机制：{discount_policy}")

    channel_resources = _value(params, "channelResources")
    if channel_resources:
        lines.append(f"- 资源位/渠道：{channel_resources}")

    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充信息：{extra_info}")

    lines.extend(
        [
            "",
            "请严格遵循以下要求：",
            "- 方案要可执行，给出阶段节奏和关键动作。",
            "- 资源分配建议应结合输入的预算和渠道信息。",
            "- 不要给出违规营销建议，不要夸大承诺。",
            "- 指标建议要可跟踪，便于复盘。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 活动目标与策略概览",
            "...",
            "",
            "## 活动节奏与阶段安排",
            "...",
            "",
            "## 玩法设计与资源分配",
            "...",
            "",
            "## 投放与转化建议",
            "...",
            "",
            "## 核心指标与复盘建议",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
