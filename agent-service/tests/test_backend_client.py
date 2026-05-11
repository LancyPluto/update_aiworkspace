import httpx
import pytest

from app.clients.backend_client import BackendBusinessError, BackendClient, BackendClientError
from app.config import Settings
from app.core.schemas import RunComplete, RunEventCreate


@pytest.mark.asyncio
async def test_backend_client_parses_success_response_and_signs_request():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "runId": 7,
                    "sessionId": 3,
                    "userId": 2,
                    "userMessage": "hello",
                    "history": [],
                    "tools": [],
                    "creditBudget": 20,
                },
            },
        )

    settings = Settings(backend_internal_base_url="http://backend", internal_api_token="secret")
    client = BackendClient(settings, http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))

    context = await client.get_run_context(7)

    assert context.runId == 7
    assert requests[0].headers["X-Internal-Signature"]


@pytest.mark.asyncio
async def test_backend_client_raises_business_error():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": "AGENT_RUN_NOT_FOUND", "message": "missing"})

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendBusinessError):
        await client.get_run_context(7)


@pytest.mark.asyncio
async def test_backend_client_raises_http_error():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendClientError):
        await client.append_event(7, RunEventCreate(eventType="run.started"))


@pytest.mark.asyncio
async def test_backend_client_can_complete_run():
    seen: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.content)
        return httpx.Response(200, json={"code": "SUCCESS", "message": "ok", "data": {}})

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    await client.complete_run(7, RunComplete(finalAnswer="ok", intent="general_chat", consumedCredits=1))

    assert b'"finalAnswer":"ok"' in seen[0]


@pytest.mark.asyncio
async def test_backend_client_fetches_active_model_config():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/internal/v1/agent/model-config"
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "provider": "minimax",
                    "modelName": "MiniMax-M2.7",
                    "baseUrl": "https://api.minimax.io/v1",
                    "apiKey": "secret",
                    "minimaxGroupId": "group",
                    "timeoutSeconds": 45,
                    "enabled": True,
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    config = await client.get_active_model_config()

    assert config.provider == "minimax"
    assert config.modelName == "MiniMax-M2.7"
    assert config.apiKey == "secret"
