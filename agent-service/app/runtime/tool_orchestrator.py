from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.core.budget_guard import BudgetState, BudgetGuard
from app.core.attachment_catalog import build_reference_plan, current_attachment_alias
from app.core.schemas import RunContext, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority
from app.runtime.session_state import latest_generated_image_state
from app.tools.backend_tool import BackendToolBridge, ToolExecutionError, enforce_locked_field_defaults, finalize_generation_arguments


class ToolOrchestrator:
    """Orchestrates local guard checks before delegating real execution to the backend bridge.

    Agent Service is not the credit source of truth. It only guards local model/tool-call counts;
    actual task credit validation, freezing, settlement, and worker execution must stay behind
    BackendToolBridge -> backend task APIs.
    """

    def __init__(self, tool_bridge: BackendToolBridge, budget_guard_provider: Callable[[], BudgetGuard]) -> None:
        self.tool_bridge = tool_bridge
        self._budget_guard_provider = budget_guard_provider
        self.reserve_callback = None

    async def execute_with_guard(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        budget: BudgetState,
        *,
        arguments: dict[str, Any] | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        prepared = await self._prepare_execution_arguments(context, tool, arguments=arguments)
        _raise_image_prompt_schema_validation_if_needed(context, tool, prepared)
        missing = missing_execution_arguments(prepared, tool)
        if missing:
            return {"missing_tool_arguments": missing}

        if self.reserve_callback is not None:
            await self.reserve_callback(budget, idempotency_key)
        else:
            self._budget_guard_provider().reserve_tool_call(budget, 0)
        if idempotency_key is None:
            return await self.tool_bridge.execute_with_args(context, tool, prepared)
        return await self.tool_bridge.execute_with_args(context, tool, prepared, idempotency_key=idempotency_key)

    async def _prepare_execution_arguments(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        *,
        arguments: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if arguments:
            prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
            prepared.update({key: value for key, value in arguments.items() if value not in (None, "")})
            prepared = enforce_locked_field_defaults(tool, prepared, user_message=context.message)
            prepared = apply_user_selected_attachment_priority(context, tool, prepared)
            return finalize_generation_arguments(context, tool, prepared)

        base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
        enriched = await self.tool_bridge.enrich_arguments(
            self.tool_bridge.conversation_argument_text(context),
            tool,
            existing_args=base_args,
            context=context,
        )
        prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        prepared.update({key: value for key, value in enriched.items() if value not in (None, "")})
        prepared = enforce_locked_field_defaults(tool, prepared, user_message=context.message)
        prepared = apply_user_selected_attachment_priority(context, tool, prepared)
        return finalize_generation_arguments(context, tool, prepared)


def missing_execution_arguments(arguments: dict[str, Any], tool: ToolDescriptor) -> list[str]:
    if _is_v2_lite_image_schema(tool):
        return []
    if tool.fields:
        return [
            field.fieldKey
            for field in tool.fields
            if bool(field.executionRequired if field.executionRequired is not None else field.required)
            and (field.fieldKey not in arguments or arguments[field.fieldKey] in (None, ""))
        ]
    required = tool.inputSchema.get("required", [])
    if not isinstance(required, list):
        return []
    return [
        name
        for name in required
        if isinstance(name, str) and (name not in arguments or arguments[name] in (None, ""))
    ]


def _raise_image_prompt_schema_validation_if_needed(
    context: RunContext,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
) -> None:
    if not _is_v2_lite_image_schema(tool):
        return
    operation = str(arguments.get("operation") or "generate").strip().lower()
    missing: list[str] = []
    if operation in {"edit", "variation"}:
        for field in ("base_prompt", "modification_prompt"):
            if not _present(arguments.get(field)):
                missing.append(field)
    elif operation in {"generate", "composite"}:
        if not _present(arguments.get("generation_prompt")):
            missing.append("generation_prompt")
    elif not _present(arguments.get("generation_prompt")) and not _present(arguments.get("modification_prompt")):
        missing.append("generation_prompt")
    if not missing:
        _raise_image_reference_routing_validation_if_needed(context, operation, arguments)
        return

    latest = latest_generated_image_state(context.recentToolCalls)
    preview = _preview(latest.prompt if latest is not None else "")
    message = _schema_validation_message(
        field_name=missing[0],
        operation=operation or "generate",
        latest_prompt_preview=preview,
    )
    error = ToolExecutionError(message, error_code="SCHEMA_VALIDATION")
    error.details = {
        "missingFields": missing,
        "operation": operation or "generate",
        "promptCompletionHint": (
            "SYSTEM ACTION REQUIRED: DO NOT ask the user for clarification. DO NOT explain this error to the user. "
            "You already have the context. Read <SessionState>, synthesize complete image prompt fields yourself, "
            "and call the tool again in your next response. The backend will not infer or compose image prompts."
        ),
        "latestGeneratedImagePromptPreview": preview,
    }
    raise error


def _raise_image_reference_routing_validation_if_needed(
    context: RunContext,
    operation: str,
    arguments: dict[str, Any],
) -> None:
    if operation not in {"generate", "composite", "edit", "variation"}:
        return
    plan = build_reference_plan(context)
    if len(plan.mentions) < 2:
        return
    references = arguments.get("references")
    if _has_structured_multi_reference_routing(references, min_count=min(2, len(plan.mentions))):
        return

    latest = latest_generated_image_state(context.recentToolCalls)
    preview = _preview(latest.prompt if latest is not None else "")
    aliases = ", ".join(current_attachment_alias(index) for index in range(1, len(plan.mentions) + 1))
    message = _schema_validation_message(
        field_name="references",
        operation=operation or "generate",
        latest_prompt_preview=preview,
        routing_aliases=aliases,
    )
    error = ToolExecutionError(message, error_code="SCHEMA_VALIDATION")
    error.details = {
        "missingFields": ["references"],
        "operation": operation or "generate",
        "promptCompletionHint": (
            "SYSTEM ACTION REQUIRED: DO NOT ask the user for clarification. DO NOT explain this error to the user. "
            "You already have multiple current-turn image references. Fill references[] with one entry per relevant "
            "image, use source_ref aliases such as [当前参考图_1], assign explicit roles like face_ref, identity_ref, "
            "pose_ref, composition_ref, or style_ref, and call the tool again."
        ),
        "latestGeneratedImagePromptPreview": preview,
        "visibleReferenceAliases": aliases,
    }
    raise error


def _is_v2_lite_image_schema(tool: ToolDescriptor) -> bool:
    schema = tool.inputSchema if isinstance(tool.inputSchema, dict) else {}
    properties = schema.get("properties")
    return isinstance(properties, dict) and {"operation", "references", "base_image_ref"}.issubset(properties.keys())


def _present(value: Any) -> bool:
    return not (value is None or (isinstance(value, str) and not value.strip()))


def _preview(value: str, limit: int = 700) -> str:
    text = " ".join(str(value or "").split())
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 16)] + "...[truncated]"


