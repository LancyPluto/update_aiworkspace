import logging
import hashlib
import hmac
import json
import time
import uuid
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any
from urllib.parse import urlencode, urlsplit

import requests

from config import settings
from observability.metrics import record_backend_request, record_lease_renew, record_status_update


LOGGER = logging.getLogger(__name__)
_CURRENT_CLAIM_TOKEN: ContextVar[str | None] = ContextVar("worker_claim_token", default=None)
_CURRENT_ROUTE_ATTEMPT_ID: ContextVar[int | None] = ContextVar("worker_route_attempt_id", default=None)


class BackendClientError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        error_code: str | None = None,
        trace_id: str | None = None,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.trace_id = trace_id
        self.status_code = status_code


class RouteFailoverRequested(BackendClientError):
    def __init__(self, execution_context: dict[str, Any], route_attempt_id: int | None = None) -> None:
        super().__init__("backend selected a replacement provider account")
        self.execution_context = execution_context
        self.route_attempt_id = route_attempt_id


class BackendClient:
    def __init__(self) -> None:
        self.base_url = settings.backend_internal_base_url.rstrip("/")
        self.timeout = (3, 15)
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
            }
        )

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "GET",
            f"/api/internal/v1/tasks/{task_id}/execution-context",
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def claim_task(self, task_id: int, *, worker_id: str, claim_token: str, trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/claim",
            json_body={"workerId": worker_id, "claimToken": claim_token},
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def renew_lease(self, task_id: int, *, worker_id: str, claim_token: str, trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/lease/renew",
            json_body={"workerId": worker_id, "claimToken": claim_token},
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def get_agent_model_config(self) -> dict[str, Any]:
        response = self._request(
            "GET",
            "/api/internal/v1/agent/model-config",
            timeout=self.timeout,
        )
        return self._parse_response(response)

    def mark_processing(
        self,
        task_id: int,
        *,
        progress: int | None = None,
        progress_message: str | None = None,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if progress is not None:
            payload["progress"] = progress
        if progress_message:
            payload["progressMessage"] = progress_message
        self._attach_claim_token(payload, claim_token)
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/processing",
            json_body=payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def save_provider_checkpoint(
        self,
        task_id: int,
        checkpoint: dict[str, Any],
        *,
        expected_version: int,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        payload = {
            "checkpoint": checkpoint,
            "expectedVersion": max(0, int(expected_version)),
        }
        self._attach_claim_token(payload, claim_token)
        path = f"/api/internal/v1/tasks/{task_id}/provider-checkpoint"
        last_error: requests.RequestException | None = None
        for attempt in range(3):
            try:
                response = self._request(
                    "POST",
                    path,
                    json_body=payload,
                    timeout=self.timeout,
                    trace_id=trace_id,
                )
                if response.status_code >= 500 and attempt < 2:
                    time.sleep(0.2 * (attempt + 1))
                    continue
                return self._parse_response(response)
            except requests.RequestException as error:
                last_error = error
                if attempt >= 2:
                    raise
                time.sleep(0.2 * (attempt + 1))
        if last_error is not None:
            raise last_error
        raise BackendClientError("provider checkpoint retry exhausted")

    def register_provider_callback(
        self,
        task_id: int,
        provider_code: str,
        *,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        payload = {"providerCode": provider_code}
        self._attach_claim_token(payload, claim_token)
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/provider-callback-registration",
            json_body=payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def get_provider_callback(
        self,
        task_id: int,
        provider_code: str,
        *,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        token = claim_token or _CURRENT_CLAIM_TOKEN.get()
        query = urlencode({"providerCode": provider_code, "claimToken": token or ""})
        response = self._request(
            "GET",
            f"/api/internal/v1/tasks/{task_id}/provider-callback?{query}",
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def mark_success(
        self,
        task_id: int,
        payload: dict[str, Any],
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        payload = dict(payload or {})
        self._attach_claim_token(payload, claim_token)
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/success",
            json_body=payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def mark_failed(
        self,
        task_id: int,
        payload: dict[str, Any],
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        payload = dict(payload or {})
        self._attach_claim_token(payload, claim_token)
        route_attempt_id = _CURRENT_ROUTE_ATTEMPT_ID.get()
        if route_attempt_id is not None and "routeAttemptId" not in payload:
            payload["routeAttemptId"] = route_attempt_id
        payload.setdefault("failureStage", _infer_failure_stage(str(payload.get("errorCode") or "")))
        legacy_message = payload.get("errorMessage")
        if legacy_message is not None and "developerMessage" not in payload:
            payload["developerMessage"] = legacy_message
        if trace_id and "failureTraceId" not in payload:
            payload["failureTraceId"] = trace_id
        if _should_attempt_route_failover(payload):
            try:
                failover = self.request_route_failover(task_id, payload, trace_id=trace_id)
            except BackendClientError:
                LOGGER.warning(
                    "route failover decision failed; falling back to terminal failure taskId=%s",
                    task_id,
                    exc_info=True,
                )
            else:
                switched = bool(failover.get("switched")) or str(failover.get("action") or "").upper() == "SWITCH"
                execution_context = failover.get("executionContext")
                if switched and isinstance(execution_context, dict):
                    route_attempt_id = failover.get("routeAttemptId")
                    raise RouteFailoverRequested(
                        execution_context,
                        int(route_attempt_id) if route_attempt_id is not None else None,
                    )
        terminal_payload = _terminal_failure_payload(payload)
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/failed",
            json_body=terminal_payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def request_route_failover(
        self,
        task_id: int,
        payload: dict[str, Any],
        *,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        failover_payload = dict(payload or {})
        self._attach_claim_token(failover_payload, claim_token)
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/route-failover",
            json_body=failover_payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def get_subject_sync_context(self, subject_code: str, trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "GET",
            f"/api/internal/v1/subjects/{subject_code}/sync-context",
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def report_subject_sync_result(
        self,
        subject_code: str,
        payload: dict[str, Any],
        trace_id: str | None = None,
    ) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/subjects/{subject_code}/sync-result",
            json_body=payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: dict[str, Any] | None = None,
        timeout: tuple[int, int],
        trace_id: str | None = None,
    ) -> requests.Response:
        body = b""
        kwargs: dict[str, Any] = {"timeout": timeout}
        if json_body is not None:
            body = json.dumps(json_body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            kwargs["data"] = body
        kwargs["headers"] = self._signature_headers(method, path, body, trace_id=trace_id)
        start = time.perf_counter()
        try:
            response = self.session.request(method, self._url(path), **kwargs)
        except requests.RequestException:
            record_backend_request(method, path, "network_error", time.perf_counter() - start)
            raise
        record_backend_request(method, path, str(response.status_code), time.perf_counter() - start)
        _record_backend_operation_metric(path, response.status_code)
        return response

    def _signature_headers(self, method: str, path: str, body: bytes, *, trace_id: str | None = None) -> dict[str, str]:
        timestamp = str(int(time.time() * 1000))
        nonce = str(uuid.uuid4())
        request_path = urlsplit(path).path
        body_hash = hashlib.sha256(body).hexdigest()
        content = "\n".join([method.upper(), request_path, timestamp, nonce, body_hash])
        signature = hmac.new(
            settings.internal_api_token.encode("utf-8"),
            content.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        headers = {
            "X-Internal-Timestamp": timestamp,
            "X-Internal-Nonce": nonce,
            "X-Internal-Signature": signature,
        }
        if trace_id:
            headers["X-Request-Id"] = trace_id
        return headers

    def _parse_response(self, response: requests.Response) -> dict[str, Any]:
        try:
            payload = response.json()
        except ValueError:
            payload = None

        trace_id = _response_trace_id(response, payload)
        response_ok = getattr(response, "ok", 200 <= int(response.status_code) < 300)
        if not response_ok:
            error_code, message = _response_error(payload, f"backend HTTP {response.status_code}")
            raise BackendClientError(
                _backend_error_message(response.status_code, error_code, message, trace_id),
                error_code=error_code,
                trace_id=trace_id,
                status_code=response.status_code,
            )

        if not isinstance(payload, dict):
            raise BackendClientError(
                f"backend returned non-json response traceId={trace_id or '-'}",
                error_code="API_001",
                trace_id=trace_id,
                status_code=response.status_code,
            )

        code = payload.get("code")
        if payload.get("errorCode") or (code is not None and code != "SUCCESS"):
            error_code, message = _response_error(payload, "backend business error")
            raise BackendClientError(
                _backend_error_message(response.status_code, error_code, message, trace_id),
                error_code=error_code,
                trace_id=trace_id,
                status_code=response.status_code,
            )

        if code != "SUCCESS":
            raise BackendClientError(
                f"backend returned an invalid success envelope traceId={trace_id or '-'}",
                error_code="API_001",
                trace_id=trace_id,
                status_code=response.status_code,
            )

        data = payload.get("data")
        if data is None:
            LOGGER.debug("backend response data is empty: %s", payload)
            return {}
        return data

    def _attach_claim_token(self, payload: dict[str, Any], claim_token: str | None = None) -> None:
        token = claim_token or _CURRENT_CLAIM_TOKEN.get()
        if token and "claimToken" not in payload:
            payload["claimToken"] = token


@contextmanager
def backend_claim_context(claim_token: str | None):
    token = _CURRENT_CLAIM_TOKEN.set(claim_token)
    try:
        yield
    finally:
        _CURRENT_CLAIM_TOKEN.reset(token)


@contextmanager
def backend_route_context(route_attempt_id: int | None):
    token = _CURRENT_ROUTE_ATTEMPT_ID.set(route_attempt_id)
    try:
        yield
    finally:
        _CURRENT_ROUTE_ATTEMPT_ID.reset(token)


def _infer_failure_stage(error_code: str) -> str:
    normalized = (error_code or "").strip().upper()
    if normalized in {"INVALID_TASK_PARAMS", "PROMPT_VARIABLE_MISSING"}:
        return "VALIDATION"
    if normalized in {"MODEL_PROVIDER_UNAVAILABLE", "MODEL_AUTH_FAILED", "MODEL_CREDIT_INSUFFICIENT", "MODEL_RATE_LIMITED"}:
        return "BEFORE_PROVIDER"
    if normalized in {"MEDIA_PERSIST_FAILED", "POSTPROCESS_FAILED"}:
        return "MEDIA_PERSIST"
    if normalized in {"WORKER_INTERNAL_ERROR"}:
        return "WORKER_INTERNAL"
    if normalized in {"MODEL_TIMEOUT"}:
        return "PROVIDER_POLLING"
    if normalized.startswith("MODEL_"):
        return "PROVIDER_SUBMITTED"
    return "UNKNOWN"


def _should_attempt_route_failover(payload: dict[str, Any]) -> bool:
    retry_scope = str(payload.get("retryScope") or "").strip().upper()
    delivery_state = str(payload.get("deliveryState") or "").strip().upper()
    return (
        retry_scope == "ACCOUNT"
        and delivery_state in {"NOT_SENT", "REJECTED"}
        and not payload.get("providerRequestId")
        and payload.get("providerCharged") is not True
    )


def _terminal_failure_payload(payload: dict[str, Any]) -> dict[str, Any]:
    supported = {
        "errorCode",
        "errorMessage",
        "userMessage",
        "developerMessage",
        "failureTraceId",
        "failureStage",
        "providerCharged",
        "providerCostAmount",
        "providerCostCurrency",
        "providerErrorCode",
        "providerRequestId",
        "promptTokens",
        "completionTokens",
        "billableUnits",
        "deliveryState",
        "retryScope",
        "retryAfterSeconds",
        "claimToken",
    }
    return {key: value for key, value in payload.items() if key in supported}


def _response_trace_id(response: requests.Response, payload: object) -> str | None:
    if isinstance(payload, dict):
        candidate = payload.get("traceId") or payload.get("requestId")
        if candidate is not None and str(candidate).strip():
            return str(candidate).strip()[:64]
    headers = getattr(response, "headers", None)
    header = headers.get("X-Request-Id") if headers is not None else None
    return header.strip()[:64] if header and header.strip() else None


def _response_error(payload: object, fallback: str) -> tuple[str, str]:
    if not isinstance(payload, dict):
        return "SYSTEM_001", fallback
    code = str(payload.get("errorCode") or payload.get("code") or "SYSTEM_001").strip()[:64]
    if code.upper() == "SUCCESS":
        return "SYSTEM_001", fallback
    message = str(
        payload.get("developerMessage")
        or payload.get("userMessage")
        or payload.get("message")
        or fallback
    ).strip()
    return code or "SYSTEM_001", (message or fallback)[:2000]


def _backend_error_message(status: int, error_code: str, message: str, trace_id: str | None) -> str:
    return (
        f"backend request failed: status={status}, errorCode={error_code}, "
        f"traceId={trace_id or '-'}, message={message}"
    )


def _record_backend_operation_metric(path: str, status_code: int) -> None:
    ok = 200 <= int(status_code) < 300
    normalized = urlsplit(path).path
    if normalized.endswith("/processing"):
        record_status_update("processing" if ok else "processing_failed")
    elif normalized.endswith("/success"):
        record_status_update("success" if ok else "success_failed")
    elif normalized.endswith("/failed"):
        record_status_update("failed" if ok else "failed_failed")
    elif normalized.endswith("/lease/renew"):
        record_lease_renew("success" if ok else "failed")

