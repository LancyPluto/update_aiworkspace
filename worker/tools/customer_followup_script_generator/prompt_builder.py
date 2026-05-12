from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的销售与私域客户跟进话术助手，擅长根据客户类型、跟进阶段、沟通渠道、"
    "客户痛点、产品/服务价值和跟进目标，生成自然、克制、可执行的跟进话术。"
    "请严格使用用户提供的信息，不要编造客户历史、付款意愿、优惠、库存、承诺、资质或服务权益。"
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
        "请根据以下信息生成一套客户跟进话术：",
        "",
        f"- 客户类型：{_value(params, 'customerType')}",
        f"- 跟进阶段：{_value(params, 'followupStage')}",
        f"- 沟通渠道：{_value(params, 'communicationChannel')}",
        f"- 客户痛点/需求：{_value(params, 'customerPainPoints')}",
        f"- 产品/服务：{_value(params, 'productOrService')}",
        f"- 核心价值/卖点：{_value(params, 'valueProposition')}",
        f"- 跟进目标：{_value(params, 'followupGoal')}",
        f"- 话术风格：{_value(params, 'tone')}",
    ]

    customer_profile = _value(params, "customerProfile")
    if customer_profile:
        lines.append(f"- 客户画像：{customer_profile}")

    last_interaction = _value(params, "lastInteraction")
    if last_interaction:
        lines.append(f"- 上次沟通情况：{last_interaction}")

    objection = _value(params, "objectionOrConcern")
    if objection:
        lines.append(f"- 客户顾虑：{objection}")

    call_to_action = _value(params, "callToAction")
    if call_to_action:
        lines.append(f"- 行动引导：{call_to_action}")

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
            "- 话术要适配沟通渠道：微信/企微用短句，电话用可口播表达，短信要简洁。",
            "- 不要编造客户历史、购买意愿、价格优惠、库存、售后权益、资质或承诺。",
            "- 跟进话术要自然克制，避免骚扰式、压迫式、诱导式表达。",
            "- 异议处理要围绕用户提供的顾虑，不承诺未提供事项。",
            "- 触达节奏建议要给出合理间隔和下一步动作，不建议高频打扰。",
            "- 跟进记录建议要提示记录客户关注点、顾虑、下一步动作和回访时间。",
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
            "## 客户情况分析",
            "- ...",
            "",
            "## 跟进话术",
            "...",
            "",
            "## 异议处理话术",
            "- 问：...",
            "  答：...",
            "",
            "## 触达节奏建议",
            "1. ...",
            "2. ...",
            "",
            "## 跟进记录建议",
            "- ...",
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
