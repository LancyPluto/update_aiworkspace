"""Format user-facing hints when required tool arguments are missing."""

from typing import Any

from app.core.schemas import ToolDescriptor

# 当 inputSchema 未带 title/description 时，按常见字段 key 给出中文名与示例（单测与兜底）
_FALLBACK_LINES: dict[str, str] = {
    "topic": "• 主题：要写什么内容，例如「夏季新品防晒喷雾上市」",
    "userRequest": "• 你的原始需求：用一两句话说明想达成什么",
    "productName": "• 产品/服务名称：例如「五一肩颈护理体验套餐」",
    "targetCustomer": "• 目标用户：例如「久坐上班族、宝妈」",
    "targetAudience": "• 目标人群：例如「附近白领、老会员、新客」",
    "tone": "• 文案风格：例如「亲切种草」「真实分享」「专业克制」",
    "scene": "• 发布场景：例如「新品上线」「活动宣传」「门店日常」",
    "sellingPoints": "• 核心卖点：例如「限时体验价、到店即用、适合敏感肌」",
    "lengthLevel": "• 文案长度：例如「短」（精炼一两句）或「中」（稍展开）",
    "style": "• 风格：例如「种草」「真实分享」",
    "extraInfo": "• 补充说明：例如活动时间、禁忌词（选填）",
    "cta": "• 行动引导：例如「私信预约体验名额」（选填）",
}


def _line_from_schema_property(field_key: str, prop: dict[str, Any]) -> str:
    title = prop.get("title")
    description = prop.get("description")
    enum_vals = prop.get("enum")

    label = title if isinstance(title, str) and title.strip() else field_key
    if isinstance(description, str) and description.strip():
        return f"• {label}：{description.strip()}"

    if isinstance(enum_vals, list) and enum_vals:
        opts = "、".join(str(v) for v in enum_vals[:10])
        return f"• {label}：可从「{opts}」中选择其一，或按你的场景用一句话说明。"

    return f"• {label}：请简要补充（与「{field_key}」对应的信息）。"


def format_missing_tool_arguments_message(tool: ToolDescriptor | None, missing: list[str]) -> str:
    """Build a Chinese clarification message with per-field examples."""
    tool_name = (tool.toolName or tool.toolCode or "该工具").strip() if tool else "该工具"
    props: dict[str, Any] = {}
    if tool and isinstance(tool.inputSchema, dict):
        raw = tool.inputSchema.get("properties")
        if isinstance(raw, dict):
            props = raw

    lines: list[str] = []
    for key in missing:
        if not isinstance(key, str) or not key:
            continue
        prop = props.get(key)
        if isinstance(prop, dict) and (prop.get("title") or prop.get("description") or prop.get("enum")):
            lines.append(_line_from_schema_property(key, prop))
        elif key in _FALLBACK_LINES:
            lines.append(_FALLBACK_LINES[key])
        else:
            lines.append(f"• {key}：请用中文简要说明该段信息。")

    body = "\n".join(lines) if lines else "请用中文补充关键信息。"
    return (
        f"如果想使用「{tool_name}」，请在同一条或下一条消息里按下面补充（可直接复制条目改写成你的内容）：\n\n"
        f"{body}\n\n"
        "提示：每条前面是说明、后面是写法参考；你用自己的话写清楚也可以，不必照搬示例措辞。"
    )
