from app.core.attachment_precheck import format_attachment_error, validate_attachment_arguments
from app.runtime.agent_router_service import _normalize_router_intent
from app.core.schemas import AgentFileContext, RunContext


def _context() -> RunContext:
    return RunContext(
        runId=1,
        sessionId=5,
        userId=1,
        message="test",
        agentFiles=[
            AgentFileContext(
                id=3,
                originalFilename="ref.png",
                status="READY",
                downloadUrl="/api/v1/agent/sessions/5/files/3/content",
            )
        ],
    )


def test_validate_attachment_arguments_flags_missing_file():
    errors = validate_attachment_arguments(
        _context(),
        {"reference_image_2": "/api/v1/agent/sessions/5/files/8/content"},
    )
    assert len(errors) == 1
    assert errors[0]["fileId"] == 8
    assert errors[0]["sessionId"] == 5


def test_validate_attachment_arguments_allows_known_file():
    errors = validate_attachment_arguments(
        _context(),
        {"image": "/api/v1/agent/sessions/5/files/3/content"},
    )
    assert errors == []


def test_validate_attachment_arguments_allows_url_attachment_with_synthetic_id():
    context = RunContext(
        runId=1,
        sessionId=14,
        userId=1,
        message="test",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-海报",
                status="READY",
                downloadUrl="http://127.0.0.1:8080/api/v1/agent/sessions/14/files/13/content",
            )
        ],
    )
    errors = validate_attachment_arguments(
        context,
        {"image": "http://127.0.0.1:8080/api/v1/agent/sessions/14/files/13/content"},
    )
    assert errors == []


def test_normalize_router_intent_maps_image_editing_to_tool_use():
    assert _normalize_router_intent("image_editing") == "tool_use"
    assert _normalize_router_intent("face_swap") == "tool_use"


def test_format_attachment_error_includes_available_ids():
    message = format_attachment_error(
        [{"sessionId": 5, "fileId": 8, "availableFileIds": [3]}],
    )
    assert "file#8" in message
    assert "3" in message
