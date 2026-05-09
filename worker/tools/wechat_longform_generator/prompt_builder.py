from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的公众号长文写作助手。你需要根据用户输入生成结构清晰、可读性强、"
    "可直接二次编辑发布的长文草稿。请严格使用给定信息，不要编造事实，不要输出额外解释。"
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
        "请根据以下输入生成一篇公众号长文：",
        "",
        f"- 文章主题：{_value(params, 'topic')}",
        f"- 目标读者：{_value(params, 'audience')}",
        f"- 写作目标：{_value(params, 'goal')}",
        f"- 语气风格：{_value(params, 'tone')}",
        f"- 篇幅档位：{_value(params, 'lengthLevel')}",
        f"- 核心要点：{_value(params, 'keyPoints')}",
    ]

    cta = _value(params, "cta")
    if cta:
        lines.append(f"- 行动引导：{cta}")

    extra_info = _value(params, "extraInfo")
    if extra_info:
        lines.append(f"- 补充信息：{extra_info}")

    lines.extend(
        [
            "",
            "请严格遵循以下要求：",
            "- 内容逻辑完整，避免空话套话。",
            "- 语言风格与目标读者匹配，避免明显营销腔。",
            "- 不要编造未提供的事实、数据、地址、价格、政策信息。",
            "- 适当分段并加小标题，确保可读性。",
            "",
            _output_format_block(),
        ]
    )

    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "# 标题",
            "...",
            "",
            "## 导语",
            "...",
            "",
            "## 正文",
            "### 小节1",
            "...",
            "### 小节2",
            "...",
            "",
            "## 总结",
            "...",
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
