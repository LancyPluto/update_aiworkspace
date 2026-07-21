"""Worker-side HTTP contract tests.

The Backend server in this module is a contract double. The matching real Spring
controller/service/database decisions are covered by ModelRouteFailoverApiIntegrationTest.
"""

import json
import socket
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from client.backend_client import BackendClient
from client.model_client import ModelClient
from handlers.text_task_handler import TextTaskHandler
from task_queue.task_handler_router import TaskHandlerRouter


class BackendContractState:
    def __init__(self, initial_context: dict, replacement_context: dict | None = None):
        self.initial_context = initial_context
        self.replacement_context = replacement_context
        self.route_calls: list[dict] = []
        self.success_calls: list[dict] = []
        self.failed_calls: list[dict] = []


def test_connection_refused_switches_to_second_account_and_succeeds(monkeypatch):
    monkeypatch.setattr("handlers.text_task_handler.settings.text_tool_streaming_enabled", False)
    refused_base_url = _unused_base_url()

    with _provider_server() as (provider_base_url, provider_calls):
        initial = _execution_context(1, refused_base_url, account_id=25)
        replacement = _execution_context(2, provider_base_url, account_id=35)
        state = BackendContractState(initial, replacement)

        with _backend_server(state) as backend_base_url:
            result = _router(backend_base_url).handle({"taskId": 901, "traceId": "integration-failover"})

    assert result["status"] == "SUCCESS"
    assert len(state.route_calls) == 1
    assert state.route_calls[0]["deliveryState"] == "NOT_SENT"
    assert state.route_calls[0]["retryScope"] == "ACCOUNT"
    assert state.route_calls[0]["routeAttemptId"] == 1
    assert len(provider_calls) == 1
    assert len(state.success_calls) == 1
    assert state.success_calls[0]["providerCalled"] is True
    assert state.failed_calls == []


def test_read_timeout_never_requests_account_failover(monkeypatch):
    monkeypatch.setattr("handlers.text_task_handler.settings.text_tool_streaming_enabled", False)

    with _provider_server(response_delay=1.25) as (provider_base_url, _provider_calls):
        state = BackendContractState(_execution_context(1, provider_base_url, account_id=25, timeout_seconds=1))
        with _backend_server(state) as backend_base_url:
            result = _router(backend_base_url).handle({"taskId": 902, "traceId": "integration-read-timeout"})

    assert result["status"] == "FAILED"
    assert state.route_calls == []
    assert len(state.failed_calls) == 1
    assert state.failed_calls[0]["deliveryState"] == "UNKNOWN"
    assert state.failed_calls[0]["retryScope"] == "NONE"


def test_checkpoint_makes_backend_reject_an_otherwise_safe_failover(monkeypatch):
    monkeypatch.setattr("handlers.text_task_handler.settings.text_tool_streaming_enabled", False)
    initial = _execution_context(1, _unused_base_url(), account_id=25)
    initial["providerCheckpoint"] = {
        "kind": "VIDEO_SUBMISSION",
        "status": "SUBMITTED",
        "requestId": "provider-accepted-903",
    }
    state = BackendContractState(initial, _execution_context(2, "http://unused.invalid/v1", account_id=35))

    with _backend_server(state) as backend_base_url:
        result = _router(backend_base_url).handle({"taskId": 903, "traceId": "integration-checkpoint"})

    assert result["status"] == "FAILED"
    assert len(state.route_calls) == 1
    assert state.route_calls[0]["deliveryState"] == "NOT_SENT"
    assert state.success_calls == []
    assert len(state.failed_calls) == 1


def _router(backend_base_url: str) -> TaskHandlerRouter:
    backend = BackendClient()
    backend.base_url = backend_base_url
    return TaskHandlerRouter(
        backend_client=backend,
        text_handler=TextTaskHandler(backend_client=backend, model_client=ModelClient()),
    )


def _execution_context(
    route_attempt_id: int,
    provider_base_url: str,
    *,
    account_id: int,
    timeout_seconds: int = 3,
) -> dict:
    return {
        "taskId": 900 + route_attempt_id,
        "status": "PROCESSING",
        "routeAttemptId": route_attempt_id,
        "executionHandler": "TEXT_GENERATION",
        "toolType": "TEXT_GENERATION",
        "toolCode": "route_integration_text",
        "outputModality": "TEXT",
        "params": {"prompt": "return a short integration response"},
        "modelProviderCode": "openai_compatible",
        "modelName": "fake-chat-model",
        "modelConfig": {
            "vendorAccountId": account_id,
            "provider": "openai_compatible",
            "modelName": "fake-chat-model",
            "baseUrl": provider_base_url,
            "apiKey": "fake-key",
            "timeoutSeconds": timeout_seconds,
        },
        "fields": [],
    }


@contextmanager
def _backend_server(state: BackendContractState):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.endswith("/execution-context"):
                self._json(200, {"code": "SUCCESS", "data": state.initial_context})
                return
            self._json(404, {"code": "NOT_FOUND"})

        def do_POST(self):
            payload = self._payload()
            if self.path.endswith("/claim"):
                self._json(200, {
                    "code": "SUCCESS",
                    "data": {
                        "claimed": True,
                        "status": "PROCESSING",
                        "claimToken": payload.get("claimToken"),
                        "reason": "claimed",
                    },
                })
                return
            if self.path.endswith("/processing") or self.path.endswith("/lease/renew"):
                self._json(200, {"code": "SUCCESS", "data": {"status": "PROCESSING", "claimed": True}})
                return
            if self.path.endswith("/route-failover"):
                state.route_calls.append(payload)
                has_checkpoint = bool(state.initial_context.get("providerCheckpoint"))
                if state.replacement_context is not None and not has_checkpoint:
                    self._json(200, {
                        "code": "SUCCESS",
                        "data": {
                            "switched": True,
                            "reason": "switched",
                            "routeAttemptId": state.replacement_context["routeAttemptId"],
                            "executionContext": state.replacement_context,
                        },
                    })
                else:
                    self._json(200, {
                        "code": "SUCCESS",
                        "data": {"switched": False, "reason": "unsafe_checkpoint"},
                    })
                return
            if self.path.endswith("/success"):
                state.success_calls.append(payload)
                self._json(200, {"code": "SUCCESS", "data": {"status": "SUCCESS"}})
                return
            if self.path.endswith("/failed"):
                state.failed_calls.append(payload)
                self._json(200, {"code": "SUCCESS", "data": {"status": "FAILED"}})
                return
            self._json(404, {"code": "NOT_FOUND"})

        def _payload(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(length) or b"{}")

        def _json(self, status: int, payload: dict):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format, *_args):
            return

    with _http_server(Handler) as base_url:
        yield base_url


@contextmanager
def _provider_server(response_delay: float = 0):
    calls: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            calls.append(json.loads(self.rfile.read(length) or b"{}"))
            if response_delay:
                time.sleep(response_delay)
            body = json.dumps({
                "choices": [{"message": {"content": "fake upstream success"}}],
                "usage": {"prompt_tokens": 8, "completion_tokens": 4},
            }).encode("utf-8")
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, _format, *_args):
            return

    with _http_server(Handler) as base_url:
        yield f"{base_url}/v1", calls


@contextmanager
def _http_server(handler):
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def _unused_base_url() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return f"http://127.0.0.1:{port}/v1"
