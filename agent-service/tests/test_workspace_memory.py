import json

import httpx
import pytest

from app.clients.backend_client import BackendClient
from app.config import Settings
from app.core.schemas import RunContext
from app.runtime.deep_agents_engine import _memory_view_for_context


@pytest.mark.asyncio
async def test_backend_client_retrieves_workspace_memory_and_signs_request():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "list": [
                        {
                            "id": 11,
                            "workspaceId": 7,
                            "sourceRunId": 1001,
                            "title": "Pricing policy",
                            "content": "Use tiered pricing.",
                            "memoryType": "fact",
                            "status": "ACTIVE",
                            "score": 0.87,
                            "updatedAt": "2026-05-10T12:00:00Z",
                        }
                    ]
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend", internal_api_token="secret"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    items = await client.retrieve_workspace_memory(workspace_id=7, query="pricing", limit=4)

    assert requests[0].method == "POST"
    assert requests[0].url.path == "/api/internal/v1/agent/workspaces/7/memory/retrieve"
    assert json.loads(requests[0].content) == {"query": "pricing", "limit": 4}
    assert requests[0].headers["X-Internal-Signature"]
    assert requests[0].headers["X-Internal-Timestamp"]
    assert requests[0].headers["X-Internal-Nonce"]

    assert len(items) == 1
    item = items[0]
    assert item.id == 11
    assert item.workspaceId == 7
    assert item.sourceRunId == 1001
    assert item.title == "Pricing policy"
    assert item.content == "Use tiered pricing."
    assert item.memoryType == "fact"
    assert item.status == "ACTIVE"
    assert item.score == 0.87
    assert item.updatedAt == "2026-05-10T12:00:00Z"


@pytest.mark.asyncio
async def test_backend_client_creates_memory_candidate_and_searches_session():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path.endswith("/memory/candidates"):
            return httpx.Response(200, json={"code": "SUCCESS", "message": "ok", "data": {"id": 21}})
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "list": [
                        {
                            "itemType": "tool_call",
                            "id": 41,
                            "runId": 9,
                            "toolCode": "kling_image",
                            "content": "generated image",
                            "score": 2,
                        }
                    ]
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend", internal_api_token="secret"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    await client.create_workspace_memory_candidate(
        workspace_id=7,
        user_id=3,
        action="candidate",
        memory_type="workflow_recipe",
        title="Image defaults",
        content="Use square ratio.",
        reason="stable workflow",
    )
    results = await client.search_session(user_id=3, session_id=5, query="last image", limit=3)

    assert requests[0].url.path == "/api/internal/v1/agent/workspaces/7/memory/candidates"
    assert json.loads(requests[0].content)["memoryType"] == "workflow_recipe"
    assert requests[1].url.path == "/api/internal/v1/agent/session-search"
    assert json.loads(requests[1].content) == {
        "userId": 3,
        "sessionId": 5,
        "query": "last image",
        "toolCode": None,
        "limit": 3,
    }
    assert results[0].itemType == "tool_call"
    assert results[0].toolCode == "kling_image"


@pytest.mark.asyncio
async def test_backend_client_creates_run_artifact_and_signs_request():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "id": 31,
                    "filename": "summary.md",
                    "contentType": "text/markdown",
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend", internal_api_token="secret"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    artifact = await client.create_run_artifact(
        run_id=15,
        filename="summary.md",
        content="# Summary",
        content_type="text/markdown",
    )

    assert requests[0].method == "POST"
    assert requests[0].url.path == "/api/internal/v1/agent/runs/15/artifacts"
    assert json.loads(requests[0].content) == {
        "filename": "summary.md",
        "content": "# Summary",
        "contentType": "text/markdown",
    }
    assert requests[0].headers["X-Internal-Signature"]
    assert artifact == {"id": 31, "filename": "summary.md", "contentType": "text/markdown"}


def test_memory_view_uses_chat_for_profile_questions_even_with_recent_tools():
    context = RunContext(
        runId=1,
        sessionId=2,
        userId=3,
        workspaceId=7,
        message="我是何人？",
        recentToolCalls=[{"id": 9, "toolCode": "kling_image"}],
    )

    assert _memory_view_for_context(context) == "chat"
