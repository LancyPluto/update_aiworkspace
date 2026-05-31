import re
from dataclasses import dataclass

from app.core.schemas import RunContext, ToolDescriptor


TOOL_KEYWORDS: dict[str, tuple[str, ...]] = {
    "xiaohongshu_copywriting": ("小红书", "种草", "笔记", "爆款笔记"),
    "moments_copywriting_generator": ("朋友圈", "微信朋友圈", "私域文案"),
    "product_title_optimizer": ("商品标题", "标题优化", "电商标题"),
    "wechat_longform_generator": ("公众号", "微信长文", "长文"),
    "social_media_comment_insights_agent": ("社交媒体评论", "小红书评论", "抖音评论", "评论分析", "用户洞察", "产品建议"),
    "ofox_gpt_image2": ("图片生成", "文生图", "生图", "写真", "照片", "产品照", "商品主图", "老照片", "海报", "插画"),
    "kling_image_to_video": ("视频生成", "文生视频", "图生视频", "短视频", "宣传片", "成片", "转场视频"),
}


@dataclass(frozen=True, slots=True)
class ToolMatch:
    tool: ToolDescriptor
    score: int
    matched_terms: tuple[str, ...]


class ToolRegistry:
    def __init__(self, context: RunContext) -> None:
        self._tools = {tool.toolCode: tool for tool in context.availableTools}

    def list_tools(self) -> list[ToolDescriptor]:
        return list(self._tools.values())

    def get(self, tool_code: str) -> ToolDescriptor | None:
        return self._tools.get(tool_code)

    def match_by_intent(self, message: str) -> ToolDescriptor | None:
        matches = self.rank_by_intent(message)
        return matches[0].tool if matches else None

    def rank_by_intent(self, message: str) -> list[ToolMatch]:
        text = message.lower()
        message_terms = _terms(message)
        matches: list[ToolMatch] = []
        for tool in self._tools.values():
            score, matched_terms = _score_tool(text, message_terms, tool)
            if score > 0:
                matches.append(ToolMatch(tool=tool, score=score, matched_terms=tuple(sorted(matched_terms))))
        return sorted(matches, key=lambda item: (-item.score, item.tool.toolCode))


def _score_tool(text: str, message_terms: set[str], tool: ToolDescriptor) -> tuple[int, set[str]]:
    score = 0
    matched_terms: set[str] = set()
    phrases = _phrases(tool)
    for phrase in phrases:
        lowered = phrase.lower()
        if lowered and lowered in text:
            matched_terms.add(phrase)
            score += 6 if len(lowered) >= 3 else 3
    tool_terms = _terms(" ".join(phrases))
    for term in tool_terms:
        if term in message_terms:
            matched_terms.add(term)
            score += 2 if len(term) >= 3 else 1
    if tool.toolCode.lower() in text:
        matched_terms.add(tool.toolCode)
        score += 8
    modality_score, modality_terms = _score_modality_intent(text, tool)
    if modality_score:
        score += modality_score
        matched_terms.update(modality_terms)
    return score, matched_terms


def _phrases(tool: ToolDescriptor) -> list[str]:
    phrases: list[str] = [
        tool.toolCode,
        tool.toolName,
        tool.description or "",
    ]
    phrases.extend(TOOL_KEYWORDS.get(tool.toolCode, ()))
    for value in tool.hints.values():
        if isinstance(value, str):
            phrases.append(value)
        elif isinstance(value, (list, tuple, set)):
            phrases.extend(str(item) for item in value)
    return [phrase.strip() for phrase in phrases if isinstance(phrase, str) and phrase.strip()]


def _score_modality_intent(text: str, tool: ToolDescriptor) -> tuple[int, set[str]]:
    tool_text = " ".join(_phrases(tool)).lower()
    hint_values = " ".join(str(value) for value in tool.hints.values()).lower()
    combined = f"{tool_text} {hint_values}"
    requested_modality = requested_output_modality(text)
    if requested_modality == "image" and tool_supports_modality(tool, "image", combined):
        bonus = 2 if _has_any(combined, ("图片生成", "生图", "文生图", "image_generation")) else 0
        return 7 + bonus, {"image_intent"}
    if requested_modality == "video" and tool_supports_modality(tool, "video", combined):
        bonus = 2 if _has_any(combined, ("视频生成", "文生视频", "图生视频", "video_generation")) else 0
        return 7 + bonus, {"video_intent"}
    if requested_modality == "audio" and tool_supports_modality(tool, "audio", combined):
        bonus = 2 if _has_any(combined, ("语音合成", "配音", "text_to_speech")) else 0
        return 7 + bonus, {"audio_intent"}
    return 0, set()


def requested_output_modality(message: str) -> str | None:
    text = message.lower()
    # Video wins over image when both "画面/图片" and "视频/成片" appear.
    if _has_any(text, ("视频", "短视频", "成片", "宣传片", "转场视频", "生成一段", "生成一个视频", "图生视频", "文生视频")):
        return "video"
    if _has_any(text, (
        "图片",
        "图像",
        "照片",
        "写真",
        "写真照",
        "老照片",
        "产品照",
        "商品主图",
        "海报",
        "插画",
        "画面",
        "生成一张",
        "来一张",
        "拍摄",
        "摄影",
        "远景",
        "近景",
        "全身",
        "半身",
        "cos",
        "cosplay",
        "角色照",
        "剧照",
    )):
        return "image"
    if _has_any(text, ("语音", "配音", "朗读", "音频", "声音")):
        return "audio"
    return None


def tool_supports_modality(tool: ToolDescriptor, modality: str, combined_text: str | None = None) -> bool:
    combined = combined_text if combined_text is not None else " ".join(_phrases(tool)).lower()
    if modality == "image":
        return _has_any(combined, (
            "image_generation",
            "image",
            "photo",
            "picture",
            "图片",
            "图像",
            "生图",
            "文生图",
            "写真",
            "海报",
            "插画",
            "照片",
            "产品照",
            "商品主图",
            "老照片",
            "摄影",
            "拍摄",
        ))
    if modality == "video":
        return _has_any(combined, (
            "video_generation",
            "video",
            "视频",
            "短视频",
            "成片",
            "宣传片",
            "生视频",
            "文生视频",
            "图生视频",
        ))
    if modality == "audio":
        return _has_any(combined, ("text_to_speech", "speech", "tts", "语音", "配音", "朗读", "音频"))
    return False


def infer_output_modality(tool: ToolDescriptor) -> str | None:
    hint_values = " ".join(str(value) for value in tool.hints.values()).lower()
    combined = f"{' '.join(_phrases(tool)).lower()} {hint_values}"
    if _has_any(combined, ("video_generation", "文生视频", "图生视频", "视频生成", "生视频", "short video")):
        return "video"
    if _has_any(combined, ("image_generation", "图片生成", "文生图", "生图", "写真", "海报", "照片")):
        return "image"
    if _has_any(combined, ("text_to_speech", "audio_generation", "语音合成", "配音", "音频")):
        return "audio"
    if _has_any(combined, ("text_generation", "copywriting", "文案", "标题", "文章", "总结")):
        return "text"
    return None


def _has_any(value: str, needles: tuple[str, ...]) -> bool:
    return any(needle in value for needle in needles)


def _terms(value: str) -> set[str]:
    terms: set[str] = set()
    for term in re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]{2,}", value.lower()):
        if not term:
            continue
        terms.add(term)
        if re.fullmatch(r"[\u4e00-\u9fff]{2,}", term):
            max_size = min(4, len(term))
            for size in range(2, max_size + 1):
                for index in range(0, len(term) - size + 1):
                    terms.add(term[index : index + size])
    return terms
