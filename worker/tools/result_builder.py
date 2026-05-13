import re
from typing import Any

from tools.customer_followup_script_generator.parser import format_publishable_text as fmt_customer_followup
from tools.ecommerce_campaign_planner.parser import format_publishable_text as fmt_ecommerce_campaign
from tools.live_stream_script_generator.parser import format_publishable_text as fmt_live_stream
from tools.moments_copywriting_generator.parser import parse_result_markdown as parse_moments
from tools.moments_copywriting_generator.parser import format_publishable_text as fmt_moments
from tools.objection_handling_script_generator.parser import format_publishable_text as fmt_objection_handling
from tools.product_detail_page_copywriter.parser import format_publishable_text as fmt_product_detail
from tools.product_title_optimizer.parser import format_publishable_text as fmt_product_title
from tools.short_video_script_generator.parser import parse_result_markdown as parse_short_video_script
from tools.short_video_script_generator.parser import format_publishable_text as fmt_short_video_script
from tools.short_video_topic_generator.parser import format_publishable_text as fmt_short_video_topic
from tools.store_campaign_planner.parser import format_publishable_text as fmt_store_campaign
from tools.wechat_longform_generator.parser import format_publishable_text as fmt_wechat_longform
from tools.xiaohongshu_copywriting.parser import format_publishable_text as fmt_xiaohongshu
from tools.errors import ToolResultBuildError

_TOOL_PUBLISHABLE_FORMATTERS: dict[str, Any] = {
    "xiaohongshu_copywriting": fmt_xiaohongshu,
    "wechat_longform_generator": fmt_wechat_longform,
    "moments_copywriting_generator": fmt_moments,
    "product_title_optimizer": fmt_product_title,
    "product_detail_page_copywriter": fmt_product_detail,
    "short_video_script_generator": fmt_short_video_script,
    "short_video_topic_generator": fmt_short_video_topic,
    "live_stream_script_generator": fmt_live_stream,
    "customer_followup_script_generator": fmt_customer_followup,
    "objection_handling_script_generator": fmt_objection_handling,
    "store_campaign_planner": fmt_store_campaign,
    "ecommerce_campaign_planner": fmt_ecommerce_campaign,
}


_FENCE_OPEN = re.compile(r"^\s*```(?:\w+)?\s*\n", re.MULTILINE)
_NON_WORD_PATTERN = re.compile(r"[\s\W_]+", re.UNICODE)
_TERM_SPLIT_PATTERN = re.compile(r"[，,、/\\\s\-（()）【】\[\]：:；;·|]+")


def _strip_outer_code_fence(text: str) -> str:
    """模型偶发用 ```markdown 包裹全文时去掉围栏，便于解析与展示。"""
    raw = (text or "").strip()
    if not raw.startswith("```"):
        return text or ""
    without_open = _FENCE_OPEN.sub("", raw, count=1)
    if without_open.rstrip().endswith("```"):
        return without_open.rstrip()[:-3].rstrip()
    return without_open.strip()


def _normalize_text(text: str) -> str:
    return _NON_WORD_PATTERN.sub("", str(text or "")).lower()


def _extract_anchor_terms(raw: str) -> list[str]:
    source = str(raw or "").strip()
    if not source:
        return []

    candidates: list[str] = []
    normalized_full = _normalize_text(source)
    if len(normalized_full) >= 2:
        candidates.append(normalized_full)

    for piece in _TERM_SPLIT_PATTERN.split(source):
        normalized = _normalize_text(piece)
        if len(normalized) >= 2:
            candidates.append(normalized)

    deduped: list[str] = []
    for item in candidates:
        if item not in deduped:
            deduped.append(item)
    return deduped


def _contains_any_anchor(text: str, anchors: list[str]) -> bool:
    haystack = _normalize_text(text)
    return bool(haystack and anchors and any(anchor in haystack for anchor in anchors))


def _validate_moments_relevance(context: dict[str, Any], markdown_text: str) -> None:
    params = context.get("params") or {}
    topic_anchors = _extract_anchor_terms(str(params.get("topic") or ""))
    if not topic_anchors:
        return

    parsed = parse_moments(markdown_text)
    combined_text = "\n".join(
        [
            str(parsed.get("content") or ""),
            " ".join(str(tag).strip() for tag in (parsed.get("hashtags") or []) if str(tag).strip()),
            str(parsed.get("cta") or ""),
        ]
    )
    if not _contains_any_anchor(combined_text, topic_anchors):
        raise ToolResultBuildError(
            "moments output does not mention the requested topic/product; reject generic copy"
        )


def _validate_short_video_relevance(context: dict[str, Any], markdown_text: str) -> None:
    params = context.get("params") or {}
    promotion_anchors = _extract_anchor_terms(str(params.get("promotionObject") or ""))
    if not promotion_anchors:
        return

    parsed = parse_short_video_script(markdown_text)
    combined_text = "\n".join(
        [
            str(parsed.get("hook") or ""),
            str(parsed.get("voiceover") or ""),
            str(parsed.get("storyboard") or ""),
            str(parsed.get("titles_and_tags") or ""),
        ]
    )
    if not _contains_any_anchor(combined_text, promotion_anchors):
        raise ToolResultBuildError(
            "short video script output does not mention the requested promotion object; reject off-topic copy"
        )


def _validate_output_relevance(context: dict[str, Any], markdown_text: str) -> None:
    tool_code = str(context.get("toolCode") or "")
    if tool_code == "moments_copywriting_generator":
        _validate_moments_relevance(context, markdown_text)
    elif tool_code == "short_video_script_generator":
        _validate_short_video_relevance(context, markdown_text)


def build_success_payload(context: dict[str, Any], generated_text: str) -> dict[str, Any]:
    generated_text = _strip_outer_code_fence(generated_text)
    _validate_output_relevance(context, generated_text)
    tool_code = context.get("toolCode")
    formatter = _TOOL_PUBLISHABLE_FORMATTERS.get(str(tool_code or ""))
    if formatter is not None:
        generated_text = formatter(generated_text)

    return {
        "resourceType": context.get("outputFormat", "MARKDOWN"),
        "contentText": generated_text,
    }
