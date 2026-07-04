import asyncio
import json
import logging
import re
import time
from typing import Any

from app.clients.backend_client import BackendBusinessError
from app.config import settings
from app.credit_messages import credit_message_from_backend_error
from app.core.attachment_catalog import build_reference_plan, current_attachment_alias, llm_token_for_mention, readable_positional_prompt, resolve_media_argument_pointers
from app.core.attachment_precheck import format_attachment_error, validate_attachment_arguments
from app.core.event_types import ATTACHMENT_RESOLVED, MESSAGE_DELTA, TOOL_CALL_REJECTED, TOOL_TASK_DISPATCHED, TOOL_TASK_PROGRESS
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, TaskCreate, TaskDetailResponse, ToolCallComplete, ToolCallCreate, ToolCallFail, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority, emit_attachment_resolved_payload
from app.runtime.runtime_settings import runtime_bool, runtime_float, runtime_int
from app.runtime.prompt_policy import PromptMode, reference_edit_prompt, resolve_prompt_mode
from app.runtime.session_state import SESSION_STATE_INSTRUCTIONS, format_session_state_context, latest_generated_image_state, sanitize_visual_prompt
from app.tools.stream_preview import extract_stream_preview
from app.tools.registry import infer_output_modality


logger = logging.getLogger(__name__)

REFERENCE_SEMANTICS_MARKER = "参考图角色约束"


class ToolExecutionError(RuntimeError):
    def __init__(self, message: str, error_code: str | None = None) -> None:
        super().__init__(message)
        self.error_code = error_code