def _has_structured_multi_reference_routing(references: Any, *, min_count: int) -> bool:
    if not isinstance(references, list):
        return False
    usable: list[tuple[str, str]] = []
    for item in references:
        if not isinstance(item, dict):
            continue
        source_ref = str(item.get("source_ref") or "").strip()
        role = str(item.get("role") or "").strip()
        if not source_ref or not role or role == "supplemental_ref":
            continue
        usable.append((source_ref, role))
    if len(usable) < min_count:
        return False
    if len({source for source, _ in usable}) < min_count:
        return False
    return len({role for _, role in usable}) >= 2


def _schema_validation_message(
    *,
    field_name: str,
    operation: str,
    latest_prompt_preview: str,
    routing_aliases: str = "",
) -> str:
    if field_name == "references":
        alias_line = f"Current attachment aliases: `{routing_aliases}`\n" if routing_aliases else ""
        return (
            "SchemaValidationError: Missing structured multi-reference routing.\n\n"
            "You provided multiple current-turn image references, but did not assign explicit roles in `references[]`.\n\n"
            "The backend will not guess which image controls identity, style, pose, or composition.\n\n"
            "Required correction:\n"
            "- Read the user request and <SessionState>.\n"
            "- Fill `references[]` with one object per relevant image.\n"
            "- Use current attachment aliases such as [当前参考图_1] in references[].source_ref.\n"
            "- Assign concrete roles such as face_ref, identity_ref, style_ref, pose_ref, composition_ref, or controlnet_pose_ref.\n"
            "- Add notes that explicitly say what each reference should and should not control.\n"
            "- Ensure generation_prompt also reflects this routing and does not collapse all images into one generic scene.\n\n"
            f"Missing field: `{field_name}`\n"
            f"Operation: `{operation}`\n"
            f"{alias_line}"
            f"Available session image prompt: `{latest_prompt_preview}`\n\n"
            "[SYSTEM ACTION REQUIRED]: DO NOT ask the user for clarification. DO NOT explain this error to the user. "
            "You already have the context. You MUST immediately construct structured references[] and call the tool again."
        )
    return (
        "SchemaValidationError: Missing or empty required image prompt field.\n\n"
        f"You left `{field_name}` empty or missing.\n\n"
        "The backend will not infer or compose image prompts for you.\n\n"
        "Required correction:\n"
        "- Read <SessionState>.\n"
        "- For generate/composite/variation, write a full standalone `generation_prompt`.\n"
        "- For edit/variation, copy the selected image prompt into `base_prompt` and write only the new visual change into `modification_prompt`.\n"
        "- Use current attachment aliases such as [当前参考图_1] in references[].source_ref.\n"
        "- Do not retry with an empty prompt, placeholder, or vague continuation phrase.\n\n"
        f"Missing field: `{field_name}`\n"
        f"Operation: `{operation}`\n"
        f"Available session image prompt: `{latest_prompt_preview}`\n\n"
        "[SYSTEM ACTION REQUIRED]: DO NOT ask the user for clarification. DO NOT explain this error to the user. "
        "You already have the context. You MUST immediately read <SessionState>, synthesize the complete visual prompt "
        "yourself, and call the tool again in your next response."
    )
