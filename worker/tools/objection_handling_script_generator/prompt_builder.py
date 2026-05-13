from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的销售异议处理话术助手，擅长根据客户异议、沟通渠道、销售阶段、"
    "产品/服务价值和处理目标，生成自然、克制、可执行的回应话术。"
    "请先承接客户顾虑，再补充价值和追问引导；不要争辩、压迫、诱导，"
    "不要编造客户历史、价格优惠、库存、服务权益、效果承诺或资质背书。"
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
        "请根据以下信息生成一套客户异议处理话术：",
        "",
        f"- 客户异议原话：{_value(params, 'objectionText')}",
        f"- 异议类型：{_value(params, 'objectionType')}",
        f"- 沟通渠道：{_value(params, 'communicationChannel')}",
        f"- 客户类型：{_value(params, 'customerType')}",
        f"- 销售阶段：{_value(params, 'salesStage')}",
        f"- 产品/服务：{_value(params, 'productOrService')}",
        f"- 核心价值/卖点：{_value(params, 'valueProposition')}",
        f"- 处理目标：{_value(params, 'handlingGoal')}",
        f"- 话术风格：{_value(params, 'tone')}",
    ]

    customer_context = _value(params, "customerContext")
    if customer_context:
        lines.append(f"- 客户背景：{customer_context}")

    proof_points = _value(params, "proofPoints")
    if proof_points:
        lines.append(f"- 可用佐证（仅使用以下已提供内容）：{proof_points}")

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
            "- 先判断异议背后的可能顾虑，再给回应策略。",
            "- 推荐话术要先认可客户感受，再补充价值点或可验证信息，最后用轻量问题继续沟通。",
            "- 追问引导要帮助澄清真实顾虑，不连续逼单。",
            "- 替代表达至少给出更温和和更直接两种版本。",
            "- 后续动作建议要给出可执行步骤，不建议高频打扰。",
            "- 不要编造客户历史、购买意愿、价格优惠、库存、服务权益、效果承诺或资质背书。",
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
            "## 异议判断",
            "- ...",
            "",
            "## 回应策略",
            "- ...",
            "",
            "## 推荐话术",
            "...",
            "",
            "## 追问引导",
            "- ...",
            "",
            "## 替代表达",
            "- 更温和：...",
            "- 更直接：...",
            "",
            "## 后续动作建议",
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
