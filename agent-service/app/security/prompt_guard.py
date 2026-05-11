from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class PromptGuardResult:
    rejected: bool
    error_code: str | None = None
    message: str | None = None


class PromptGuard:
    unsafe_patterns = (
        "ignore previous instructions",
        "ignore all previous instructions",
        "system prompt",
        "developer message",
        "api key",
        "secret",
        "internal token",
        "jwt",
        "hidden tool",
        "admin tool",
        "modify balance",
        "change credits",
    )

    refusal = (
        "I cannot help with requests to reveal system prompts, secrets, internal tokens, "
        "hidden tools, or admin-only actions. I can still help with normal tool recommendations "
        "or content generation."
    )

    def inspect(self, message: str) -> PromptGuardResult:
        normalized = (message or "").lower()
        if any(pattern in normalized for pattern in self.unsafe_patterns):
            return PromptGuardResult(True, "AGENT_SECURITY_REJECTED", self.refusal)
        return PromptGuardResult(False)
