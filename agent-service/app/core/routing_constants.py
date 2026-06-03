"""Shared routing constants for infrastructure-level rule short-circuits."""

INFRASTRUCTURE_RULE_REASONS = frozenset({
    "ready_file_context_available",
    "file_analysis_request",
    "phase_unsupported_capability",
    "empty_request",
    "restored_from_pending_tool_context",
    "continuing_pending_tool_prompt",
    "structured_tool_arguments",
})


def is_infrastructure_rule_reason(reason: str) -> bool:
    return reason in INFRASTRUCTURE_RULE_REASONS
