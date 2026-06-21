from __future__ import annotations

import re
from enum import Enum

from app.core.attachment_catalog import build_reference_plan
from app.core.schemas import RunContext
from app.tools.registry import ToolDescriptor, infer_output_modality

REFERENCE_EDIT_MAX_MESSAGE_LEN = 40
REFERENCE_EDIT_VERBS = (
    "调整",
    "修改",
    "放大",
    "缩小",
    "增强",
    "减弱",
    "去掉",
    "删除",
    "替换",
    "改成",
    "换成",
    "贴近",
    "更大",
    "更小",
    "更亮",
    "更暗",
    "修复",
    "优化",
    "edit",
    "adjust",
    "fix",
    "remove",
    "enhance",
)
REGENERATION_MARKERS = (
    "重新生成",
    "换主题",
    "新海报",
    "新图",
    "从零",
    "全新",
    "another poster",
    "new poster",
)
EXPLICIT_PROJECT_MEMORY_MARKERS = (
    "按项目",
    "按上次方案",
    "按上次",
    "沿用项目",
    "继续项目",
    "张继科",
    "海报项目",
    "那个项目",
    "上次方案",
)


class PromptMode(str, Enum):
    REFERENCE_EDIT_DELTA = "reference_edit_delta"
    STYLE_TRANSFER = "style_transfer"
    TEXT_TO_IMAGE = "text_to_image"
    FOLLOWUP_DELTA = "followup_delta"
    DEFAULT = "default"


def ready_image_count(context: RunContext) -> int:
    count = 0
    for file in context.agentFiles or []:
        if file.status == "READY" and (file.downloadUrl or "").strip():
            count += 1
    plan = build_reference_plan(context)
    if plan.ordered_urls:
        return max(count, len(plan.ordered_urls))
    return count


def looks_like_explicit_project_memory_request(message: str) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    return any(marker in compact for marker in EXPLICIT_PROJECT_MEMORY_MARKERS)


def resolve_prompt_mode(context: RunContext, tool: ToolDescriptor | None = None) -> PromptMode:
    message = (context.message or "").strip()
    if not message:
        return PromptMode.DEFAULT

    if context.recentToolCalls and any(
        token in re.sub(r"\s+", "", message)
        for token in ("按刚才", "按上次", "同样", "再来", "继续", "沿用")
    ):
        return PromptMode.FOLLOWUP_DELTA

    modality = infer_output_modality(tool) if tool is not None else None
    if modality is None and ready_image_count(context) > 0:
        modality = "image"
    if modality != "image":
        return PromptMode.DEFAULT

    images = ready_image_count(context)
    plan = build_reference_plan(context)
    if plan.has_explicit_references and len(plan.mentions or []) >= 2:
        return PromptMode.STYLE_TRANSFER
    if images <= 0:
        return PromptMode.TEXT_TO_IMAGE

    compact = re.sub(r"\s+", "", message)
    if any(marker in compact for marker in REGENERATION_MARKERS):
        return PromptMode.TEXT_TO_IMAGE
    if len(message) <= REFERENCE_EDIT_MAX_MESSAGE_LEN and any(
        verb in compact for verb in REFERENCE_EDIT_VERBS
    ):
        return PromptMode.REFERENCE_EDIT_DELTA
    return PromptMode.DEFAULT


def should_skip_tool_memory_injection(context: RunContext, tool: ToolDescriptor | None = None) -> bool:
    return resolve_prompt_mode(context, tool) == PromptMode.REFERENCE_EDIT_DELTA


def reference_edit_prompt(message: str) -> str:
    request = message.strip()
    if not request:
        return "对参考图执行用户请求的编辑调整，保持主体与场景一致，仅应用增量修改。"
    return f"对参考图执行：{request}。保持参考图中的主体身份、场景与构图，仅应用上述增量修改，不要引入新的 IP、标题或无关人物。"


def explicit_memory_ids_from_context(context: RunContext) -> list[int]:
    ids: list[int] = []
    for mention in context.referenceMentions or []:
        if (mention.kind or "").lower() != "memory":
            continue
        raw = (mention.assetKey or mention.token or mention.refLabel or "").strip()
        match = re.search(r"(\d+)", raw)
        if match:
            ids.append(int(match.group(1)))
    for match in re.finditer(r"#(\d+)", context.message or ""):
        ids.append(int(match.group(1)))
    seen: set[int] = set()
    ordered: list[int] = []
    for memory_id in ids:
        if memory_id <= 0 or memory_id in seen:
            continue
        seen.add(memory_id)
        ordered.append(memory_id)
    return ordered
