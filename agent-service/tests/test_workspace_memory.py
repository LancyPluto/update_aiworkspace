import json

import httpx
import pytest

from app.clients.backend_client import BackendClient
from app.config import Settings
from app.core.schemas import WorkspaceMemoryItem


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
