import logging
from typing import Any, Optional

import httpx

from ai_task_worker.config import Settings
from ai_task_worker.schemas import ExecutionContext, FailedPayload, SuccessPayload

logger = logging.getLogger(__name__)


class InternalTaskApiError(Exception):
    def __init__(
        self,
        message: str,
        *,
        status_code: Optional[int] = None,
        body: Optional[str] = None,
    ):
        super().__init__(message)
        self.status_code = status_code
        self.body = body


class InternalTaskClient:
    """
    调用 Spring 内部任务接口。鉴权头与后端实现保持一致后再调整（当前为占位约定）。
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = httpx.Client(
            base_url=settings.backend_base_url.rstrip("/"),
            timeout=httpx.Timeout(settings.http_timeout_sec),
            headers=self._headers(),
        )

    def _headers(self) -> dict:
        return {
            "X-Internal-Token": self._settings.internal_api_token,
            "Content-Type": "application/json",
        }

    def close(self) -> None:
        self._client.close()

    def get_execution_context(self, task_id: int) -> ExecutionContext:
        path = f"/api/internal/v1/tasks/{task_id}/execution-context"
        r = self._client.get(path)
        data = self._unwrap_json(r)
        return ExecutionContext.model_validate(data)

    def post_processing(self, task_id: int) -> None:
        path = f"/api/internal/v1/tasks/{task_id}/processing"
        r = self._client.post(path)
        self._expect_ok(r)

    def post_success(self, task_id: int, payload: SuccessPayload) -> None:
        path = f"/api/internal/v1/tasks/{task_id}/success"
        r = self._client.post(path, json=payload.model_dump(by_alias=True))
        self._expect_ok(r)

    def post_failed(self, task_id: int, payload: FailedPayload) -> None:
        path = f"/api/internal/v1/tasks/{task_id}/failed"
        r = self._client.post(path, json=payload.model_dump(by_alias=True))
        self._expect_ok(r)

    def _unwrap_json(self, response: httpx.Response) -> Any:
        self._expect_ok(response)
        return response.json()

    def _expect_ok(self, response: httpx.Response) -> None:
        if response.is_success:
            return
        msg = f"HTTP {response.status_code} for {response.request.url!s}"
        logger.warning("%s body=%s", msg, response.text[:500])
        raise InternalTaskApiError(
            msg,
            status_code=response.status_code,
            body=response.text,
        )
