import logging
import hashlib
import hmac
import json
import time
import uuid
from typing import Any
from urllib.parse import urlsplit

import requests

from config import settings


LOGGER = logging.getLogger(__name__)


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

    def mark_processing(self, task_id: int, trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/processing",
            json_body={},
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def mark_success(self, task_id: int, payload: dict[str, Any], trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/success",
            json_body=payload,
            timeout=self.timeout,
            trace_id=trace_id,
        )
        return self._parse_response(response)

    def mark_failed(self, task_id: int, payload: dict[str, Any], trace_id: str | None = None) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/api/internal/v1/tasks/{task_id}/failed",
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
        return self.session.request(method, self._url(path), **kwargs)

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
