import asyncio
import json
import logging
import re
import time
from typing import Any

from app.clients.backend_client import BackendBusinessError
from app.config import settings
from app.credit_messages import credit_message_from_backend_error
from app.core.attachment_precheck import format_attachment_error, validate_attachment_arguments
from app.core.event_types import MESSAGE_DELTA, TOOL_CALL_REJECTED, TOOL_TASK_DISPATCHED, TOOL_TASK_PROGRESS
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, TaskCreate, TaskDetailResponse, ToolCallComplete, ToolCallCreate, ToolCallFail, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority
from app.runtime.runtime_settings import runtime_bool, runtime_float, runtime_int
from app.tools.registry import infer_output_modality
from app.tools.stream_preview import extract_stream_preview


logger = logging.getLogger(__name__)


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

    def build_arguments(self, context: RunContext, tool: ToolDescriptor, *, apply_placeholder_defaults: bool = True) -> dict[str, Any]:
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
            arguments = _with_generation_argument_defaults(context, tool, arguments)
            arguments = _with_field_strategy_defaults(tool, arguments)
        arguments = _with_attached_file_defaults(context, tool, arguments)
        return arguments

    def missing_required_arguments(self, context: RunContext, tool: ToolDescriptor) -> list[str]:
        if tool.toolCode == "xiaohongshu_copywriting" and _user_accepts_builtin_examples(context.message):
            return []
        arguments = self.build_arguments(context, tool, apply_placeholder_defaults=False)
        return _missing_user_required_fields(tool, arguments)

    async def enrich_arguments(self, message: str, tool: ToolDescriptor, existing_args: dict[str, Any] | None = None) -> dict[str, Any]:
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
        prompt = (
            "You are filling arguments for an AI tool call. Return a pure JSON object only.\n"
            "Fill safe, low-risk generation fields from the user's request, recent context, existing arguments, "
            "and the tool schema. For prompt-like fields, expand short user intent into a useful production prompt. "
            "Do not invent credentials, account ids, payment, publishing authorization, personal private data, "
            "or other high-risk values. Preserve existing arguments unless a field is empty.\n\n"
            f"Tool: {tool.toolName or tool.toolCode}\n"
            f"Description: {tool.description or 'none'}\n"
            f"Fields:\n{chr(10).join(field_descriptions)}\n"
            f"{existing_info}\n"
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
            if isinstance(key, str) and isinstance(value, str) and value.strip() and key not in merged:
                merged[key] = value.strip()
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
        call = await self.backend.create_tool_call(context.runId, ToolCallCreate(toolCode=tool.toolCode, argumentsJson=arguments))
        task_id: int | None = None
        try:
            task = await self.backend.create_task(
                TaskCreate(
                    userId=context.userId,
                    toolCode=tool.toolCode,
                    params=arguments,
                    clientRequestId=f"agent-run-{context.runId}-tool-call-{call.id}",
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


def _with_generation_argument_defaults(context: RunContext, tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(arguments)
    properties = tool.inputSchema.get("properties", {})
    if not isinstance(properties, dict):
        return normalized

    prompt_key = _infer_prompt_field(tool)
    if prompt_key and not normalized.get(prompt_key):
        prompt = _compose_generation_prompt(context, tool)
        if prompt:
            normalized[prompt_key] = prompt

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


def _compose_generation_prompt(context: RunContext, tool: ToolDescriptor) -> str:
    request = _compact(context.message, 600)
    if not request:
        return ""
    modality = infer_output_modality(tool)
    if modality == "image":
        return (
            f"{request}。高质量图片，主体清晰，构图自然，细节丰富，审美高级；"
            "如果用户只给出简短主体，请自动补足适合商业生成的场景、光线、镜头和风格。"
        )
    if modality == "video":
        return (
            f"{request}。高质量短视频画面，主体明确，运动自然，镜头连贯，节奏清晰；"
            "如果用户只给出简短主体，请自动补足场景、镜头运动和视觉风格。"
        )
    if modality == "audio":
        return f"{request}。语气自然，节奏清晰，适合直接生成音频。"
    if modality == "text":
        return request
    return request


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
    option_values = _option_values(options)
    if option_values:
        for preferred in ("1:1", "1024x1024", "standard", "normal", "medium", "auto", "default"):
            if preferred in option_values:
                return preferred
        return option_values[0]
    if any(token in lower for token in ("ratio", "aspect")):
        return "1:1"
    if lower in {"size", "image_size", "imageSize"} or "size" in lower:
        return "1024x1024"
    if any(token in lower for token in ("count", "num", "number")) and "negative" not in lower:
        return 1
    if "quality" in lower:
        return "standard"
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
