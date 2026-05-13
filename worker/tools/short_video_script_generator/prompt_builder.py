from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个专业的短视频脚本策划，擅长根据主题、平台、人群、卖点和转化目标，"
    "生成可拍摄的短视频脚本。请严格使用用户提供的信息，不要编造功效、价格、"
    "资质、检测数据或平台背书，不要使用绝对化广告词。"
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
    video_topic = _value(params, "videoTopic")
    promotion_object = _value(params, "promotionObject")
    core_selling_points = _value(params, "coreSellingPoints")
    target_audience = _value(params, "targetAudience")

    lines = [
        "请根据以下信息生成一条可拍摄的短视频脚本：",
        "",
        f"- 视频主题：{video_topic}",
        f"- 目标平台：{_value(params, 'targetPlatform')}",
        f"- 目标人群：{target_audience}",
        f"- 推广对象：{promotion_object}",
        f"- 核心卖点：{core_selling_points}",
        f"- 脚本风格：{_value(params, 'scriptStyle')}",
        f"- 视频时长：{_value(params, 'videoLength')}",
    ]

    shooting_scenario = _value(params, "shootingScenario")
    if shooting_scenario:
        lines.append(f"- 拍摄场景：{shooting_scenario}")

    call_to_action = _value(params, "callToAction")
    if call_to_action:
        lines.append(f"- 行动引导：{call_to_action}")

    keywords = _value(params, "keywords")
    if keywords:
        lines.append(f"- 希望包含关键词：{keywords}")

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
            "- 所有内容都必须围绕“视频主题、推广对象、核心卖点”展开，不要擅自改成别的话题。",
            "- 开场钩子要在前 3 秒抓住注意力，直接对应目标人群痛点或好奇点，并点出视频主题或推广对象。",
            "- 分镜脚本必须包含镜头顺序或时间段，便于拍摄执行。",
            "- 口播文案要自然、可读、适合目标平台，不要像硬广说明书，且至少覆盖 2 个核心卖点或关键词。",
            "- 拍摄与剪辑建议要具体到镜头、画面、节奏或字幕呈现。",
            "- 标题与标签建议要贴合平台搜索和推荐语境，并优先复用视频主题、推广对象、关键词中的原词。",
            "- 不要编造未提供的功效、价格、资质、检测数据、库存、销量或平台背书。",
            "- 不要使用“最强、第一、永久、100%”等绝对化或高风险表达。",
            "- 如果提供了避免使用词，输出中不要出现这些词。",
            "- 如果输入信息不足，就基于现有信息做保守表达，不要用通用鸡汤、时间管理、职场励志等无关主题填充。",
            "- 禁止复述表单字段，不要输出“根据你提供的信息”之类说明。",
            "",
            "本次生成的内容锚点：",
            f"- 必须紧扣的视频主题：{video_topic}",
            f"- 必须服务的推广对象：{promotion_object}",
            f"- 必须体现的核心卖点：{core_selling_points}",
            f"- 必须面向的目标人群：{target_audience}",
            "",
            "生成前请先自检：如果你的开场钩子、口播文案、标题标签换掉以上锚点后依然成立，说明你写跑题了，请重写。",
            "",
            _output_format_block(),
        ]
    )
    return "\n".join(lines)


def _output_format_block() -> str:
    return "\n".join(
        [
            "请严格按照下面 Markdown 结构输出：",
            "## 开场钩子",
            "...",
            "",
            "## 分镜脚本",
            "1. ...",
            "2. ...",
            "",
            "## 口播文案",
            "...",
            "",
            "## 拍摄与剪辑建议",
            "- ...",
            "- ...",
            "",
            "## 标题与标签建议",
            "- 标题：...",
            "- 标签：#... #...",
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