class BackendToolBridge:
    TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"}
    ABORTING_RUN_STATUSES = {"FAILED", "CANCELLED", "TIMEOUT"}

    def __init__(
        self,
        backend_client,
        timeout_seconds: int | None = None,
        poll_interval_seconds: float | None = None,
        model_client=None,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.timeout_seconds = timeout_seconds or settings.agent_tool_execution_timeout_seconds
        self.poll_interval_seconds = poll_interval_seconds or settings.agent_tool_poll_interval_seconds

    def build_arguments(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        *,
        apply_placeholder_defaults: bool = True,
        workspace_memory_context: str = "",
        prompt_mode: PromptMode | None = None,
    ) -> dict[str, Any]:
        """从用户消息解析参数。占位默认值仅应在「即将执行工具」时启用；缺参检测必须关闭，否则会静默填满 schema 导致从不追问。"""
        arguments: dict[str, Any] = {"userRequest": context.message}
        properties = tool.inputSchema.get("properties", {})
        if not isinstance(properties, dict):
            return arguments
        for message in _recent_user_messages(context):
            for name, prop in properties.items():
                if not isinstance(name, str) or name == "userRequest":
                    continue
                for alias in _field_aliases(name, prop):
                    value = _extract_labeled_argument(message, alias)
                    if value:
                        arguments[name] = value
                        break
        if apply_placeholder_defaults and tool.toolCode == "xiaohongshu_copywriting":
            arguments = _with_xiaohongshu_defaults(context.message, arguments)
        if apply_placeholder_defaults:
            mode = prompt_mode or resolve_prompt_mode(context, tool)
            arguments = _with_generation_argument_defaults(
                context,
                tool,
                arguments,
                workspace_memory_context=workspace_memory_context,
                prompt_mode=mode,
            )
            arguments = _with_field_strategy_defaults(tool, arguments)
            arguments = enforce_locked_field_defaults(tool, arguments, user_message=context.message)
        arguments = _with_attached_file_defaults(context, tool, arguments)
        return arguments

    def missing_required_arguments(self, context: RunContext, tool: ToolDescriptor) -> list[str]:
        if tool.toolCode == "xiaohongshu_copywriting" and _user_accepts_builtin_examples(context.message):
            return []
        arguments = self.build_arguments(context, tool, apply_placeholder_defaults=False)
        return _missing_user_required_fields(tool, arguments)

    async def enrich_arguments(
        self,
        message: str,
        tool: ToolDescriptor,
        existing_args: dict[str, Any] | None = None,
        *,
        workspace_memory_context: str = "",
        context: RunContext | None = None,
        prompt_mode: PromptMode | None = None,
    ) -> dict[str, Any]:
        mode = prompt_mode or (resolve_prompt_mode(context, tool) if context is not None else PromptMode.DEFAULT)
        if mode == PromptMode.REFERENCE_EDIT_DELTA:
            return existing_args or {}
        if self.model is None:
            return existing_args or {}
        properties = tool.inputSchema.get("properties", {})
        if not isinstance(properties, dict) or not properties:
            return existing_args or {}
        field_descriptions = []
        for key, prop in properties.items():
            if key == "userRequest":
                continue
            title = prop.get("title", key) if isinstance(prop, dict) else key
            desc = prop.get("description", "") if isinstance(prop, dict) else ""
            enum_vals = prop.get("enum") if isinstance(prop, dict) else None
            line = f"- {key} ({title})"
            if desc:
                line += f": {desc}"
            if enum_vals and isinstance(enum_vals, list):
                line += f" [可选值: {'/'.join(str(v) for v in enum_vals)}]"
            field_descriptions.append(line)
        if not field_descriptions:
            return existing_args or {}
        existing_info = ""
        if existing_args:
            existing_info = f"\n已从格式匹配中提取的参数 (不要覆盖): {json.dumps(existing_args, ensure_ascii=False)}"
        memory_info = ""
        if workspace_memory_context.strip() and mode != PromptMode.REFERENCE_EDIT_DELTA:
            memory_info = (
                "\nWorkspace long-term memory for this run. Use only relevant stable preferences to fill safe, low-risk "
                "fields. Current user instruction has highest priority:\n"
                f"{_limit_text(workspace_memory_context.strip(), 1800)}\n"
            )
        reference_info = ""
        if context is not None and mode != PromptMode.REFERENCE_EDIT_DELTA:
            from app.core.attachment_catalog import build_reference_plan, reference_mentions_payload

            plan = build_reference_plan(context)
            if plan.mentions:
                reference_info = (
                    "\nStructured current-turn references (use alias/source refs for image/reference fields; "
                    "do not copy local file paths or raw @图片 labels):\n"
                    f"{json.dumps(reference_mentions_payload(plan), ensure_ascii=False)}\n"
                    "When filling prompt-like fields, you MUST preserve each reference's role from the user request "
                    "(subject identity vs style vs composition). For dual-reference tasks, do not collapse into a "
                    "single-image description. If the schema has references[], fill one object per referenced image "
                    "with source_ref set to the turn-local alias (for example [当前参考图_1]), and role set to "
                    "face_ref/style_ref/pose_ref/composition_ref as appropriate. Do not put multi-image routing into "
                    "the full prompt.\n"
                )
        session_state_info = ""
        if context is not None:
            session_state_context = format_session_state_context(context)
            if session_state_context:
                session_state_info = (
                    "\nSession state available for coreference resolution:\n"
                    f"{session_state_context}\n"
                    f"{SESSION_STATE_INSTRUCTIONS}\n"
                )
        expand_instruction = "For prompt-like fields, expand short user intent into a useful production prompt."
        prompt = (
            "You are filling arguments for an AI tool call. Return a pure JSON object only.\n"
            "Fill safe, low-risk generation fields from the user's request, recent context, existing arguments, "
            f"workspace long-term memory, and the tool schema. {expand_instruction} "
            "When multiple reference images apply, the prompt or references[] MUST state which reference supplies subject identity and which supplies style or other roles. "
            "For edit or variation operations with fields base_prompt and modification_prompt, copy the original prompt from SessionState verbatim into base_prompt and put only the new user delta into modification_prompt. "
            "Do not invent credentials, account ids, payment, publishing authorization, personal private data, "
            "or other high-risk values. Preserve existing arguments unless a field is empty. "
            "Do not populate legacy image/reference URL fields when structured v2 references[] are available.\n\n"
            f"Tool: {tool.toolName or tool.toolCode}\n"
            f"Description: {tool.description or 'none'}\n"
            f"Fields:\n{chr(10).join(field_descriptions)}\n"
            f"{existing_info}\n"
            f"{memory_info}"
            f"{reference_info}"
            f"{session_state_info}"
            f"Recent user context:\n{message}\n\n"
            "JSON only. Use the exact English field keys from the schema."
        )
        try:
            raw = await self.model.chat([ChatMessage(role="user", content=prompt)])
        except Exception:
            return existing_args or {}
        parsed = self._parse_json_block(raw)
        if not isinstance(parsed, dict):
            return existing_args or {}
        merged = dict(existing_args or {})
        for key, value in parsed.items():
            if not isinstance(key, str) or key in merged or _empty_value(value):
                continue
            merged[key] = value.strip() if isinstance(value, str) else value
        return merged

    def conversation_argument_text(self, context: RunContext) -> str:
        return "\n".join(_recent_user_messages(context))

    @staticmethod
    def _parse_json_block(raw: str) -> Any:
        text = raw.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            lines = lines[1:] if lines else []
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            text = "\n".join(lines).strip()
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", text, re.DOTALL)
            if match:
                try:
                    return json.loads(match.group(0))
                except json.JSONDecodeError:
                    return None
            return None

    async def execute_with_args(self, context: RunContext, tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
        if tool.toolCode == "xiaohongshu_copywriting":
            arguments = _with_xiaohongshu_defaults(context.message, arguments)
        arguments = enforce_locked_field_defaults(tool, arguments, user_message=context.message)
        arguments = resolve_media_argument_pointers(context, arguments)
        attachment_errors = validate_attachment_arguments(context, arguments)
        if attachment_errors:
            message = format_attachment_error(attachment_errors)
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_CALL_REJECTED,
                    eventText=message,
                    eventJson={
                        "kind": "attachment_precheck",
                        "name": tool.toolCode,
                        "reason": "attachment_not_found",
                        "attachments": attachment_errors,
                    },
                ),
            )
            raise ToolExecutionError(message, error_code="PARAM_ERROR")
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ATTACHMENT_RESOLVED,
                eventText="attachment references resolved",
                eventJson=emit_attachment_resolved_payload(context, tool, arguments),
            ),
        )
        call = await self.backend.create_tool_call(context.runId, ToolCallCreate(toolCode=tool.toolCode, argumentsJson=arguments))
        task_params = compile_v2_lite_image_task_params(arguments, context=context) if _is_v2_lite_image_schema(tool) else arguments
        task_id: int | None = None
        try:
            task = await self.backend.create_task(
                TaskCreate(
                    userId=context.userId,
                    toolCode=tool.toolCode,
                    params=task_params,
                    clientRequestId=f"agent-run-{context.runId}-tool-call-{call.id}",
                    excludeFrozen=context.creditBudget,
                )
            )
            task_id = task.taskId
            bind_error: str | None = None
            try:
                await self.backend.bind_tool_call_task(call.id, task.taskId)
            except Exception as exc:
                bind_error = f"{type(exc).__name__}: {exc}"
                logger.warning(
                    "failed to bind agent tool call to task; continuing tool execution runId=%s toolCallId=%s taskId=%s",
                    context.runId,
                    call.id,
                    task.taskId,
                    exc_info=True,
                )
            event_json: dict[str, Any] = {
                "toolCode": tool.toolCode,
                "toolCallId": call.id,
                "taskId": task.taskId,
                "status": task.status,
            }
            if bind_error is not None:
                event_json["bindStatus"] = "FAILED"
                event_json["bindError"] = bind_error
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_TASK_DISPATCHED,
                    eventText=f"Tool {tool.toolCode} task dispatched",
                    eventJson=event_json,
                ),
            )
            task_detail = await self._wait_for_task(context, tool.toolCode, task.taskId)
        except ToolExecutionError as exc:
            if "task" in locals():
                await self._cancel_task(context.userId, task.taskId)
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode="TOOL_TASK_FAILED", errorMessage=str(exc)))
            raise
        except BackendBusinessError as exc:
            if task_id is not None:
                await self._cancel_task(context.userId, task_id)
            if exc.error_code in {"CREDIT_NOT_ENOUGH", "AGENT_CREDIT_NOT_ENOUGH"}:
                message = credit_message_from_backend_error(exc, tool.toolCode)
                await self.backend.fail_tool_call(
                    call.id,
                    ToolCallFail(errorCode=exc.error_code, errorMessage=message),
                )
                raise ToolExecutionError(message, error_code=exc.error_code) from exc
            message = _format_tool_error(tool.toolCode, task_id, None, None, str(exc))
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode="TOOL_TASK_FAILED", errorMessage=message))
            raise ToolExecutionError(message, error_code="TOOL_TASK_FAILED") from exc
        except Exception as exc:
            if task_id is not None:
                await self._cancel_task(context.userId, task_id)
            message = _format_tool_error(tool.toolCode, task_id, None, None, f"{type(exc).__name__}: {exc}")
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode="TOOL_TASK_FAILED", errorMessage=message))
            raise
        if task_detail.status != "SUCCESS":
            error_code = task_detail.errorCode or f"TASK_{task_detail.status}"
            error_message = _format_task_failure(tool.toolCode, task_detail)
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode=error_code, errorMessage=error_message))
            raise ToolExecutionError(error_message, error_code=error_code)
        content_text = task_detail.result.contentText if task_detail.result is not None else ""
        agent_content_text = _agent_visible_content(tool.toolCode, content_text)
        result = _tool_result(
            tool.toolCode,
            call.id,
            arguments,
            task_detail.taskId,
            task_detail.status,
            agent_content_text,
            task_detail.result.resourceType if task_detail.result else None,
        )
        await self.backend.complete_tool_call(call.id, ToolCallComplete(resultJson=result))
        return result

    async def execute(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        arguments = self.build_arguments(context, tool, apply_placeholder_defaults=True)
        return await self.execute_with_args(context, tool, arguments)

    async def _wait_for_task(self, context: RunContext, tool_code: str, task_id: int):
        timeout_seconds = self._timeout_for_tool(tool_code, context)
        deadline = time.monotonic() + timeout_seconds
        last_status = ""
        last_detail = None
        stream_state: dict[str, int] = {"emitted_len": 0}
        while time.monotonic() <= deadline:
            detail = await self.backend.get_task_detail(context.userId, task_id)
            last_detail = detail
            if detail.status != last_status:
                last_status = detail.status
                if detail.status not in self.TERMINAL_TASK_STATUSES:
                    await self.backend.append_event(
                        context.runId,
                        RunEventCreate(
                            eventType=TOOL_TASK_PROGRESS,
                            eventText=f"Tool {tool_code} task status: {detail.status}",
                            eventJson={
                                "toolCode": tool_code,
                                "taskId": task_id,
                                "status": detail.status,
                                "progress": detail.progress,
                                "progressMessage": detail.progressMessage,
                            },
                        ),
                    )
            if _runtime_bool(context, "toolStreamRelayEnabled", settings.agent_tool_stream_relay_enabled):
                preview = extract_stream_preview(detail.progressMessage)
                if preview:
                    await self._relay_task_stream_preview(context, preview, stream_state)
            if detail.status in self.TERMINAL_TASK_STATUSES:
                return detail
            run_context = await self.backend.get_run_context(context.runId)
            if run_context.status in self.ABORTING_RUN_STATUSES:
                raise ToolExecutionError(_format_run_abort(tool_code, task_id, run_context.status, last_detail))
            await asyncio.sleep(
                _runtime_float(
                    context,
                    "toolPollIntervalSeconds",
                    self.poll_interval_seconds,
                    0.2,
                    30.0,
                )
            )
        raise ToolExecutionError(_format_tool_error(
            tool_code,
            task_id,
            getattr(last_detail, "status", None),
            getattr(last_detail, "errorCode", None),
            f"timed out after {timeout_seconds} seconds; lastProgress={getattr(last_detail, 'progress', None)}; "
            f"lastMessage={getattr(last_detail, 'progressMessage', None) or ''}",
        ), error_code="TOOL_TASK_TIMEOUT")

    def _timeout_for_tool(self, tool_code: str, context: RunContext | None = None) -> int:
        configured_timeout = _runtime_int(context, "toolExecutionTimeoutSeconds", self.timeout_seconds, 1, 3600)
        tool_text = (tool_code or "").lower()
        if _looks_like_video_tool(tool_text):
            return max(
                configured_timeout,
                _runtime_int(context, "videoToolExecutionTimeoutSeconds", settings.agent_video_tool_execution_timeout_seconds, 1, 7200),
            )
        if _looks_like_image_tool(tool_text):
            return max(
                configured_timeout,
                _runtime_int(context, "imageToolExecutionTimeoutSeconds", settings.agent_image_tool_execution_timeout_seconds, 1, 3600),
            )
        if _looks_like_music_tool(tool_text):
            return max(
                configured_timeout,
                _runtime_int(context, "musicToolExecutionTimeoutSeconds", settings.agent_music_tool_execution_timeout_seconds, 1, 7200),
            )
        return configured_timeout

    async def _cancel_task(self, user_id: int, task_id: int) -> None:
        try:
            await self.backend.cancel_task(user_id, task_id)
        except Exception:
            pass

    async def _relay_task_stream_preview(
        self,
        context: RunContext,
        preview: str,
        stream_state: dict[str, int],
    ) -> None:
        emitted_len = stream_state.get("emitted_len", 0)
        if len(preview) <= emitted_len:
            return
        delta = preview[emitted_len:]
        stream_state["emitted_len"] = len(preview)
        for chunk in _chunk_text(delta, 48):
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
            )
        try:
            upsert = getattr(self.backend, "upsert_streaming_answer", None)
            if callable(upsert):
                await upsert(context.runId, preview)
        except Exception:
            pass


