from typing import Any

from tools.xiaohongshu_copywriting.parser import parse_result_markdown

def build_success_payload(context: dict[str, Any], generated_text: str) -> dict[str, Any]:
    content_json = None
    tool_code = context.get("toolCode")

    if tool_code == "xiaohongshu_copywriting":
        content_json = parse_result_markdown(generated_text)

    return {
        "resourceType": context.get("outputFormat", "MARKDOWN"),
        "contentText": generated_text,
        "contentJson": content_json,
        "modelProviderCode": context.get("modelProviderCode"),
        "modelName": context.get("modelName"),
    }
