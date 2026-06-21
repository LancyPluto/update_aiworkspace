"""Infrastructure-level routing short-circuits — deterministic state only, no NLP."""

ROUTER_PROMPT_VERSION = "v2"

INFRASTRUCTURE_RULE_REASONS = frozenset({
    "empty_request",
    "restored_from_pending_tool_context",
    "security_rejected",
})


def is_infrastructure_rule_reason(reason: str) -> bool:
    base_reason = (reason or "").split(";", 1)[0].strip()
    return base_reason in INFRASTRUCTURE_RULE_REASONS