def _user_accepts_builtin_examples(message: str) -> bool:
    """用户明确让系统沿用补参说明里的示例 / 默认占位时，不再卡缺参（与 IntentRouter 的续接话术对齐）。"""
    text = message.strip()
    if not text:
        return False
    if "使用默认" in text or "就用默认" in text or "就用示例" in text:
        return True
    if "按照你给的例子" in text or "按照你的例子" in text or "按你的例子" in text:
        return True
    if "用你给的例子" in text or "用你的例子" in text:
        return True
    if "照你给的例子" in text or "照你的例子" in text:
        return True
    if "全部按照" in text and ("你给" in text or "你的" in text):
        return True
    if "全部按照" in text and "例子" in text:
        return True
    return False


def _extract_labeled_argument(message: str, name: str) -> str:
    match = re.search(
        rf"(?:^|[\s,;，；]){re.escape(name)}\s*[:=：]\s*(.+?)(?=$|[\r\n,;，；])",
        message,
        flags=re.IGNORECASE,
    )
    if match is None:
        return ""
    return _normalize_extracted_value(match.group(1))


def _normalize_extracted_value(value: str) -> str:
    text = value.strip().strip("\"'")
    text = re.sub(r"^例如[：:]\s*", "", text)
    text = re.sub(r"^如[：:]\s*", "", text)
    return text.strip()


_EXTRA_FIELD_LABEL_ALIASES: dict[str, tuple[str, ...]] = {
    "style": ("文案风格",),
    "sellingPoints": ("核心卖点",),
    "targetCustomer": ("目标用户",),
    "targetAudience": ("目标人群",),
    "productName": ("产品/服务名称",),
    "topic": ("文案主题",),
}


def _field_aliases(field_key: str, prop: Any) -> list[str]:
    aliases = [field_key]
    if isinstance(prop, dict):
        title = prop.get("title")
        if isinstance(title, str) and title.strip() and title.strip() not in aliases:
            aliases.append(title.strip())
    for extra in _EXTRA_FIELD_LABEL_ALIASES.get(field_key, ()):
        if extra not in aliases:
            aliases.append(extra)
    return aliases


def _missing_user_required_fields(tool: ToolDescriptor, arguments: dict[str, Any]) -> list[str]:
    if tool.fields:
        return [
            field.fieldKey
            for field in tool.fields
            if _field_requires_user_input(field, tool)
            and (field.fieldKey not in arguments or arguments[field.fieldKey] in (None, ""))
        ]
    required = tool.inputSchema.get("required", [])
    if not isinstance(required, list):
        return []
    properties = tool.inputSchema.get("properties", {})
    return [
        name
        for name in required
        if isinstance(name, str)
        and _schema_property_user_required(properties.get(name))
        and (name not in arguments or arguments[name] in (None, ""))
    ]


def _field_requires_user_input(field, tool: ToolDescriptor) -> bool:
    strategy = (field.agentFillStrategy or "").strip().lower()
    if strategy in {"default", "derive", "none"}:
        return False
    if field.defaultValue not in (None, "") and strategy != "ask_user":
        return False
    if field.userRequired is not None:
        return bool(field.userRequired)
    properties = tool.inputSchema.get("properties", {})
    return _schema_property_user_required(properties.get(field.fieldKey))


def _schema_property_user_required(prop: Any) -> bool:
    if not isinstance(prop, dict):
        return True
    if prop.get("x-user-required") is False:
        return False
    strategy = str(prop.get("x-agent-fill-strategy") or "").strip().lower()
    if strategy in {"default", "derive", "none"}:
        return False
    if prop.get("default") not in (None, "") and strategy != "ask_user":
        return False
    return True


def _empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return not value.strip()
    if isinstance(value, (list, dict)):
        return not value
    return False


def enforce_locked_field_defaults(
    tool: ToolDescriptor,
    arguments: dict[str, Any],
    *,
    user_message: str | None = None,
) -> dict[str, Any]:
    """Force backend defaultValue for fields with agentFillStrategy=default (locks Agent/router overrides)."""
    if not tool.fields:
        return arguments
    normalized = dict(arguments)
    for field in tool.fields:
        strategy = (field.agentFillStrategy or "").strip().lower()
        if strategy != "default" or field.defaultValue in (None, ""):
            continue
        if _is_mutable_mode_field(field) and field.fieldKey in normalized and normalized[field.fieldKey] not in (None, ""):
            continue
        override = _user_explicit_override_for_locked_field(field, user_message)
        normalized[field.fieldKey] = override if override is not None else field.defaultValue
    return normalized


def _user_explicit_override_for_locked_field(field, user_message: str | None) -> Any | None:
    if not user_message or not (field.fieldKey or "").strip():
        return None
    key = field.fieldKey.lower()
    if "quality" in key:
        explicit = _explicit_quality_preference_from_message(user_message)
        if explicit is None:
            return None
        if _field_accepts_value(field, explicit):
            return explicit
        return None
    if _is_count_field(field):
        explicit_count = _explicit_image_count_from_message(user_message)
        if explicit_count is not None and _field_accepts_value(field, str(explicit_count)):
            return explicit_count
        return None
    if _is_aspect_ratio_field(field):
        explicit_ratio = _explicit_aspect_ratio_from_message(user_message)
        if explicit_ratio and _field_accepts_value(field, explicit_ratio):
            return explicit_ratio
    return None


