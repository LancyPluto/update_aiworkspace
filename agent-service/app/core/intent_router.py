import re
from enum import StrEnum

from pydantic import BaseModel, Field

from app.core.schemas import RunContext
from app.tools.registry import ToolRegistry


class Intent(StrEnum):
    GENERAL_CHAT = "general_chat"
    TOOL_USE = "tool_use"
    NEEDS_CLARIFICATION = "needs_clarification"
    UNSUPPORTED = "unsupported"
    RAG = "rag"
    FILE_ANALYSIS = "file_analysis"
    WORKFLOW = "workflow"
    SECURITY_REJECTED = "security_rejected"


class IntentResult(BaseModel):
    intent: Intent
    confidence: float
    selectedToolCode: str | None = None
    candidateToolCodes: list[str] = Field(default_factory=list)
    clarifyingQuestion: str | None = None
    decisionSource: str = "rules"
    reason: str


class IntentRouter:
    unsupported_keywords = ("上传", "文件", "知识库", "RAG", "向量", "多智能体", "工作流")
    vague_messages = {"帮我做一下", "帮我弄一下", "处理一下", "做一下"}
    short_chat_patterns = {"你是谁", "你能做什么", "你能帮我做什么", "你有什么工具", "有什么工具", "你好", "hi", "hello", "在吗"}
    tool_action_keywords = ("写", "生成", "优化", "改写", "润色", "做", "帮我", "输出", "文案", "标题", "笔记", "朋友圈", "公众号")

    def classify(self, context: RunContext) -> IntentResult:
        message = context.message.strip()
        message_lower = message.lower()
        if context.agentFiles:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.9, reason="ready_file_context_available")
        if "文件" in message or "上传" in message:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.75, reason="file_analysis_request")
        if any(keyword.lower() in message_lower for keyword in self.unsupported_keywords):
            return IntentResult(intent=Intent.UNSUPPORTED, confidence=0.9, reason="phase_unsupported_capability")
        if not message:
            return IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.8, reason="empty_request")

        # Substring match for short/greeting chats (broader than exact match)
        if self._is_short_chat(message_lower):
            return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.95, reason="short_general_chat")

        continued = self._tool_use_from_pending_tool_context(context)
        if continued is not None:
            return continued

        continued = self._tool_use_from_pending_tool_prompt(context)
        if continued is not None:
            return continued

        registry = ToolRegistry(context)
        candidates = registry.rank_by_intent(message)
        if candidates:
            top = candidates[0]
            second_score = candidates[1].score if len(candidates) > 1 else 0
            candidate_codes = [candidate.tool.toolCode for candidate in candidates[:3]]
            if top.score >= 10 and (len(candidates) == 1 or top.score - second_score >= 3):
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.9,
                    selectedToolCode=top.tool.toolCode,
                    candidateToolCodes=candidate_codes,
                    reason="high_confidence_tool_match",
                )
            if len(candidates) > 1 and top.score >= 4 and top.score - second_score <= 2 and self._looks_like_tool_request(message):
                names = []
                for c in candidates[:3]:
                    names.append(c.tool.toolName or c.tool.toolCode)
                clarifying = (
                    f"我识别到多个可能适合的工具：{'、'.join(names)}。\n"
                    f"请告诉我你想用哪个工具，以及具体需求是什么？"
                )
                return IntentResult(
                    intent=Intent.NEEDS_CLARIFICATION,
                    confidence=0.7,
                    candidateToolCodes=candidate_codes,
                    clarifyingQuestion=clarifying,
                    reason="ambiguous_tool_candidates",
                )
            if top.score >= 4 and self._looks_like_tool_request(message):
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.75,
                    selectedToolCode=top.tool.toolCode,
                    candidateToolCodes=candidate_codes,
                    reason="scored_tool_match",
                )
            if self._looks_like_tool_request(message):
                # 给出候选工具的提示，避免第一次提示太过笼统
                top_tool = candidates[0].tool
                top_name = top_tool.toolName or top_tool.toolCode
                required_params = top_tool.inputSchema.get("required", [])
                props = top_tool.inputSchema.get("properties", {})
                if required_params and isinstance(required_params, list) and isinstance(props, dict):
                    param_hints = []
                    for p in required_params[:5]:
                        prop = props.get(p, {})
                        title = prop.get("title", p) if isinstance(prop, dict) else p
                        param_hints.append(f"「{title}」")
                    clarifying = (
                        f"看起来你想使用「{top_name}」工具。\n"
                        f"这个工具需要补充以下信息：{'、'.join(param_hints)}。\n"
                        f"请告诉我具体内容，我会帮你完成。"
                    )
                else:
                    clarifying = (
                        f"看起来你想使用「{top_name}」工具，请告诉我具体需求（包括产品/服务、目标场景等），我来帮你完成。"
                    )
                return IntentResult(
                    intent=Intent.NEEDS_CLARIFICATION,
                    confidence=0.65,
                    candidateToolCodes=candidate_codes,
                    clarifyingQuestion=clarifying,
                    reason="weak_tool_signal",
                )

        # Short vague messages should suggest tools rather than asking for clarification
        if len(message) <= 5 or message in self.vague_messages:
            return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="short_vague_message")
        return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat")

    def _is_short_chat(self, message_lower: str) -> bool:
        """Check if the message matches known short chat/greeting patterns."""
        if message_lower in self.short_chat_patterns:
            return True
        # Broader substring matching for common greetings
        greetings = ("你好", "你是谁", "你能做什么", "你能帮我", "有什么工具", "你会什么")
        for greeting in greetings:
            if greeting in message_lower:
                return True
        return False

    def _looks_like_tool_request(self, message: str) -> bool:
        lowered = message.lower()
        return any(keyword in lowered for keyword in self.tool_action_keywords)

    def _tool_use_from_pending_tool_context(self, context: RunContext) -> IntentResult | None:
        """检查是否存在持久的待补参上下文（agent_pending_tool_context），用于多轮补参恢复。"""
        pending = context.pendingToolContext
        if pending is None or pending.status != "ACTIVE":
            return None
        if not pending.selectedToolCode:
            return None
        return IntentResult(
            intent=Intent.TOOL_USE,
            confidence=0.9,
            selectedToolCode=pending.selectedToolCode,
            candidateToolCodes=[pending.selectedToolCode],
            reason="restored_from_pending_tool_context",
            clarifyingQuestion=pending.clarifyingQuestion,
        )

    def _tool_use_from_pending_tool_prompt(self, context: RunContext) -> IntentResult | None:
        """上一轮助手刚发过「如果想使用「xxx」…」补参说明时，本条用户话往往不含「小红书」等关键词，不能单靠当前句做工具匹配。"""
        message = context.message.strip()
        if len(message) < 4:
            return None
        assistant_text = self._latest_tool_guidance_assistant_text(context)
        if not assistant_text:
            return None
        if not self._looks_like_tool_slot_followup(message):
            return None
        wanted = self._parse_tool_display_name_from_guidance(assistant_text)
        if not wanted:
            return None
        registry = ToolRegistry(context)
        for tool in registry.list_tools():
            name = (tool.toolName or "").strip()
            if not name:
                continue
            if wanted == name or wanted in name or name in wanted:
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.88,
                    selectedToolCode=tool.toolCode,
                    candidateToolCodes=[tool.toolCode],
                    reason="continuing_pending_tool_prompt",
                )
        return None

    @staticmethod
    def _latest_tool_guidance_assistant_text(context: RunContext) -> str:
        for item in reversed(context.history[-8:]):
            role = (item.role or "").strip().lower()
            if role not in {"assistant", "ai"}:
                continue
            body = (item.content or "").strip()
            if "如果想使用「" in body:
                return body
        return ""

    @staticmethod
    def _parse_tool_display_name_from_guidance(assistant_text: str) -> str:
        match = re.search(r"如果想使用「([^」]+)」", assistant_text)
        if not match:
            return ""
        return match.group(1).strip()

    @staticmethod
    def _looks_like_tool_slot_followup(message: str) -> bool:
        if re.search(r"[A-Za-z0-9_\u4e00-\u9fff]+\s*[:：]", message):
            return True
        if "产品/服务名称" in message or "目标用户" in message or "文案主题" in message or "目标人群" in message or "核心卖点" in message:
            return True
        needles = (
            "按照你给的例子",
            "按照你的例子",
            "按你的例子",
            "按你给的例子",
            "用你给的例子",
            "用你的例子",
            "照你给的例子",
            "照你的例子",
            "使用默认",
            "就用默认",
            "就用示例",
            "全部按照",
        )
        if any(n in message for n in needles) and ("例子" in message or "默认" in message or "示例" in message):
            return True
        if "全部按照" in message and ("你给" in message or "你的" in message):
            return True
        return False
