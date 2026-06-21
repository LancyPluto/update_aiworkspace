from app.core.schemas import AgentFileContext, ReferenceMention, RunContext
from app.runtime.prompt_policy import explicit_memory_ids_from_context, looks_like_explicit_project_memory_request


def test_explicit_memory_ids_from_message_hash():
    context = RunContext(
        runId=1,
        sessionId=10,
        userId=2,
        message="按 #16 项目继续做",
    )
    assert explicit_memory_ids_from_context(context) == [16]


def test_explicit_memory_ids_from_reference_mentions():
    context = RunContext(
        runId=2,
        sessionId=11,
        userId=2,
        message="沿用这条记忆",
        referenceMentions=[
            ReferenceMention(kind="memory", assetKey="42", url="", refLabel="张继科海报"),
        ],
    )
    assert explicit_memory_ids_from_context(context) == [42]


def test_explicit_project_request_detects_poster_phrase():
    assert looks_like_explicit_project_memory_request("按张继科海报项目再做一张") is True
    assert looks_like_explicit_project_memory_request("调整图片，人物占比更大") is False