def _is_count_field(field) -> bool:
    text = f"{getattr(field, 'fieldKey', '')} {getattr(field, 'fieldName', '')}".lower()
    return "negative" not in text and any(token in text for token in ("count", "num", "number", "张数", "数量"))


def _is_aspect_ratio_field(field) -> bool:
    text = f"{getattr(field, 'fieldKey', '')} {getattr(field, 'fieldName', '')}".lower()
    return any(token in text for token in ("aspect", "ratio", "比例", "画幅"))


def _explicit_image_count_from_message(message: str) -> int | None:
    compact = (message or "").translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    match = re.search(r"(?<![\d:：])(\d{1,2})\s*(?:张|幅|个)(?:图|图片|影像|作品)?", compact)
    if match:
        return max(1, int(match.group(1)))
    chinese_match = re.search(r"([一二两三四五六七八九十])\s*(?:张|幅|个)(?:图|图片|影像|作品)?", compact)
    if chinese_match:
        values = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
        return values.get(chinese_match.group(1))
    english_match = re.search(r"\b(\d{1,2})\s*(?:images?|pictures?|photos?)\b", compact, flags=re.IGNORECASE)
    if english_match:
        return max(1, int(english_match.group(1)))
    return None


def _explicit_aspect_ratio_from_message(message: str) -> str | None:
    compact = (message or "").translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    match = re.search(r"(?<!\d)(\d{1,2})\s*[:：]\s*(\d{1,2})(?!\d)", compact)
    if not match:
        return None
    width = int(match.group(1))
    height = int(match.group(2))
    if width <= 0 or height <= 0:
        return None
    return f"{width}:{height}"


def _explicit_quality_preference_from_message(message: str) -> str | None:
    compact = re.sub(r"\s+", "", (message or "").lower())
    if not compact:
        return None
    high_tokens = (
        "qualityhigh",
        "quality=high",
        "质量high",
        "高质量档",
        "高档质量",
        "用high",
        "使用high",
        "这次high",
    )
    low_tokens = (
        "qualitylow",
        "quality=low",
        "质量low",
        "低质量档",
        "低档质量",
        "用low",
        "使用low",
        "这次low",
    )
    if any(token in compact for token in high_tokens):
        return "high"
    if any(token in compact for token in low_tokens):
        return "low"
    return None


def _field_accepts_value(field, value: str) -> bool:
    options = getattr(field, "options", None)
    option_values = _option_values(options)
    if option_values:
        return value in {str(item).strip().lower() for item in option_values}
    return True


def _is_mutable_mode_field(field) -> bool:
    key = (getattr(field, "fieldKey", "") or "").strip().lower()
    if key not in {"custommode", "custom_mode"}:
        return False
    field_type = (getattr(field, "fieldType", "") or "").strip().lower()
    return field_type in {"radio", "select", "checkbox", "boolean", "bool"}


