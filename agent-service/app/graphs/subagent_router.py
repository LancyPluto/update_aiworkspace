from dataclasses import dataclass

from app.core.schemas import RunContext
from app.runtime.subagent_profiles import AgentSubagentProfile, default_subagent_profiles


@dataclass(frozen=True)
class SubagentRecommendation:
    profile: AgentSubagentProfile
    reason: str


class SubagentRouter:
    def __init__(self, profiles: list[AgentSubagentProfile] | None = None) -> None:
        self.profiles = profiles or default_subagent_profiles()

    def recommend(self, context: RunContext) -> SubagentRecommendation | None:
        message = context.message.lower()
        if any(term in message for term in ("research", "compare", "competitor", "market")):
            return self._recommend("researcher", "The request asks for independent research or comparison.")
        if context.agentFiles or any(term in message for term in ("file", "document", "analyze")):
            return self._recommend("file-analyst", "The request depends on workspace file analysis.")
        if any(term in message for term in ("tool", "run", "execute", "call")):
            return self._recommend("tool-operator", "The request may need bounded tool operation.")
        return None

    def _recommend(self, name: str, reason: str) -> SubagentRecommendation | None:
        profile = next((profile for profile in self.profiles if profile.name == name), None)
        if profile is None:
            return None
        return SubagentRecommendation(profile=profile, reason=reason)


def format_subagent_delegation_hint(recommendation: SubagentRecommendation | None) -> str:
    if recommendation is None:
        return ""
    profile = recommendation.profile
    return (
        "Subagent delegation hint: If this request grows into a delegated task, use the shared "
        f"`{profile.name}` profile. {profile.description}\nReason: {recommendation.reason}"
    )
