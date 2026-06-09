import re
from dataclasses import dataclass, field
from typing import Any

from app.core.schemas import RecentToolCallContext, RunContext, ToolDescriptor
from app.core.user_attachment_priority import user_selected_image_urls
from app.tools.registry import requested_output_modality, tool_supports_modality


@dataclass
class FollowupResolution:
    accepted: bool
    reason: str
    tool_code: str | None = None
    inherited_from_tool_call_id: int | None = None
    inherited_arguments: dict[str, Any] = field(default_factory=dict)
    patched_arguments: dict[str, Any] = field(default_factory=dict)
    media_urls: list[str] = field(default_factory=list)


FOLLOWUP_PATTERNS = (
    "也来",
    "再来",
    "再生成",
    "再做",
    "来一张",
    "来一个",
    "按刚才",
    "按上次",
    "沿用",
    "同样",
    "一样",
    "换成",
    "改成",
    "替换成",
    "这张",
    "上一张",
    "刚才那张",
)

VIDEO_PATTERNS = ("做成视频", "生成视频", "变成视频", "图生视频", "转视频")


class FollowupTaskResolver:
    """Resolves short follow-up requests against recent successful tool calls."""

    def resolve(self, context: RunContext, selected_tool: ToolDescriptor | None) -> FollowupResolution:
        message = (context.message or "").strip()
        if not message or not self._looks_like_followup(message):
            return FollowupResolution(False, "not_followup")
        if not context.recentToolCalls:
            return FollowupResolution(False, "no_recent_tool_calls")

        requested_modality = requested_output_modality(message)
        wants_video = any(pattern in message for pattern in VIDEO_PATTERNS)
        target_tool_code = selected_tool.toolCode if selected_tool is not None else None
        source = self._select_source(context.recentToolCalls, target_tool_code, requested_modality, wants_video)
        if source is None:
            return FollowupResolution(False, "no_compatible_recent_tool_call", tool_code=target_tool_code)

        inherited = dict(source.argumentsJson or {})
        has_reference_media = bool(source.mediaUrls) or bool(user_selected_image_urls(context))
        if not inherited and not (has_reference_media and self._tool_accepts_image(selected_tool)):
            return FollowupResolution(False, "recent_tool_call_missing_arguments", tool_code=target_tool_code or source.toolCode)

        can_use_selected_target = (
            target_tool_code is not None
            and (
                source.toolCode == target_tool_code
                or (wants_video and bool(source.mediaUrls) and self._tool_accepts_image(selected_tool))
            )
        )
        tool_code = target_tool_code if can_use_selected_target else source.toolCode
        patch_tool = selected_tool if can_use_selected_target else None
        patched = self._patch_arguments(context, message, inherited, patch_tool, source)
        return FollowupResolution(
            True,
            "followup_arguments_inherited",
            tool_code=tool_code,
            inherited_from_tool_call_id=source.id,
            inherited_arguments=inherited,
            patched_arguments=patched,
            media_urls=source.mediaUrls or [],
        )

    def _select_source(
        self,
        calls: list[RecentToolCallContext],
        target_tool_code: str | None,
        requested_modality: str | None,
        wants_video: bool,
    ) -> RecentToolCallContext | None:
        if target_tool_code:
            for call in calls:
                if call.toolCode == target_tool_code:
                    return call
        if wants_video:
            for call in calls:
                if call.mediaUrls:
                    return call
        if requested_modality:
            for call in calls:
                if (call.resourceType or "").upper() == requested_modality.upper():
                    return call
        return calls[0] if calls else None

    @staticmethod
    def _looks_like_followup(message: str) -> bool:
        compact = re.sub(r"\s+", "", message)
        if any(pattern in compact for pattern in FOLLOWUP_PATTERNS):
            return True
        if len(compact) <= 16 and any(word in compact for word in ("科比", "乔丹", "詹姆斯", "赛博朋克", "黄袍", "黑袍")):
            return True
        return False

    def _patch_arguments(
        self,
        context: RunContext,
        message: str,
        inherited: dict[str, Any],
        selected_tool: ToolDescriptor | None,
        source: RecentToolCallContext,
    ) -> dict[str, Any]:
        patched = dict(inherited)
        prompt_key = self._prompt_key(patched, selected_tool)
        current_prompt = str(patched.get(prompt_key) or patched.get("userRequest") or "")
        changed_subject = self._extract_replacement_subject(message)
        if changed_subject:
            patched[prompt_key] = self._build_rewrite_prompt(current_prompt, message)
        elif any(pattern in message for pattern in ("按刚才", "按上次", "沿用", "同样", "一样", "再来", "也来")):
            patched[prompt_key] = current_prompt or message
        else:
            patched[prompt_key] = message
        patched["userRequest"] = message

        user_image_urls = user_selected_image_urls(context)
        reference_url = user_image_urls[0] if user_image_urls else (source.mediaUrls[0] if source.mediaUrls else None)
        if reference_url and self._tool_accepts_image(selected_tool):
            for key in ("image", "imageUrl", "image_url", "referenceImageUrl", "reference_image_url", "initImage", "inputImage"):
                if key in self._schema_properties(selected_tool):
                    patched[key] = reference_url
                    break
        return patched

    @staticmethod
    def _prompt_key(arguments: dict[str, Any], selected_tool: ToolDescriptor | None) -> str:
        properties = FollowupTaskResolver._schema_properties(selected_tool)
        for key in ("prompt", "textPrompt", "imagePrompt", "description", "content", "topic", "userRequest"):
            if key in arguments or key in properties:
                return key
        return "prompt"

    @staticmethod
    def _build_rewrite_prompt(previous_prompt: str, user_request: str) -> str:
        request = user_request.strip()
        if not previous_prompt:
            return request
        return (
            "本轮用户改写要求优先："
            f"{request}\n\n"
            "参考上一轮的画面风格、构图、比例和质量设定，但不要保留与本轮要求冲突的主体、服装、场景、IP 或文案元素。\n"
            "上一轮提示词仅作风格参考：\n"
            f"{previous_prompt}"
        )

    @staticmethod
    def _extract_replacement_subject(message: str) -> str | None:
        patterns = (
            r"(?:给|帮|替|换成|改成|替换成)(?P<subject>[^，。,.！？!?]{1,40})(?:也)?(?:来|生成|做|画|写)",
            r"(?:换成|改成|替换成)(?P<subject>[^，。,.！？!?]{1,40})",
        )
        for pattern in patterns:
            match = re.search(pattern, message)
            if match:
                subject = match.group("subject").strip()
                subject = re.sub(r"^(给|帮|替)", "", subject).strip()
                if subject:
                    return subject
        compact = re.sub(r"\s+", "", message)
        if compact.startswith("给") and "也来" in compact:
            subject = compact[1:compact.index("也来")]
            return subject or None
        return None

    @staticmethod
    def _schema_properties(selected_tool: ToolDescriptor | None) -> dict[str, Any]:
        if selected_tool is None or not isinstance(selected_tool.inputSchema, dict):
            return {}
        properties = selected_tool.inputSchema.get("properties", {})
        return properties if isinstance(properties, dict) else {}

    def _tool_accepts_image(self, selected_tool: ToolDescriptor | None) -> bool:
        if selected_tool is None:
            return False
        if tool_supports_modality(selected_tool, "VIDEO"):
            return True
        properties = self._schema_properties(selected_tool)
        return any("image" in key.lower() for key in properties)
