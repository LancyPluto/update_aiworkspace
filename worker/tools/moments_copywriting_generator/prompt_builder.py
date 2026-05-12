from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的朋友圈文案助手。请根据用户输入生成简洁、自然、可直接发布的朋友圈文案，"
    "不要编造未提供的信息，不要输出与任务无关的解释。文案正文必须像真实朋友圈内容，"
    "不要出现“文案正文”“表情建议”“话题标签”“行动引导”等栏目词。"
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
        "请根据以下信息生成朋友圈文案：",
        "",
        f"- 文案主题：{_value(params, 'topic')}",
        f"- 目标人群：{_value(params, 'targetAudience')}",
        f"- 文案风格：{_value(params, 'tone')}",
        f"- 发布场景：{_value(params, 'scene')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
        f"- 文案长度：{_value(params, 'lengthLevel')}",
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
            "请严格按以下 Markdown 结构输出，结构标题仅供系统解析，正文内容本身必须可直接复制发布：",
            "## 文案正文",
            "输出一段自然朋友圈文案，不要带小标题，不要解释写作思路。",
            "",
            "## 表情建议",
            "只输出适合拼接在文案后的表情符号，多个表情连续排列，不要说明。",
            "",
            "## 话题标签",
            "输出 2-4 个话题标签，用空格分隔。",
            "",
            "## 行动引导",
            "输出一句可直接放在朋友圈末尾的行动引导，不要带标题。",
        ]
    )
    return "\n".join(lines)


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
