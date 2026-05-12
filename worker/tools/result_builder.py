from typing import Any

from tools.customer_followup_script_generator.parser import (
    parse_result_markdown as parse_customer_followup_script_markdown,
)
from tools.live_stream_script_generator.parser import (
    parse_result_markdown as parse_live_stream_script_markdown,
)
from tools.moments_copywriting_generator.parser import parse_result_markdown as parse_moments_markdown
from tools.objection_handling_script_generator.parser import (
    parse_result_markdown as parse_objection_handling_script_markdown,
)
from tools.product_detail_page_copywriter.parser import (
    parse_result_markdown as parse_product_detail_page_copywriter_markdown,
)
from tools.product_title_optimizer.parser import parse_result_markdown as parse_product_title_markdown
from tools.short_video_script_generator.parser import (
    parse_result_markdown as parse_short_video_script_markdown,
)
from tools.short_video_topic_generator.parser import (
    parse_result_markdown as parse_short_video_topic_markdown,
)
from tools.store_campaign_planner.parser import (
    parse_result_markdown as parse_store_campaign_markdown,
)
from tools.wechat_longform_generator.parser import parse_result_markdown as parse_wechat_longform_markdown
from tools.xiaohongshu_copywriting.parser import parse_result_markdown

def build_success_payload(context: dict[str, Any], generated_text: str) -> dict[str, Any]:
    tool_code = context.get("toolCode")

    if tool_code == "xiaohongshu_copywriting":
        parse_result_markdown(generated_text)
    elif tool_code == "wechat_longform_generator":
        parse_wechat_longform_markdown(generated_text)
    elif tool_code == "moments_copywriting_generator":
        parse_moments_markdown(generated_text)
    elif tool_code == "product_title_optimizer":
        parse_product_title_markdown(generated_text)
    elif tool_code == "product_detail_page_copywriter":
        parse_product_detail_page_copywriter_markdown(generated_text)
    elif tool_code == "short_video_script_generator":
        parse_short_video_script_markdown(generated_text)
    elif tool_code == "short_video_topic_generator":
        parse_short_video_topic_markdown(generated_text)
    elif tool_code == "live_stream_script_generator":
        parse_live_stream_script_markdown(generated_text)
    elif tool_code == "customer_followup_script_generator":
        parse_customer_followup_script_markdown(generated_text)
    elif tool_code == "objection_handling_script_generator":
        parse_objection_handling_script_markdown(generated_text)
    elif tool_code == "store_campaign_planner":
        parse_store_campaign_markdown(generated_text)

    return {
        "resourceType": context.get("outputFormat", "MARKDOWN"),
        "contentText": generated_text,
    }
