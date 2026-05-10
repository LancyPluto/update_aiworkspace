from typing import Any

import requests

from config import settings


class ModelClientError(RuntimeError):
    pass


class ModelTimeoutError(ModelClientError):
    pass


class ModelOutputEmptyError(ModelClientError):
    pass


class ModelClient:
    def __init__(self) -> None:
        self.base_url = settings.model_api_base_url.rstrip("/")
        self.api_key = settings.model_api_key
        self.default_model_name = settings.model_name
        self.timeout = (5, 60)
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            }
        )

    def generate(
        self,
        prompt: str,
        *,
        system_prompt: str = "",
        model_name: str | None = None,
    ) -> str:
        if settings.model_provider == "mock":
            return (
                "Local demo result: Worker received the task and generated a mock response.\n\n"
                f"Input:\n{prompt[:500]}"
            )

        if not self.api_key or self.api_key == "replace-with-model-key":
            raise ModelClientError("MODEL_API_KEY is not configured")

        messages: list[dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        try:
            response = self.session.post(
                f"{self.base_url}/chat/completions",
                json={
                    "model": model_name or self.default_model_name,
                    "messages": messages,
                    "stream": False,
                },
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise ModelTimeoutError("model request timed out") from exc
        except requests.RequestException as exc:
            raise ModelClientError(f"model request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise ModelClientError(
                f"model request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise ModelClientError("model returned non-json response") from exc

        content = self._extract_content(payload)
        if not content:
            raise ModelOutputEmptyError("model returned empty content")
        return content

    @staticmethod
    def _extract_content(payload: dict[str, Any]) -> str:
        choices = payload.get("choices") or []
        if not choices:
            return ""

        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content.strip()
        return ""
