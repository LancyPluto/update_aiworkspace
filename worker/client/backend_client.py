import logging
import hashlib
import hmac
import json
import time
import uuid
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any
from urllib.parse import urlsplit

import requests

from config import settings
from observability.metrics import record_backend_request, record_lease_renew, record_status_update


LOGGER = logging.getLogger(__name__)
_CURRENT_CLAIM_TOKEN: ContextVar[str | None] = ContextVar("worker_claim_token", default=None)


class BackendClientError(RuntimeError):
    pass


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
        payload.setdefault("failureStage", _infer_failure_stage(str(payload.get("errorCode") or "")))
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/failed",
            json_body=payload,
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
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise BackendClientError(
                f"backend request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise BackendClientError("backend returned non-json response") from exc

        code = payload.get("code")
        if code != "SUCCESS":
            raise BackendClientError(
                f"backend business error: code={code}, message={payload.get('message', '')}"
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