def _with_field_strategy_defaults(tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
    if not tool.fields:
        return arguments
    normalized = dict(arguments)
    for field in tool.fields:
        if field.fieldKey in normalized and normalized[field.fieldKey] not in (None, ""):
            continue
        strategy = (field.agentFillStrategy or "").strip().lower()
        if strategy in {"default", "derive"} and field.defaultValue not in (None, ""):
            normalized[field.fieldKey] = field.defaultValue
    return normalized


def _with_attached_file_defaults(context: RunContext, tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
    normalized = apply_user_selected_attachment_priority(context, tool, arguments)
    properties = tool.inputSchema.get("properties", {})
    if not isinstance(properties, dict):
        properties = {}

    duration_match = re.search(r"(\d+)\s*秒", context.message or "")
    if duration_match:
        for key in ("duration", "videoDuration", "video_duration", "length", "seconds"):
            if key in properties and not normalized.get(key):
                normalized[key] = duration_match.group(1)
                break

    return normalized


def _absolute_backend_url(url: str | None) -> str:
    raw = (url or "").strip()
    if not raw:
        return ""
    if raw.startswith(("http://", "https://", "data:")):
        return raw
    base = settings.backend_internal_base_url.rstrip("/")
    path = raw if raw.startswith("/") else f"/{raw}"
    return f"{base}{path}"


def _with_generation_argument_defaults(
    context: RunContext,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
    *,
    workspace_memory_context: str = "",
    prompt_mode: PromptMode | None = None,
) -> dict[str, Any]:
    normalized = dict(arguments)
    properties = tool.inputSchema.get("properties", {})
    if not isinstance(properties, dict):
        return normalized

    mode = prompt_mode or resolve_prompt_mode(context, tool)
    if _is_v2_lite_image_schema(tool):
        normalized = _with_v2_lite_image_defaults(context, normalized, mode=mode)
    prompt_key = _infer_prompt_field(tool)
    if prompt_key and not normalized.get(prompt_key):
        should_defer = True if _is_v2_lite_image_schema(tool) else _should_defer_prompt_to_session_state(context, tool)
        if _is_v2_lite_image_schema(tool) and str(normalized.get("operation") or "").lower() in {"generate", "composite"}:
            should_defer = True
        if not should_defer:
            prompt = _compose_generation_prompt(
                context,
                tool,
                workspace_memory_context=workspace_memory_context,
                prompt_mode=mode,
            )
            if prompt:
                normalized[prompt_key] = prompt
    elif prompt_key and mode == PromptMode.REFERENCE_EDIT_DELTA:
        normalized[prompt_key] = reference_edit_prompt(context.message)

    for key, prop in properties.items():
        if not isinstance(key, str) or key in normalized and normalized[key] not in (None, ""):
            continue
        default_value = _safe_default_for_property(key, prop)
        if default_value not in (None, ""):
            normalized[key] = default_value

    for field in tool.fields:
        if field.fieldKey in normalized and normalized[field.fieldKey] not in (None, ""):
            continue
        default_value = _safe_default_for_field(field)
        if default_value not in (None, ""):
            normalized[field.fieldKey] = default_value
    return normalized


def _is_v2_lite_image_schema(tool: ToolDescriptor) -> bool:
    properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
    return isinstance(properties, dict) and {"operation", "references", "base_image_ref"}.issubset(properties.keys())


def _with_v2_lite_image_defaults(
    context: RunContext,
    arguments: dict[str, Any],
    *,
    mode: PromptMode,
) -> dict[str, Any]:
    normalized = dict(arguments)
    latest = latest_generated_image_state(context.recentToolCalls)
    if mode == PromptMode.REFERENCE_EDIT_DELTA and latest is not None:
        normalized.setdefault("operation", "edit")
        normalized.setdefault("base_image_ref", "latest_generated_image.url")
        return normalized
    normalized.setdefault("operation", "generate")
    return normalized


def _looks_like_image_tool(tool_code: str) -> bool:
    return any(marker in tool_code for marker in ("image", "img", "photo", "picture", "gpt_image"))


def _looks_like_video_tool(tool_code: str) -> bool:
    return any(marker in tool_code for marker in ("video", "movie", "kling", "seedance"))


def _looks_like_music_tool(tool_code: str) -> bool:
    return any(marker in tool_code for marker in ("music", "suno", "song", "chirp"))


def _runtime_int(context: RunContext | None, field: str, fallback: int, min_value: int, max_value: int) -> int:
    return runtime_int(context, field, fallback, min_value, max_value)


def _runtime_bool(context: RunContext | None, field: str, fallback: bool) -> bool:
    return runtime_bool(context, field, fallback)


def _runtime_float(context: RunContext | None, field: str, fallback: float, min_value: float, max_value: float) -> float:
    return runtime_float(context, field, fallback, min_value, max_value)


def _infer_prompt_field(tool: ToolDescriptor) -> str | None:
    properties = tool.inputSchema.get("properties", {})
    if not isinstance(properties, dict):
        return None
    preferred = (
        "generation_prompt",
        "prompt",
        "positivePrompt",
        "imagePrompt",
        "videoPrompt",
        "textPrompt",
        "description",
        "content",
        "subject",
        "topic",
    )
    for key in preferred:
        if key in properties and key != "userRequest":
            return key
    for key, prop in properties.items():
        if not isinstance(key, str) or key == "userRequest":
            continue
        if _is_non_prompt_generation_control(key):
            continue
        text = f"{key} {_prop_text(prop)}".lower()
        if any(token in text for token in ("prompt", "description", "subject", "topic", "画面", "提示词", "主题")):
            return key
    return None


def _should_defer_prompt_to_session_state(context: RunContext, tool: ToolDescriptor) -> bool:
    if infer_output_modality(tool) != "image":
        return False
    if not _tool_accepts_base_image(tool):
        return False
    return bool(format_session_state_context(context))


def _tool_accepts_base_image(tool: ToolDescriptor) -> bool:
    properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
    if not isinstance(properties, dict):
        properties = {}
    if any(key in properties for key in ("base_image_url", "baseImageUrl", "base_image", "baseImage", "base_image_ref")):
        return True
    return any((field.fieldKey or "") in {"base_image_url", "baseImageUrl", "base_image", "baseImage", "base_image_ref"} for field in tool.fields)


def _is_non_prompt_generation_control(key: str) -> bool:
    lower = key.lower()
    return any(
        token in lower
        for token in (
            "ratio",
            "aspect",
            "size",
            "width",
            "height",
            "count",
            "num",
            "number",
            "quality",
            "seed",
            "steps",
            "duration",
            "fps",
            "negative",
        )
    )


def _compose_generation_prompt(
    context: RunContext,
    tool: ToolDescriptor,
    *,
    workspace_memory_context: str = "",
    prompt_mode: PromptMode | None = None,
) -> str:
    mode = prompt_mode or resolve_prompt_mode(context, tool)
    if mode == PromptMode.REFERENCE_EDIT_DELTA:
        return reference_edit_prompt(context.message)
    request = _compact(context.message, 600)
    if not request:
        return ""
    modality = infer_output_modality(tool)
    memory_clause = _workspace_memory_prompt_clause(context.message, workspace_memory_context)
    if modality == "image":
        prompt = (
            f"{request}。高质量图片，主体清晰，构图自然，细节丰富，审美高级；"
            "如果用户只给出简短主体，请自动补足适合商业生成的场景、光线、镜头和风格。"
        )
        prompt = _append_reference_semantics_clause(prompt, context)
        return _append_memory_prompt_clause(prompt, memory_clause)
    if modality == "video":
        prompt = (
            f"{request}。高质量短视频画面，主体明确，运动自然，镜头连贯，节奏清晰；"
            "如果用户只给出简短主体，请自动补足场景、镜头运动和视觉风格。"
        )
        return _append_memory_prompt_clause(prompt, memory_clause)
    if modality == "audio":
        return f"{request}。语气自然，节奏清晰，适合直接生成音频。"
    if modality == "text":
        return _append_memory_prompt_clause(request, memory_clause)
    return _append_memory_prompt_clause(request, memory_clause)


def _append_reference_semantics_clause(prompt: str, context: RunContext) -> str:
    plan = build_reference_plan(context)
    if not plan.has_explicit_references or not plan.mentions:
        return prompt

    labels: list[str] = []
    for mention in plan.mentions:
        label = llm_token_for_mention(mention)
        if label and label not in labels:
            labels.append(label)
    if not labels:
        return prompt

    if len(labels) >= 2:
        second_title, second_desc, final_desc = _second_reference_role(context)
        lines = [
            "参考图角色约束（必须严格执行，不可交换）：",
            f"1) 主体身份参考：{labels[0]}，用于保留人物主体与形象（五官、发型、服饰、体态）；若用户要求画风不变，也以此图为画风基准；",
            f"2) {second_title}：{labels[1]}，{second_desc}",
        ]
        for index, label in enumerate(labels[2:], start=3):
            lines.append(f"{index}) 补充参考：{label}，仅用于细节补充，不改变主体身份。")
        lines.append(final_desc)
        return f"{prompt} {' '.join(lines)}"

    only = labels[0]
    return (
        f"{prompt} 参考图角色约束：{only} 为主体参考图。"
        "优先保留主体身份特征；若用户额外描述风格，仅在不改变主体身份前提下进行风格化。"
    )


def _second_reference_role(context: RunContext) -> tuple[str, str, str]:
    request = readable_positional_prompt(context) or context.message or ""
    compact = re.sub(r"\s+", "", request).lower()
    wants_action = any(token in compact for token in ("动作", "姿势", "姿态", "pose", "action", "做出", "perform"))
    wants_composition = any(token in compact for token in ("构图", "composition", "framing", "layout", "版式", "镜头", "样式"))
    wants_style = any(token in compact for token in ("风格", "画风", "style", "笔触", "色彩", "质感"))

    if wants_action and wants_composition:
        return (
            "动作与构图参考",
            "仅提供动作、姿态、肢体动态、构图、镜头和版式参考；不得替换主体身份；除非用户明确要求，否则不得覆盖1号参考的画风。",
            "最终输出需明确保证：主体与画风以1号参考为准，动作和构图主要来自2号参考。",
        )
    if wants_action:
        return (
            "动作/姿态参考",
            "仅提供动作、姿态和肢体动态参考；不得替换主体身份或画风。",
            "最终输出需明确保证：主体与画风以1号参考为准，动作主要来自2号参考。",
        )
    if wants_composition:
        return (
            "构图/版式参考",
            "仅提供构图、镜头、画面布局和版式参考；不得替换主体身份或画风。",
            "最终输出需明确保证：主体与画风以1号参考为准，构图主要来自2号参考。",
        )
    if wants_style:
        return (
            "风格/画风参考",
            "仅迁移风格、笔触、色彩、光影和质感；不得替换主体身份。",
            "最终输出需明确保证：主体来自1号参考，风格主要来自2号参考。",
        )
    return (
        "第二参考图",
        "按用户句子中的位置关系使用该参考图；不得替换1号参考中的主体身份。",
        "最终输出需明确保证：主体来自1号参考，其余参考关系按用户原句执行。",
    )


def finalize_generation_arguments(
    context: RunContext,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
    *,
    prompt_mode: PromptMode | None = None,
) -> dict[str, Any]:
    """Re-apply reference role constraints after LLM/enrich overwrites the prompt field."""
    mode = prompt_mode or resolve_prompt_mode(context, tool)
    if _is_music_generation_tool(tool):
        arguments = _finalize_music_generation_arguments(context, tool, arguments)
    if _is_v2_lite_image_schema(tool):
        arguments = _finalize_v2_lite_image_arguments(context, arguments, mode=mode)
        return arguments
    if mode == PromptMode.REFERENCE_EDIT_DELTA:
        prompt_key = _infer_prompt_field(tool)
        if not prompt_key:
            return arguments
        updated = dict(arguments)
        updated[prompt_key] = reference_edit_prompt(context.message)
        updated["userRequest"] = context.message
        return updated
    if infer_output_modality(tool) != "image":
        return arguments
    prompt_key = _infer_prompt_field(tool)
    if not prompt_key:
        return arguments
    prompt = arguments.get(prompt_key)
    if not isinstance(prompt, str) or not prompt.strip():
        return arguments
    if REFERENCE_SEMANTICS_MARKER in prompt:
        return arguments
    if _has_base_image_argument(arguments):
        return arguments
    updated = dict(arguments)
    updated[prompt_key] = _append_reference_semantics_clause(prompt.strip(), context)
    return updated


def _is_music_generation_tool(tool: ToolDescriptor) -> bool:
    text = f"{tool.toolCode} {tool.toolName or ''} {tool.description or ''}".lower()
    return any(token in text for token in ("suno", "music_generation", "音乐生成", "歌曲", "音乐"))


def _finalize_music_generation_arguments(context: RunContext, tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
    prompt = arguments.get("prompt")
    user_requested_custom = _user_requested_music_custom_mode(context.message)
    prompt_too_long = isinstance(prompt, str) and len(prompt) > 500
    if not user_requested_custom and not prompt_too_long:
        return arguments
    custom_key = _custom_mode_key(tool, arguments)
    if not custom_key:
        return arguments
    if _truthy(arguments.get(custom_key)):
        return arguments
    updated = dict(arguments)
    updated[custom_key] = True
    return updated


def _user_requested_music_custom_mode(message: str | None) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    return any(
        token in compact
        for token in (
            "自定义模式",
            "高级模式",
            "custommode",
            "custom_mode",
            "custommode=true",
            "用custom",
            "使用custom",
        )
    )


def _custom_mode_key(tool: ToolDescriptor, arguments: dict[str, Any]) -> str | None:
    for key in ("customMode", "custom_mode"):
        if key in arguments:
            return key
    properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
    if isinstance(properties, dict):
        for key in ("customMode", "custom_mode"):
            if key in properties:
                return key
    for field in tool.fields:
        key = (field.fieldKey or "").strip()
        if key.lower() in {"custommode", "custom_mode"}:
            return key
    return None


def _truthy(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on", "是", "高级"}


def _finalize_v2_lite_image_arguments(
    context: RunContext,
    arguments: dict[str, Any],
    *,
    mode: PromptMode,
) -> dict[str, Any]:
    updated = dict(arguments)
    latest = latest_generated_image_state(context.recentToolCalls)
    operation = str(updated.get("operation") or "").strip().lower()
    if mode == PromptMode.REFERENCE_EDIT_DELTA or operation in {"edit", "variation"}:
        updated["operation"] = operation if operation in {"edit", "variation"} else "edit"
        if latest is not None:
            updated.setdefault("base_image_ref", "latest_generated_image.url")
        updated["base_prompt"] = sanitize_visual_prompt(str(updated.get("base_prompt") or ""))
        updated["modification_prompt"] = _namespace_current_attachment_labels(
            context,
            sanitize_visual_prompt(str(updated.get("modification_prompt") or "")),
        )
        if "references" in updated:
            updated["references"] = _namespace_v2_references(context, updated.get("references"))
        updated.pop("generation_prompt", None)
        updated.pop("prompt", None)
    elif not operation:
        updated["operation"] = "generate"
    elif operation in {"generate", "composite"} and "references" in updated:
        updated["references"] = _namespace_v2_references(context, updated.get("references"))
    return updated


def compile_v2_lite_image_task_params(
    arguments: dict[str, Any],
    *,
    context: RunContext | None = None,
) -> dict[str, Any]:
    """Compile the agent-facing v2-lite image schema into physical workbench params."""
    source = dict(arguments or {})
    operation = str(source.get("operation") or "generate").strip().lower()
    physical = {
        key: value
        for key, value in source.items()
        if key
        not in {
            "operation",
            "generation_prompt",
            "base_prompt",
            "modification_prompt",
            "base_image_ref",
            "references",
            "routing_notes",
            "prompt",
            "base_image_url",
            "reference_images",
        }
    }

    prompt = _compile_v2_lite_prompt(source, operation)
    if prompt:
        physical["prompt"] = prompt

    base_image_ref = str(source.get("base_image_ref") or "").strip() if operation in {"edit", "variation"} else ""
    base_image_ref = _resolve_v2_lite_physical_image_ref(context, base_image_ref)
    if base_image_ref:
        physical["base_image_url"] = base_image_ref

    reference_images = _compile_v2_lite_reference_images(source, context=context)
    if reference_images:
        physical["reference_images"] = reference_images

    aspect_ratio = str(source.get("aspect_ratio") or source.get("aspectRatio") or "").strip()
    if aspect_ratio:
        physical["aspectRatio"] = aspect_ratio
        physical.pop("aspect_ratio", None)

    if "negative_prompt" in source and source.get("negative_prompt") not in (None, ""):
        physical["negative_prompt"] = source.get("negative_prompt")
    if "count" in source and source.get("count") not in (None, ""):
        physical["count"] = source.get("count")
    return physical


def _compile_v2_lite_prompt(arguments: dict[str, Any], operation: str) -> str:
    if operation in {"edit", "variation"}:
        base_prompt = sanitize_visual_prompt(str(arguments.get("base_prompt") or ""))
        modification_prompt = sanitize_visual_prompt(str(arguments.get("modification_prompt") or ""))
        parts: list[str] = []
        if base_prompt:
            parts.append(base_prompt)
        if modification_prompt:
            parts.extend(["EDIT INSTRUCTION:", modification_prompt])
        reference_lines = _v2_reference_routing_lines(arguments.get("references"))
        if reference_lines:
            parts.append("REFERENCE ROUTING:")
            parts.extend(reference_lines)
            parts.append("STRICT REFERENCE ROLE PRESERVATION:")
            parts.extend(_v2_strict_reference_role_lines(arguments.get("references")))
        if base_prompt or modification_prompt:
            parts.append("STRICT PRESERVATION:")
            parts.extend(_v2_strict_preservation_lines(modification_prompt, arguments.get("references")))
        return "\n\n".join(part for part in parts if str(part).strip())

    prompt = str(arguments.get("generation_prompt") or "").strip()
    if not prompt:
        return ""
    reference_lines = _v2_reference_routing_lines(arguments.get("references"))
    if reference_lines:
        return "\n\n".join(
            [
                prompt,
                "REFERENCE ROUTING:",
                *reference_lines,
                "STRICT REFERENCE ROLE PRESERVATION:",
                *_v2_strict_reference_role_lines(arguments.get("references")),
            ]
        )
    return prompt


def _compile_v2_lite_reference_images(
    arguments: dict[str, Any],
    *,
    context: RunContext | None = None,
) -> list[str]:
    images: list[str] = []
    seen: set[str] = set()

    def add(value: Any) -> None:
        if isinstance(value, str):
            text = _resolve_v2_lite_physical_image_ref(context, value)
            if text and text not in seen:
                seen.add(text)
                images.append(text)

    for reference in _sorted_v2_lite_references(arguments.get("references")):
        add(reference.get("source_ref"))

    legacy = arguments.get("reference_images")
    if isinstance(legacy, list):
        for item in legacy:
            add(item)
    else:
        add(legacy)
    return images


def _resolve_v2_lite_physical_image_ref(context: RunContext | None, value: Any) -> str:
    text = str(value or "").strip()
    if not text or context is None:
        return text
    if text.startswith(("http://", "https://", "data:image/", "/")):
        return text
    latest = latest_generated_image_state(context.recentToolCalls)
    if latest is not None and text in {"latest_generated_image", "latest_generated_image.url", "{latest_generated_image.url}"}:
        return latest.image_url
    plan = build_reference_plan(context)
    lookup: dict[str, str] = {}
    for index, mention in enumerate(plan.mentions, start=1):
        url = str(mention.url or "").strip()
        if not url:
            continue
        for key in (
            current_attachment_alias(index),
            mention.assetKey,
            str(mention.fileId or "") if mention.fileId is not None else "",
            mention.url,
            mention.token,
            mention.refLabel,
            llm_token_for_mention(mention),
            mention.name,
        ):
            normalized = str(key or "").strip()
            if normalized and normalized not in lookup:
                lookup[normalized] = url
    resolved = lookup.get(text)
    if resolved:
        return resolved
    base = re.match(r"(@(?:图|图片)\d+)", text)
    if base:
        resolved = lookup.get(base.group(1))
        if resolved:
            return resolved
    alias = re.fullmatch(r"\[当前参考图_(\d+)\]", text)
    if alias:
        index = int(alias.group(1))
        if 1 <= index <= len(plan.mentions):
            return str(plan.mentions[index - 1].url or "").strip()
    return text


_V2_REFERENCE_ROLE_PRIORITY = {
    "face_ref": 100,
    "identity_ref": 100,
    "controlnet_pose_ref": 80,
    "pose_ref": 70,
    "composition_ref": 70,
    "object_ref": 65,
    "style_ref": 60,
    "background_ref": 50,
    "supplemental_ref": 10,
}


def _sorted_v2_lite_references(references: Any) -> list[dict[str, Any]]:
    if not isinstance(references, list):
        return []
    items = [item for item in references if isinstance(item, dict)]
    return sorted(
        items,
        key=lambda item: -_V2_REFERENCE_ROLE_PRIORITY.get(str(item.get("role") or ""), 0),
    )


def _v2_reference_routing_lines(references: Any) -> list[str]:
    lines: list[str] = []
    for reference in _sorted_v2_lite_references(references):
        ref_id = str(reference.get("id") or "").strip() or "reference"
        role = str(reference.get("role") or "").strip() or "supplemental_ref"
        source_ref = str(reference.get("source_ref") or "").strip()
        notes = str(reference.get("notes") or "").strip()
        label = f"{ref_id} ({source_ref})" if source_ref else ref_id
        line = f"- {label}: {role}."
        if notes:
            line += f" {notes}"
        lines.append(line)
    return lines


def _v2_strict_preservation_lines(modification_prompt: str, references: Any) -> list[str]:
    if _v2_requests_identity_replacement(modification_prompt) and _v2_has_identity_reference(references):
        return [
            "Allow identity and face to change according to the current face_ref or identity_ref.",
            "Preserve pose, camera, composition, lighting style, background, outfit, and art direction unless explicitly changed.",
        ]
    return [
        "Preserve all visual elements from the base image unless explicitly changed in EDIT INSTRUCTION.",
        "Do not change identity, outfit, pose, camera, composition, lighting style, or art direction unless explicitly requested.",
    ]


def _v2_strict_reference_role_lines(references: Any) -> list[str]:
    lines = [
        "Follow each current reference image only for its declared role.",
        "Do not swap identity/face references with pose, composition, control, style, background, or object references.",
    ]
    roles = {str(reference.get("role") or "").strip() for reference in _sorted_v2_lite_references(references)}
    if roles.intersection({"face_ref", "identity_ref"}):
        lines.append("face_ref/identity_ref controls character identity, facial features, hair, and body traits; it must not be overridden by pose/style references.")
    if roles.intersection({"pose_ref", "composition_ref", "controlnet_pose_ref"}):
        lines.append("pose_ref/composition_ref/controlnet_pose_ref controls action, body pose, camera angle, framing, and layout; it must not replace the subject identity.")
    if "style_ref" in roles:
        lines.append("style_ref controls visual style, rendering language, palette, and texture only when compatible with the user's requested identity and composition roles.")
    if "background_ref" in roles:
        lines.append("background_ref controls environment and scene setting only; it must not replace the subject identity.")
    return lines


def _v2_has_identity_reference(references: Any) -> bool:
    return any(
        str(reference.get("role") or "").strip() in {"face_ref", "identity_ref"}
        for reference in _sorted_v2_lite_references(references)
    )


def _v2_requests_identity_replacement(text: str) -> bool:
    compact = re.sub(r"\s+", "", (text or "").lower())
    chinese_patterns = (
        r"换脸",
        r"(?:人物|模特|角色|主体|女性|男人|女人|女孩|男孩|脸|面部|五官).{0,12}(?:换成|替换|换为)",
        r"(?:换成|替换为|换为).{0,12}(?:人物|模特|角色|主体|女性|男人|女人|女孩|男孩|脸|面部|五官)",
    )
    if any(re.search(pattern, compact) for pattern in chinese_patterns):
        return True
    return any(
        token in compact
        for token in (
            "replaceface",
            "changeface",
            "swapface",
            "replaceperson",
            "replacecharacter",
            "differentidentity",
        )
    )


def _namespace_v2_references(context: RunContext, references: Any) -> Any:
    if not isinstance(references, list):
        return references
    normalized: list[Any] = []
    for item in references:
        if not isinstance(item, dict):
            normalized.append(item)
            continue
        ref = dict(item)
        source_ref = ref.get("source_ref")
        if isinstance(source_ref, str):
            ref["source_ref"] = _namespace_current_attachment_labels(context, source_ref)
        notes = ref.get("notes")
        if isinstance(notes, str):
            ref["notes"] = _namespace_current_attachment_labels(context, notes)
        normalized.append(ref)
    return normalized


def _namespace_current_attachment_labels(context: RunContext, text: str) -> str:
    value = str(text or "")
    if not value:
        return value
    for original, alias in _current_attachment_label_replacements(context):
        value = value.replace(original, alias)
    return value


def _current_attachment_label_replacements(context: RunContext) -> list[tuple[str, str]]:
    plan = build_reference_plan(context)
    replacements: list[tuple[str, str]] = []
    seen: set[str] = set()
    for index, mention in enumerate(plan.mentions, start=1):
        alias = current_attachment_alias(index)
        for label in (
            llm_token_for_mention(mention),
            mention.refLabel,
            mention.token,
            mention.name,
        ):
            raw = str(label or "").strip()
            if raw and raw != alias and raw not in seen:
                seen.add(raw)
                replacements.append((raw, alias))
    replacements.sort(key=lambda item: len(item[0]), reverse=True)
    return replacements


def _has_base_image_argument(arguments: dict[str, Any]) -> bool:
    for key in ("base_image_url", "baseImageUrl", "base_image", "baseImage", "base_image_ref"):
        value = arguments.get(key)
        if isinstance(value, str) and value.strip():
            return True
    return False


def _append_memory_prompt_clause(prompt: str, memory_clause: str) -> str:
    if not memory_clause:
        return prompt
    return f"{prompt} 长期偏好参考：{memory_clause}"


def _workspace_memory_prompt_clause(message: str, workspace_memory_context: str) -> str:
    if not workspace_memory_context.strip():
        return ""
    if not _message_requests_workspace_preferences(message):
        return ""
    compact = re.sub(r"\s+", " ", workspace_memory_context).strip()
    compact = compact.replace("Frozen workspace memory snapshot", "").strip()
    compact = compact.replace("Priority: current user instruction > live tool result > recent tool calls > long-term memory.", "").strip()
    return _limit_text(compact, 900)


def _message_requests_workspace_preferences(message: str) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    return any(
        token in compact
        for token in (
            "我的喜好",
            "我的审美",
            "我的偏好",
            "按我喜欢",
            "根据我喜欢",
            "根据我的喜好",
            "根据我的审美",
            "按你了解我",
            "你了解我",
            "mytaste",
            "mypreference",
            "preferences",
        )
    )


def _safe_default_for_field(field) -> Any | None:
    if field.defaultValue not in (None, ""):
        return field.defaultValue
    strategy = (field.agentFillStrategy or "").strip().lower()
    if strategy not in {"default", "derive", "none"} and field.userRequired:
        return None
    return _safe_default_for_key(field.fieldKey, field.fieldType, field.options)


def _safe_default_for_property(key: str, prop: Any) -> Any | None:
    if not isinstance(prop, dict):
        return None
    if prop.get("default") not in (None, ""):
        return prop.get("default")
    if _schema_property_user_required(prop):
        return None
    return _safe_default_for_key(key, str(prop.get("type") or ""), prop.get("enum"))


def _safe_default_for_key(key: str, field_type: str = "", options: Any = None) -> Any | None:
    lower = key.lower()
    if "prompt" in lower or lower in {"description", "subject", "topic", "content"}:
        return None
    option_values = _option_values(options)
    if option_values:
        normalized_options = {str(item).strip().lower() for item in option_values}
        for preferred in ("low", "auto", "1:1", "1024x1024", "default", "standard", "normal", "medium"):
            if preferred in normalized_options:
                for item in option_values:
                    if str(item).strip().lower() == preferred:
                        return item
        return option_values[0]
    if any(token in lower for token in ("ratio", "aspect")):
        return "1:1"
    if lower in {"size", "image_size", "imageSize"} or "size" in lower:
        return "1024x1024"
    if any(token in lower for token in ("count", "num", "number")) and "negative" not in lower:
        return 1
    if "quality" in lower:
        return "low"
    if "style" in lower and "strength" not in lower:
        return "auto"
    if "negative" in lower:
        return ""
    if field_type in {"boolean", "bool"}:
        return False
    return None


def _option_values(options: Any) -> list[Any]:
    if not isinstance(options, list):
        return []
    values: list[Any] = []
    for item in options:
        if isinstance(item, dict):
            value = item.get("value")
        else:
            value = item
        if value not in (None, ""):
            values.append(value)
    return values


def _prop_text(prop: Any) -> str:
    if not isinstance(prop, dict):
        return ""
    values = [prop.get("title"), prop.get("description")]
    return " ".join(str(value) for value in values if value)


def _recent_user_messages(context: RunContext) -> list[str]:
    history_pairs: list[tuple[str, str]] = []
    for item in context.history:
        role = (item.role or "").strip().lower()
        content = (item.content or "").strip()
        if content:
            history_pairs.append((role, content))
    if not history_pairs or history_pairs[-1][1] != context.message.strip():
        history_pairs.append(("user", context.message.strip()))

    recent: list[str] = []
    seen_user = False
    for role, content in reversed(history_pairs):
        if role in {"user", "human"}:
            recent.append(content)
            seen_user = True
            continue
        if role in {"assistant", "ai"} and _is_tool_guidance_message(content):
            continue
        if seen_user:
            break
    recent.reverse()
    return recent or [context.message.strip()]


def _is_tool_guidance_message(content: str) -> bool:
    has_tool_hint = "如果想使用「" in content or "看起来你想使用「" in content
    return has_tool_hint and (
        "请在同一条或下一条消息里按下面补充" in content
        or "这个工具需要补充以下信息" in content
    )


def _with_xiaohongshu_defaults(message: str, arguments: dict[str, Any]) -> dict[str, Any]:
    user_request = message.strip()
    normalized = dict(arguments)
    normalized.setdefault("productName", _compact(user_request, 80) or "用户提供的产品或服务")
    normalized.setdefault("targetCustomer", "未指定目标用户")
    normalized.setdefault("style", "种草")
    normalized.setdefault("sellingPoints", user_request or "用户希望生成小红书文案")
    normalized.setdefault("extraInfo", "")
    return normalized


def _tool_result(
    tool_code: str,
    tool_call_id: int,
    arguments: dict[str, Any],
    task_id: int,
    task_status: str,
    content_text: str | None,
    resource_type: str | None,
) -> dict[str, Any]:
    safe_content_text = _sanitize_media_payload_text(content_text)
    return {
        "success": True,
        "toolCode": tool_code,
        "toolCallId": tool_call_id,
        "taskId": task_id,
        "status": task_status,
        "arguments": arguments,
        "resultSummary": safe_content_text,
        "data": {
            "resourceType": resource_type,
            "contentText": safe_content_text,
        },
        "summary": safe_content_text,
    }


def _agent_visible_content(tool_code: str, content_text: str | None) -> str:
    text = _sanitize_media_payload_text(content_text)
    if tool_code != "digital_human_agent" or not text.strip():
        return text

    final_video = _first_match(text, r"(?:最终成片|成片)[:：]\s*(\S+?\.mp4(?:\?\S*)?)")
    if final_video:
        return f"视频已生成，可直接播放或下载：{_sanitize_link(final_video)}"
    return "视频已生成，可直接播放或下载。"


def _format_task_failure(tool_code: str, detail: TaskDetailResponse) -> str:
    message = detail.errorMessage or detail.progressMessage or f"Tool task ended with status {detail.status}"
    return _format_tool_error(tool_code, detail.taskId, detail.status, detail.errorCode, message)


def _format_run_abort(tool_code: str, task_id: int, run_status: str | None, detail: TaskDetailResponse | None) -> str:
    if detail is None:
        return _format_tool_error(tool_code, task_id, None, None, f"agent run ended with status {run_status}")
    message = detail.errorMessage or detail.progressMessage or f"agent run ended with status {run_status}"
    return _format_tool_error(tool_code, task_id, detail.status, detail.errorCode, message, run_status=run_status)


def _format_tool_error(
    tool_code: str,
    task_id: int | None,
    task_status: str | None,
    task_error_code: str | None,
    message: str | None,
    *,
    run_status: str | None = None,
) -> str:
    parts = [f"tool={tool_code}"]
    if task_id is not None:
        parts.append(f"taskId={task_id}")
    if run_status:
        parts.append(f"runStatus={run_status}")
    if task_status:
        parts.append(f"taskStatus={task_status}")
    if task_error_code:
        parts.append(f"taskErrorCode={task_error_code}")
    text = (message or "").strip()
    if text:
        parts.append(f"message={_limit_text(text, 1200)}")
    return "; ".join(parts)


def _limit_text(value: str, max_length: int) -> str:
    if len(value) <= max_length:
        return value
    return value[: max(0, max_length - 16)] + "...[truncated]"


def _sanitize_media_payload_text(value: str | None, max_length: int = 12000) -> str:
    if not value:
        return ""
    text = re.sub(
        r"data:[^\s\"']+;base64,[A-Za-z0-9+/=\r\n]+",
        "[inline-media-base64-omitted]",
        value,
        flags=re.IGNORECASE,
    )
    text = re.sub(
        r'"b64_json"\s*:\s*"[A-Za-z0-9+/=\r\n]+"',
        '"b64_json":"[inline-media-base64-omitted]"',
        text,
        flags=re.IGNORECASE,
    )
    return _limit_text(text, max_length)


def _first_match(text: str, pattern: str) -> str:
    match = re.search(pattern, text, flags=re.IGNORECASE)
    return match.group(1).strip() if match else ""


def _sanitize_link(value: str) -> str:
    return value.strip().replace(")", "").replace("]", "").rstrip("，。,.、；;")


def _compact(value: str, max_length: int) -> str:
    normalized = re.sub(r"\s+", " ", value).strip()
    return normalized[:max_length]


def _chunk_text(value: str, size: int = 32) -> list[str]:
    return [value[index : index + size] for index in range(0, len(value), size)] or [""]
