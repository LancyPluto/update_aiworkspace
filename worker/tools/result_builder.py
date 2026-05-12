import re
from typing import Any

from tools.customer_followup_script_generator.parser import format_publishable_text as fmt_customer_followup
from tools.ecommerce_campaign_planner.parser import format_publishable_text as fmt_ecommerce_campaign
from tools.live_stream_script_generator.parser import format_publishable_text as fmt_live_stream
from tools.moments_copywriting_generator.parser import format_publishable_text as fmt_moments
from tools.objection_handling_script_generator.parser import format_publishable_text as fmt_objection_handling
from tools.product_detail_page_copywriter.parser import format_publishable_text as fmt_product_detail
from tools.product_title_optimizer.parser import format_publishable_text as fmt_product_title
from tools.short_video_script_generator.parser import format_publishable_text as fmt_short_video_script
from tools.short_video_topic_generator.parser import format_publishable_text as fmt_short_video_topic
from tools.store_campaign_planner.parser import format_publishable_text as fmt_store_campaign
from tools.wechat_longform_generator.parser import format_publishable_text as fmt_wechat_longform
from tools.xiaohongshu_copywriting.parser import format_publishable_text as fmt_xiaohongshu

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


def _strip_outer_code_fence(text: str) -> str:
    """模型偶发用 ```markdown 包裹全文时去掉围栏，便于解析与展示。"""
    raw = (text or "").strip()
    if not raw.startswith("```"):
        return text or ""
    without_open = _FENCE_OPEN.sub("", raw, count=1)
    if without_open.rstrip().endswith("```"):
        return without_open.rstrip()[:-3].rstrip()
    return without_open.strip()


def build_success_payload(context: dict[str, Any], generated_text: str) -> dict[str, Any]:
    generated_text = _strip_outer_code_fence(generated_text)
    tool_code = context.get("toolCode")
    formatter = _TOOL_PUBLISHABLE_FORMATTERS.get(str(tool_code or ""))
    if formatter is not None:
        generated_text = formatter(generated_text)

    return {
        "resourceType": context.get("outputFormat", "MARKDOWN"),
        "contentText": generated_text,
    }
