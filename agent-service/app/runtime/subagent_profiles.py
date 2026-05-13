from pydantic import BaseModel, Field

from app.runtime.subagent_permissions import SubagentPermissionPolicy


class AgentSubagentProfile(BaseModel):
    name: str
    description: str
    systemPrompt: str
    tools: list[str] = Field(default_factory=list)
    permissions: list[str] = Field(default_factory=list)


def default_subagent_profiles() -> list[AgentSubagentProfile]:
    profiles = [
        AgentSubagentProfile(
            name="researcher",
            description="Research complex questions, compare sources, and return concise findings with caveats.",
            systemPrompt=(
                "You are a research subagent. Investigate the assigned question independently, "
                "separate facts from assumptions, and return concise findings plus open risks."
            ),
            permissions=["memory:read"],
        ),
        AgentSubagentProfile(
            name="file-analyst",
            description="Analyze workspace files, extracted text, and file-derived context for the main agent.",
            systemPrompt=(
                "You are a file analysis subagent. Focus on provided workspace file context, "
                "quote filenames or memory labels when relevant, and summarize only evidence-backed conclusions."
            ),
            permissions=["workspace_file:read"],
        ),
        AgentSubagentProfile(
            name="tool-operator",
            description="Plan and perform bounded tool operations that require careful argument selection.",
            systemPrompt=(
                "You are a tool operation subagent. Use available tools only for the assigned task, "
                "respect permission boundaries, and report actions, results, and failures succinctly."
            ),
            permissions=["tool:invoke"],
        ),
    ]
    return validate_profiles(profiles)


def validate_profile_permissions(profile: AgentSubagentProfile) -> AgentSubagentProfile:
    return SubagentPermissionPolicy.validate_profile_permissions(profile)


def validate_profiles(profiles: list[AgentSubagentProfile]) -> list[AgentSubagentProfile]:
    return SubagentPermissionPolicy.validate_profiles(profiles)


def profiles_to_deepagents_subagents(
    profiles: list[AgentSubagentProfile],
    *,
    model,
    tools: list,
) -> list[dict]:
    subagents: list[dict] = []
    for profile in profiles:
        subagent: dict = {
            "name": profile.name,
            "description": profile.description,
            "system_prompt": profile.systemPrompt,
            "model": model,
            "tools": tools,
        }
        subagents.append(subagent)
    return subagents
