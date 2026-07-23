import pytest

from client.backend_client import BackendClient, RouteFailoverRequested, backend_route_context
from task_queue.task_handler_router import TaskHandlerRouter


def test_backend_requests_failover_only_for_safe_account_retry(monkeypatch) -> None:
    client = BackendClient()
    replacement = {
        "status": "PROCESSING",
        "executionHandler": "IMAGE_GENERATION",
        "modelConfig": {"vendorAccountId": 35},
    }
    calls: list[dict] = []

    def choose_route(task_id, payload, **_kwargs):
        calls.append({"taskId": task_id, **payload})
        return {"switched": True, "routeAttemptId": 2, "executionContext": replacement}

    monkeypatch.setattr(client, "request_route_failover", choose_route)

    with backend_route_context(1):
        with pytest.raises(RouteFailoverRequested) as raised:
            client.mark_failed(
                901,
                {
                    "errorCode": "MODEL_PROVIDER_UNAVAILABLE",
                    "errorMessage": "connect refused",
                    "deliveryState": "NOT_SENT",
                    "retryScope": "ACCOUNT",
                    "failureStage": "BEFORE_PROVIDER",
                },
            )

    assert raised.value.execution_context == replacement
    assert raised.value.route_attempt_id == 2
    assert calls[0]["deliveryState"] == "NOT_SENT"
    assert calls[0]["routeAttemptId"] == 1


def test_backend_does_not_request_failover_after_provider_acceptance(monkeypatch) -> None:
    client = BackendClient()
    monkeypatch.setattr(
        client,
        "request_route_failover",
        lambda *_args, **_kwargs: pytest.fail("accepted requests must not request another account"),
    )

    class Response:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"code": "SUCCESS", "data": {"status": "FAILED"}}

    monkeypatch.setattr(client, "_request", lambda *_args, **_kwargs: Response())

    result = client.mark_failed(
        902,
        {
            "errorCode": "MODEL_TIMEOUT",
            "errorMessage": "poll timed out",
            "deliveryState": "ACCEPTED",
            "retryScope": "NONE",
            "providerRequestId": "provider-902",
        },
    )

    assert result["status"] == "FAILED"


def test_backend_preserves_safe_retry_classification_on_terminal_failure(monkeypatch) -> None:
    client = BackendClient()
    monkeypatch.setattr(
        client,
        "request_route_failover",
        lambda *_args, **_kwargs: {"switched": False, "reason": "no_eligible_account"},
    )
    terminal_payloads: list[dict] = []

    class Response:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"code": "SUCCESS", "data": {"status": "FAILED"}}

    def capture_terminal_failure(_method, path, **kwargs):
        assert path == "/api/internal/v1/tasks/904/failed"
        terminal_payloads.append(kwargs["json_body"])
        return Response()

    monkeypatch.setattr(client, "_request", capture_terminal_failure)

    client.mark_failed(
        904,
        {
            "errorCode": "MODEL_PROVIDER_UNAVAILABLE",
            "errorMessage": "connect refused",
            "deliveryState": "NOT_SENT",
            "retryScope": "ACCOUNT",
            "retryAfterSeconds": 17,
        },
        trace_id="trace-904",
    )

    assert terminal_payloads == [
        {
            "errorCode": "MODEL_PROVIDER_UNAVAILABLE",
            "errorMessage": "connect refused",
            "developerMessage": "connect refused",
            "failureTraceId": "trace-904",
            "deliveryState": "NOT_SENT",
            "retryScope": "ACCOUNT",
            "retryAfterSeconds": 17,
            "failureStage": "BEFORE_PROVIDER",
        }
    ]


class RouterBackend:
    def __init__(self, context):
        self.context = context

    def claim_task(self, task_id, **_kwargs):
        return {"claimed": True, "taskId": task_id, "reason": "client_without_claim"}

    def get_execution_context(self, _task_id, trace_id=None):
        return {**self.context, "traceId": trace_id}

    def mark_processing(self, *_args, **_kwargs):
        return {}


class FailoverImageHandler:
    def __init__(self, replacement):
        self.replacement = replacement
        self.account_ids: list[int] = []

    def handle(self, message):
        account_id = int(message["__executionContext"]["modelConfig"]["vendorAccountId"])
        self.account_ids.append(account_id)
        if len(self.account_ids) == 1:
            raise RouteFailoverRequested(self.replacement, route_attempt_id=2)
        return {"status": "SUCCESS", "taskId": message["taskId"]}


def test_router_reuses_same_claim_and_dispatches_replacement_context() -> None:
    initial = {
        "status": "QUEUED",
        "executionHandler": "IMAGE_GENERATION",
        "toolCode": "gpt_image_text_to_image",
        "params": {},
        "modelConfig": {"vendorAccountId": 25},
    }
    replacement = {
        **initial,
        "status": "PROCESSING",
        "modelConfig": {"vendorAccountId": 35},
    }
    backend = RouterBackend(initial)
    image_handler = FailoverImageHandler(replacement)
    router = TaskHandlerRouter(backend_client=backend, image_generation_handler=image_handler)

    result = router.handle({"taskId": 903, "traceId": "route-failover-test"})

    assert result["status"] == "SUCCESS"
    assert image_handler.account_ids == [25, 35]
