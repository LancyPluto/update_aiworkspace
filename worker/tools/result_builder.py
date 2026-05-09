from typing import Any

from tools.moments_copywriting_generator.parser import parse_result_markdown as parse_moments_markdown
from tools.product_title_optimizer.parser import parse_result_markdown as parse_product_title_markdown
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

    return {
        "resourceType": context.get("outputFormat", "MARKDOWN"),
        "contentText": generated_text,
    }
