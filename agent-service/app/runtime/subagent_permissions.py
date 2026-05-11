from collections.abc import Iterable
from typing import Protocol


MEMORY_READ = "memory:read"
WORKSPACE_FILE_READ = "workspace_file:read"
WORKSPACE_FILE_WRITE = "workspace_file:write"
TOOL_INVOKE = "tool:invoke"


class SubagentProfileLike(Protocol):
    name: str
    permissions: list[str]


class SubagentPermissionPolicy:
    ALLOWED_PERMISSIONS = frozenset(
        {
            MEMORY_READ,
            WORKSPACE_FILE_READ,
            WORKSPACE_FILE_WRITE,
            TOOL_INVOKE,
        }
    )
    DEFAULT_PROFILE_PERMISSIONS = {
        "researcher": frozenset({MEMORY_READ}),
        "file-analyst": frozenset({WORKSPACE_FILE_READ}),
        "tool-operator": frozenset({TOOL_INVOKE}),
    }

    @classmethod
    def validate_profile_permissions(cls, profile: SubagentProfileLike) -> SubagentProfileLike:
        permissions = set(profile.permissions)
        unknown_permissions = permissions - cls.ALLOWED_PERMISSIONS
        if unknown_permissions:
            raise ValueError(
                f"Subagent profile '{profile.name}' has unknown permissions: "
                f"{', '.join(sorted(unknown_permissions))}"
            )

        allowed_permissions = cls.DEFAULT_PROFILE_PERMISSIONS.get(profile.name)
        if allowed_permissions is None:
            raise ValueError(f"Unknown subagent profile '{profile.name}'")

        disallowed_permissions = permissions - allowed_permissions
        if disallowed_permissions:
            raise ValueError(
                f"Subagent profile '{profile.name}' cannot hold permissions: "
                f"{', '.join(sorted(disallowed_permissions))}"
            )

        return profile

    @classmethod
    def validate_profiles(cls, profiles: Iterable[SubagentProfileLike]) -> list[SubagentProfileLike]:
        return [cls.validate_profile_permissions(profile) for profile in profiles]
