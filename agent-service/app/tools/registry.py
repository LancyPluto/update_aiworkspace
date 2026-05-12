from app.core.schemas import RunContext, ToolDescriptor


TOOL_KEYWORDS: dict[str, tuple[str, ...]] = {
    "xiaohongshu_copywriting": ("小红书", "种草", "笔记", "爆款笔记"),
    "moments_copywriting_generator": ("朋友圈", "微信朋友圈", "私域文案"),
    "product_title_optimizer": ("商品标题", "标题优化", "电商标题"),
    "wechat_longform_generator": ("公众号", "微信长文", "长文"),
}


class ToolRegistry:
    def __init__(self, context: RunContext) -> None:
        self._tools = {tool.toolCode: tool for tool in context.availableTools if tool.autoCallable}

    def list_tools(self) -> list[ToolDescriptor]:
        return list(self._tools.values())

    def get(self, tool_code: str) -> ToolDescriptor | None:
        return self._tools.get(tool_code)

    def match_by_intent(self, message: str) -> ToolDescriptor | None:
        text = message.lower()
        for tool in self._tools.values():
            haystack = " ".join(
                [
                    tool.toolCode,
                    tool.toolName,
                    tool.description or "",
                    " ".join(str(value) for value in tool.hints.values()),
                ]
            ).lower()
            keywords = TOOL_KEYWORDS.get(tool.toolCode, ())
            if any(keyword.lower() in text for keyword in keywords):
                return tool
            if any(part and part in text for part in haystack.split()):
                return tool
        return None
