import httpx
import pytest

from app.clients.backend_client import BackendBusinessError, BackendClient, BackendClientError
from app.config import Settings
from app.core.schemas import RunComplete, RunEventCreate, TaskCreate
from app.observability.trace import reset_trace_id, set_trace_id


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
async def test_backend_client_raises_business_error_with_credit_payload_on_http_400():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={
                "code": "CREDIT_NOT_ENOUGH",
                "message": "可用算力不足：当前 5，调用「image_generation」至少需要 10",
                "data": {"availableCredits": 5, "requiredCredits": 10, "toolCode": "image_generation"},
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendBusinessError) as exc_info:
        await client.create_task(
            TaskCreate(userId=2, toolCode="image_generation", params={}, clientRequestId="agent-run-1")
        )

    error = exc_info.value
    assert error.error_code == "CREDIT_NOT_ENOUGH"
    assert error.data["availableCredits"] == 5
    assert error.data["requiredCredits"] == 10


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
async def test_backend_client_accepts_v2_admin_error_contract():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            504,
            json={
                "errorCode": "MODEL_004",
                "developerMessage": "provider timed out after retry",
                "traceId": "trace-v2",
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendBusinessError) as exc_info:
        await client.get_run_context(7)

    assert exc_info.value.error_code == "MODEL_004"
    assert exc_info.value.trace_id == "trace-v2"
    assert exc_info.value.status_code == 504
    assert str(exc_info.value) == "provider timed out after retry"


@pytest.mark.asyncio
async def test_backend_client_does_not_echo_non_json_response_body():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="sql password=secret", headers={"X-Request-Id": "trace-safe"})

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendClientError) as exc_info:
        await client.get_run_context(7)

    assert "sql password=secret" not in str(exc_info.value)
    assert "trace-safe" in str(exc_info.value)
    assert exc_info.value.error_code == "SYSTEM_001"
    assert exc_info.value.trace_id == "trace-safe"
    assert exc_info.value.status_code == 500


@pytest.mark.asyncio
async def test_backend_client_rejects_success_code_on_failed_http_response():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            500,
            json={"code": "SUCCESS", "message": "ok", "traceId": "trace-invalid-success"},
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(BackendBusinessError) as exc_info:
        await client.get_run_context(7)

    assert exc_info.value.error_code == "SYSTEM_001"
    assert exc_info.value.trace_id == "trace-invalid-success"
    assert exc_info.value.status_code == 500
    assert "SUCCESS error code with HTTP status 500" in str(exc_info.value)


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


@pytest.mark.asyncio
async def test_backend_client_can_create_and_read_internal_task():
    paths: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        if request.url.path == "/api/internal/v1/tasks" and request.method == "POST":
            return httpx.Response(
                200,
                json={"code": "SUCCESS", "message": "ok", "data": {"taskId": 11, "taskNo": "T11", "status": "QUEUED"}},
            )
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "taskId": 11,
                    "taskNo": "T11",
                    "userId": 2,
                    "toolCode": "xiaohongshu_copywriting",
                    "status": "SUCCESS",
                    "result": {"resourceType": "MARKDOWN", "contentText": "# Done"},
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    created = await client.create_task(
        TaskCreate(userId=2, toolCode="xiaohongshu_copywriting", params={"productName": "test"}, clientRequestId="agent-run-1")
    )
    detail = await client.get_task_detail(2, created.taskId)
    await client.cancel_task(2, created.taskId)

    assert paths == ["/api/internal/v1/tasks", "/api/internal/v1/tasks/11", "/api/internal/v1/tasks/11/cancel"]
    assert detail.result.contentText == "# Done"


@pytest.mark.asyncio
async def test_backend_client_binds_tool_call_task():
    seen: list[tuple[str, bytes]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.content))
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {"id": 99, "runId": 7, "toolCode": "image_generation", "taskId": 123, "status": "RUNNING"},
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    bound = await client.bind_tool_call_task(99, 123)

    assert bound.taskId == 123
    assert seen == [("/api/internal/v1/agent/tool-calls/99/task", b'{"taskId":123}')]


@pytest.mark.asyncio
async def test_backend_client_delegates_workflow_using_only_persisted_tool_call_identity():
    seen: list[tuple[str, str, bytes]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, request.content))
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "taskId": 501,
                    "runId": 601,
                    "status": "CANCELLED",
                    "runUrl": "/agents/runs/501",
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    delegated = await client.delegate_workflow_tool_call(99)

    assert delegated.taskId == 501
    assert delegated.status == "CANCELLED"
    assert delegated.runUrl == "/agents/runs/501"
    assert seen == [("POST", "/api/internal/v1/agent/tool-calls/99/delegate-workflow", b"")]


@pytest.mark.asyncio
async def test_backend_client_retries_workflow_delegation_after_response_loss():
    seen: list[tuple[str, bytes]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.content))
        if len(seen) == 1:
            raise httpx.ReadTimeout("response lost after backend commit", request=request)
        return httpx.Response(
            200,
            json={
                "code": "SUCCESS",
                "message": "ok",
                "data": {
                    "taskId": 501,
                    "runId": 601,
                    "status": "RUNNING",
                    "runUrl": "/agents/runs/501",
                },
            },
        )

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    delegated = await client.delegate_workflow_tool_call(99)

    assert delegated.taskId == 501
    assert seen == [
        ("/api/internal/v1/agent/tool-calls/99/delegate-workflow", b""),
        ("/api/internal/v1/agent/tool-calls/99/delegate-workflow", b""),
    ]


@pytest.mark.asyncio
async def test_backend_client_retries_workflow_delegation_after_response_validation_failure():
    responses = [
        {"code": "SUCCESS", "message": "ok", "data": {"status": "RUNNING"}},
        {
            "code": "SUCCESS",
            "message": "ok",
            "data": {
                "taskId": 502,
                "runId": 602,
                "status": "RUNNING",
                "runUrl": "/agents/runs/502",
            },
        },
    ]
    seen: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.content)
        return httpx.Response(200, json=responses[len(seen) - 1])

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    delegated = await client.delegate_workflow_tool_call(99)

    assert delegated.taskId == 502
    assert seen == [b"", b""]


@pytest.mark.asyncio
async def test_backend_client_forwards_trace_id_header():
    seen_headers: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_headers.append(request.headers.get("X-Request-Id"))
        return httpx.Response(200, json={"code": "SUCCESS", "message": "ok", "data": {}})

    client = BackendClient(
        Settings(backend_internal_base_url="http://backend"),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    token = set_trace_id("agent-trace-123")
    try:
        await client.append_event(7, RunEventCreate(eventType="run.started"))
    finally:
        reset_trace_id(token)

    assert seen_headers == ["agent-trace-123"]
