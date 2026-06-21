from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.config import settings
from app.core.schemas import RunContext
from app.routing.attachment_signals import attachment_signal_payload, build_attachment_signal
from app.routing.constants import ROUTER_PROMPT_VERSION
from app.routing.attachment_signals import AttachmentSignal


@dataclass(frozen=True, slots=True)
class CapabilityFlags:
    file_analysis: bool = False
    rag: bool = False
    workflow: bool = False


@dataclass(frozen=True, slots=True)
class RoutingContext:
    user_message: str
    attachment_signal: AttachmentSignal
    capability_flags: CapabilityFlags
    router_prompt_version: str


def default_capability_flags() -> CapabilityFlags:
    return CapabilityFlags(
        file_analysis=bool(getattr(settings, "agent_capability_file_analysis", False)),
        rag=bool(getattr(settings, "agent_capability_rag", False)),
        workflow=bool(getattr(settings, "agent_capability_workflow", False)),
    )


def build_routing_context(context: RunContext) -> RoutingContext:
    return RoutingContext(
        user_message=context.message.strip(),
        attachment_signal=build_attachment_signal(context),
        capability_flags=default_capability_flags(),
        router_prompt_version=ROUTER_PROMPT_VERSION,
    )


def capability_flags_payload(flags: CapabilityFlags) -> dict[str, bool]:
    return {
        "fileAnalysis": flags.file_analysis,
        "rag": flags.rag,
        "workflow": flags.workflow,
    }


def routing_context_payload(routing: RoutingContext) -> dict[str, Any]:
    return {
        "routerPromptVersion": routing.router_prompt_version,
        "attachmentSignals": attachment_signal_payload(routing.attachment_signal),
        "capabilityFlags": capability_flags_payload(routing.capability_flags),
    }
