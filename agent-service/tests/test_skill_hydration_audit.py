import pytest

from app.core.schemas import AgentSkillDescriptor, RunContext
from app.runtime.skill_hydration import SkillHydrationService


class FakeBackend:
    def __init__(self, payload=None, error=None):
        self.payload = payload or {}
        self.error = error
        self.events = []

    async def get_agent_skill(self, skill_code):
        if self.error:
            raise self.error
        return self.payload

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))


def context():
    return RunContext(
        runId=12,
        sessionId=3,
        userId=4,
        message="edit image",
        availableSkills=[AgentSkillDescriptor(skillCode="image_edit", displayName="Image edit", toolCodes=["gpt_image2"], version=2)],
    )


@pytest.mark.asyncio
async def test_skill_hydration_emits_match_version_and_content_hash():
    backend = FakeBackend({"sopRules": "Keep the subject identity.", "version": 3})

    result = await SkillHydrationService(backend).hydrate_for_tool(context(), "gpt_image2", set())

    assert result is not None
    payload = backend.events[0][1].eventJson
    assert payload["hydrated"] is True
    assert payload["matchType"] == "EXACT"
    assert payload["matchedPattern"] == "gpt_image2"
    assert payload["version"] == 3
    assert len(payload["contentSha256"]) == 64


@pytest.mark.asyncio
async def test_skill_fetch_failure_emits_failure_evidence_and_preserves_error():
    backend = FakeBackend(error=RuntimeError("backend down"))

    with pytest.raises(RuntimeError, match="backend down"):
        await SkillHydrationService(backend).hydrate_for_tool(context(), "gpt_image2", set())

    payload = backend.events[0][1].eventJson
    assert payload["hydrated"] is False
    assert payload["failureReason"] == "skill fetch failed: RuntimeError"
