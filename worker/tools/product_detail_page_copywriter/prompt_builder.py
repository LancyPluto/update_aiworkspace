from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的电商商品详情页文案策划，擅长把商品卖点转化成长图文详情结构。"
    "请严格使用用户提供的商品信息与参数，不要编造材质、功效、检测数值、资质背书或价格；"
    "不要使用绝对化广告词；若用户给出避免使用词，正文中不得出现。"
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
        "请根据以下信息，为电商商品详情页撰写成套文案（含结构与主文）：",
        "",
        f"- 商品名称：{_value(params, 'productName')}",
        f"- 商品类目：{_value(params, 'category')}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 目标人群：{_value(params, 'targetCustomer')}",
        f"- 核心卖点：{_value(params, 'sellingPoints')}",
        f"- 文案调性：{_value(params, 'tone')}",
        f"- 篇幅偏好：{_value(params, 'lengthPreference')}",
    ]

    specs = _value(params, "specsOrAttributes")
    if specs:
        lines.append(f"- 规格/参数要点（仅复述以下信息，勿扩写未给出的数据）：{specs}")

    keywords = _value(params, "keywords")
    if keywords:
        lines.append(f"- 希望自然融入的关键词：{keywords}")

    avoid = _value(params, "avoidWords")
    if avoid:
        lines.append(f"- 避免使用词（全文不得出现）：{avoid}")

    extra = _value(params, "extraInfo")
    if extra:
        lines.append(f"- 补充信息：{extra}")

    lines.extend(
        [
            "",
            "请严格遵循：",
            "- 卖点提炼用短句或条目，突出用户已提供卖点，不夸大。",
            "- 详情页主文案按「首屏吸引—中段展开—收尾行动」逻辑写成可读长文，可直接贴入详情描述区再配图。",
            "- 分段排版建议用有序列表，对应详情页从上到下的模块顺序（如首屏、痛点、卖点、参数、口碑、服务、FAQ 等），按平台习惯取舍。",
            "- 关键词建议给出若干 #话题词 或短词，便于搜索与内部标注。",
            "- 合规与发布提醒需提示用户核对材质、功效、防晒/美妆等宣称与资质材料。",
            "- 可选字段若为空则忽略，不要在输出中写「未提供」。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出（不要额外层级标题、不要代码围栏包裹全文）：",
            "## 卖点提炼",
            "- ...",
            "",
            "## 详情页主文案",
            "...",
            "",
            "## 分段排版建议",
            "1. ...",
            "2. ...",
            "",
            "## 关键词建议",
            "#...",
            "",
            "## 合规与发布提醒",
            "...",
        ]
    )


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
