import json
import time
from typing import Any

import requests

DEFAULT_DASHSCOPE_VIDEO_TIMEOUT_SECONDS = 3600


class DashScopeVideoError(RuntimeError):
    pass


class DashScopeVideoTimeoutError(DashScopeVideoError):
    pass


class DashScopeVideoClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_seconds: int | None = None,
        poll_interval_seconds: float = 5,
    ) -> None:
        self.base_url = (base_url or "https://dashscope.aliyuncs.com").rstrip("/")
        self.api_key = (api_key or "").strip()
        configured_timeout = int(timeout_seconds or DEFAULT_DASHSCOPE_VIDEO_TIMEOUT_SECONDS)
        self.timeout_seconds = max(DEFAULT_DASHSCOPE_VIDEO_TIMEOUT_SECONDS, configured_timeout)
        self.poll_interval_seconds = poll_interval_seconds
        self.timeout = (10, 300)
        self.session = requests.Session()

    def generate_video(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise DashScopeVideoError("DashScope API key is not configured")

        created = self._request(
            "POST",
            "/api/v1/services/aigc/video-generation/video-synthesis",
            payload,
            async_request=True,
        )
        task_id = self._extract_task_id(created)
        finished = self.wait_for_video(task_id)
        return {
            "provider": "bailian_happyhorse",
            "model": payload.get("model"),
            "requestId": self._extract_request_id(created) or self._extract_request_id(finished),
            "dashscopeTaskId": task_id,
            "status": self._extract_status(finished),
            "videoUrl": self._extract_video_url(finished),
            "usage": self._extract_usage(finished),
            "raw": finished,
        }

    def wait_for_video(self, task_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last_payload = self._request("GET", f"/api/v1/tasks/{task_id}", None)
            status = self._extract_status(last_payload).upper()
            if status == "SUCCEEDED":
                return last_payload
            if status in {"FAILED", "CANCELED", "CANCELLED", "UNKNOWN"}:
                raise DashScopeVideoError(self._failure_reason(last_payload, status))
            time.sleep(self.poll_interval_seconds)
        raise DashScopeVideoTimeoutError(
            f"dashscope video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}"
        )

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None,
        *,
        async_request: bool = False,
    ) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        if body:
            headers["Content-Type"] = "application/json"
        if async_request:
            headers["X-DashScope-Async"] = "enable"
        try:
            response = self.session.request(
                method,
                f"{self.base_url}{path}",
                data=body.encode("utf-8") if body else None,
                headers=headers,
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise DashScopeVideoTimeoutError("dashscope video request timed out") from exc
        except requests.RequestException as exc:
            raise DashScopeVideoError(f"dashscope video request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise DashScopeVideoError(
                f"dashscope video request failed: status={response.status_code}, body={response.text}"
            ) from exc
        try:
            data = response.json()
        except ValueError as exc:
            raise DashScopeVideoError("dashscope returned non-json response") from exc
        if not isinstance(data, dict):
            raise DashScopeVideoError("dashscope returned invalid response")
        return data

    @staticmethod
    def _extract_task_id(payload: dict[str, Any]) -> str:
        for key in ("task_id", "taskId", "id"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        output = payload.get("output")
        if isinstance(output, dict):
            return DashScopeVideoClient._extract_task_id(output)
        raise DashScopeVideoError("dashscope create response missing task id")

    @staticmethod
    def _extract_request_id(payload: dict[str, Any]) -> str:
        value = payload.get("request_id") or payload.get("requestId")
        return str(value).strip() if value else ""

    @staticmethod
    def _extract_status(payload: dict[str, Any]) -> str:
        for key in ("task_status", "taskStatus", "status"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        output = payload.get("output")
        if isinstance(output, dict):
            return DashScopeVideoClient._extract_status(output)
        return "PENDING"

    @staticmethod
    def _extract_usage(payload: dict[str, Any]) -> dict[str, Any]:
        usage = payload.get("usage")
        if isinstance(usage, dict):
            return usage
        output = payload.get("output")
        if isinstance(output, dict) and isinstance(output.get("usage"), dict):
            return output["usage"]
        return {}

    @staticmethod
    def _extract_video_url(payload: dict[str, Any]) -> str:
        output = payload.get("output")
        if isinstance(output, dict):
            for key in ("video_url", "videoUrl", "url"):
                value = output.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip()
        for value in DashScopeVideoClient._walk(payload):
            if isinstance(value, str) and value.lower().split("?", 1)[0].endswith((".mp4", ".mov", ".webm")):
                return value.strip()
        raise DashScopeVideoError("dashscope response missing video url")

    @staticmethod
    def _failure_reason(payload: dict[str, Any], status: str) -> str:
        output = payload.get("output")
        if isinstance(output, dict):
            message = output.get("message") or output.get("task_status_msg") or output.get("code")
            if message:
                return f"dashscope video generation failed: status={status}, message={message}"
        return f"dashscope video generation failed: status={status}"

    @staticmethod
    def _walk(value: Any) -> list[Any]:
        values = [value]
        if isinstance(value, dict):
            for nested in value.values():
                values.extend(DashScopeVideoClient._walk(nested))
        elif isinstance(value, list):
            for item in value:
                values.extend(DashScopeVideoClient._walk(item))
        return values
