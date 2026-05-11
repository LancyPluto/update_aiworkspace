import pytest

from app.runtime.subagent_permissions import SubagentPermissionPolicy
from app.runtime.subagent_profiles import AgentSubagentProfile


def _profile(name: str, permissions: list[str]) -> AgentSubagentProfile:
    return AgentSubagentProfile(
        name=name,
        description=f"{name} profile",
        systemPrompt=f"{name} prompt",
        permissions=permissions,
    )


def test_researcher_cannot_get_tool_invoke_permission():
    profile = _profile("researcher", ["memory:read", "tool:invoke"])

    with pytest.raises(ValueError, match="researcher"):
        SubagentPermissionPolicy.validate_profile_permissions(profile)


def test_file_analyst_is_read_only_for_workspace_files():
    profile = _profile("file-analyst", ["workspace_file:read", "workspace_file:write"])

    with pytest.raises(ValueError, match="workspace_file:write"):
        SubagentPermissionPolicy.validate_profile_permissions(profile)


def test_unknown_permission_fails_validation():
    profile = _profile("tool-operator", ["tool:invoke", "memory:delete"])

    with pytest.raises(ValueError, match="memory:delete"):
        SubagentPermissionPolicy.validate_profile_permissions(profile)
