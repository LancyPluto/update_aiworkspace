import logging
from typing import Any

import httpx

from ai_task_worker.config import Settings

logger = logging.getLogger(__name__)


class ModelClientError(Exception):
    pass


class OpenAiCompatibleModelClient:
    """OpenAI 兼容 Chat Completions（DeepSeek 等）。"""

    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = httpx.Client(
            base_url=settings.model_api_base_url.rstrip("/"),
            timeout=httpx.Timeout(settings.http_timeout_sec),
            headers={
                "Authorization": f"Bearer {settings.model_api_key}",
                "Content-Type": "application/json",
            },
        )

    def close(self) -> None:
        self._client.close()

    def chat(
        self,
        *,
        model: str,
        system_prompt: str,
        user_content: str,
    ) -> str:
        if not self._settings.model_api_key:
            raise ModelClientError("MODEL_API_KEY 未配置")

        body: dict[str, Any] = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
        }
        r = self._client.post("/v1/chat/completions", json=body)
        if not r.is_success:
            raise ModelClientError(f"模型 HTTP {r.status_code}: {r.text[:300]}")

        data = r.json()
        try:
            choice = data["choices"][0]
            content = choice["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise ModelClientError(f"模型响应结构异常: {e}") from e

        if not content or not str(content).strip():
            raise ModelClientError("模型返回空内容")

        return str(content).strip()
