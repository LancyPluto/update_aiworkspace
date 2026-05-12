from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的线下门店活动策划助手，擅长根据门店类型、活动目标、活动周期、预算、"
    "主推商品/服务、目标客群和可用资源，生成可落地执行的门店活动方案。"
    "请严格使用用户提供的信息，不要编造库存、折扣、客流、销售额、会员数据或平台数据。"
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
        "请根据以下信息生成一套可落地的线下门店活动方案：",
        "",
        f"- 门店类型：{_value(params, 'storeType')}",
        f"- 门店名称/定位：{_value(params, 'storeNameOrPositioning')}",
        f"- 活动目标：{_value(params, 'campaignGoal')}",
        f"- 活动主题：{_value(params, 'campaignTheme')}",
        f"- 活动周期：{_value(params, 'campaignPeriod')}",
        f"- 目标客群：{_value(params, 'targetCustomers')}",
        f"- 主推商品/服务：{_value(params, 'productsOrServices')}",
        f"- 预算范围：{_value(params, 'budgetRange')}",
    ]

    available_resources = _value(params, "availableResources")
    if available_resources:
        lines.append(f"- 可用资源：{available_resources}")

    promotion = _value(params, "promotionMechanism")
    if promotion:
        lines.append(f"- 活动机制（仅基于已提供内容优化）：{promotion}")

    channels = _value(params, "channels")
    if channels:
        lines.append(f"- 触达渠道：{channels}")

    constraints = _value(params, "constraints")
    if constraints:
        lines.append(f"- 限制条件：{constraints}")

    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充信息：{extra_info}")

    lines.extend(
        [
            "",
            "请严格遵循以下要求：",
            "- 方案必须适合线下门店执行，不能只给线上投放建议。",
            "- 活动玩法要结合门店人力、预算、渠道和主推商品/服务。",
            "- 执行排期要按活动周期拆分阶段，明确预热、正式执行和复盘动作。",
            "- 物料与话术建议要能直接给店员、社群、朋友圈或门店海报使用。",
            "- 人员分工要考虑店长、店员、导购或兼职等角色，若用户未提供具体人数则用通用角色。",
            "- 复盘指标要包含到店、成交、客单、会员、复购或线索等可观察指标。",
            "- 不要编造库存、折扣、客流、销售额、会员数据或平台数据。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 活动总览",
            "- ...",
            "",
            "## 客群策略",
            "- ...",
            "",
            "## 活动玩法设计",
            "1. ...",
            "2. ...",
            "",
            "## 执行排期",
            "1. ...",
            "2. ...",
            "",
            "## 物料与话术建议",
            "- ...",
            "",
            "## 人员分工",
            "- ...",
            "",
            "## 复盘指标",
            "- ...",
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
