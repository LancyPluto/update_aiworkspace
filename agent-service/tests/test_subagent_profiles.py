import pytest

from app.runtime.subagent_profiles import (
    AgentSubagentProfile,
    default_subagent_profiles,
    profiles_to_deepagents_subagents,
    validate_profile_permissions,
    validate_profiles,
)


def test_default_subagent_profiles_cover_core_workspace_roles():
    profiles = default_subagent_profiles()
    names = {profile.name for profile in profiles}

    assert {"researcher", "file-analyst", "tool-operator"}.issubset(names)
    assert all(profile.description.strip() for profile in profiles)
    assert all(profile.systemPrompt.strip() for profile in profiles)


def test_profiles_map_to_deepagents_subagent_specs_with_model_and_tools():
    subagents = profiles_to_deepagents_subagents(default_subagent_profiles(), model="mock-model", tools=[])

    researcher = next(subagent for subagent in subagents if subagent["name"] == "researcher")
    assert researcher["description"]
    assert researcher["system_prompt"]
    assert researcher["model"] == "mock-model"
    assert researcher["tools"] == []
    assert "permissions" not in researcher


def test_default_subagent_profiles_have_permission_boundaries():
    profiles = {profile.name: profile for profile in default_subagent_profiles()}

    assert profiles["researcher"].permissions == ["memory:read"]
    assert profiles["file-analyst"].permissions == ["workspace_file:read"]
    assert profiles["tool-operator"].permissions == ["tool:invoke"]


def test_platform_permissions_are_not_passed_as_deepagents_filesystem_permissions():
    subagents = profiles_to_deepagents_subagents(default_subagent_profiles(), model="mock-model", tools=[])

    assert all("permissions" not in subagent for subagent in subagents)


def test_default_subagent_profiles_pass_permission_validation():
    assert validate_profiles(default_subagent_profiles()) == default_subagent_profiles()


def test_profile_permission_validation_rejects_illegal_profile_combination():
    profile = AgentSubagentProfile(
        name="researcher",
        description="Research profile",
        systemPrompt="Research prompt",
        permissions=["memory:read", "tool:invoke"],
    )

    with pytest.raises(ValueError, match="tool:invoke"):
        validate_profile_permissions(profile)
