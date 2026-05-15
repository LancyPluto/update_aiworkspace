from typing import Any

import requests

from client.backend_client import BackendClient, BackendClientError
from config import settings


class ModelClientError(RuntimeError):
    pass


class ModelTimeoutError(ModelClientError):
    pass


class ModelOutputEmptyError(ModelClientError):
    pass


class ModelClient:
    def __init__(self, backend_client: BackendClient | None = None) -> None:
        self.base_url = settings.model_api_base_url.rstrip("/")
        self.api_key = settings.model_api_key
        self.default_model_name = settings.model_name
        self.backend_client = backend_client or BackendClient()
        self.timeout = (5, 60)
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})

    def generate(
        self,
        prompt: str,
        *,
        system_prompt: str = "",
        model_name: str | None = None,
    ) -> str:
        config = self._resolve_model_config()
        provider = str(config.get("provider") or settings.model_provider).strip().lower()
        if provider == "mock":
            return (
                "Local demo result: Worker received the task and generated a mock response.\n\n"
                f"Input:\n{prompt[:500]}"
            )

        api_key = str(config.get("apiKey") or "").strip()
        if not api_key or api_key == "replace-with-model-key":
            raise ModelClientError("admin model API key is not configured")

        base_url = str(config.get("baseUrl") or self.base_url).rstrip("/")
        resolved_model_name = str(config.get("modelName") or model_name or self.default_model_name).strip()
        timeout_seconds = max(int(config.get("timeoutSeconds") or 60), 180)
        timeout = (5, timeout_seconds)

        messages: list[dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        if provider == "anthropic_compatible":
            response = self._post_anthropic_compatible(
                base_url=base_url,
                api_key=api_key,
                model_name=resolved_model_name,
                messages=messages,
                system_prompt=system_prompt,
                timeout=timeout,
            )
        else:
            response = self._post_openai_compatible(
                base_url=base_url,
                api_key=api_key,
                model_name=resolved_model_name,
                messages=messages,
                timeout=timeout,
            )

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

    def _post_openai_compatible(
        self,
        *,
        base_url: str,
        api_key: str,
        model_name: str,
        messages: list[dict[str, str]],
        timeout: tuple[int, int],
    ) -> requests.Response:
        try:
            return self.session.post(
                f"{base_url}/chat/completions",
                json={
                    "model": model_name,
                    "messages": messages,
                    "stream": False,
                },
                headers={"Authorization": f"Bearer {api_key}"},
                timeout=timeout,
            )
        except requests.Timeout as exc:
            raise ModelTimeoutError("model request timed out") from exc
        except requests.RequestException as exc:
            raise ModelClientError(f"model request failed: {exc}") from exc

    def _post_anthropic_compatible(
        self,
        *,
        base_url: str,
        api_key: str,
        model_name: str,
        messages: list[dict[str, str]],
        system_prompt: str,
        timeout: tuple[int, int],
    ) -> requests.Response:
        user_messages = [message for message in messages if message["role"] != "system"]
        payload: dict[str, Any] = {
            "model": model_name,
            "messages": user_messages,
            "max_tokens": 4096,
        }
        if system_prompt:
            payload["system"] = system_prompt
        try:
            return self.session.post(
                f"{base_url}/v1/messages",
                json=payload,
                headers={
                    "x-api-key": api_key,
                    "anthropic-version": "2023-06-01",
                },
                timeout=timeout,
            )
        except requests.Timeout as exc:
            raise ModelTimeoutError("model request timed out") from exc
        except requests.RequestException as exc:
            raise ModelClientError(f"model request failed: {exc}") from exc

    def _resolve_model_config(self) -> dict[str, Any]:
        try:
            config = self.backend_client.get_agent_model_config()
        except BackendClientError:
            return {
                "provider": settings.model_provider,
                "modelName": self.default_model_name,
                "baseUrl": self.base_url,
                "apiKey": self.api_key,
                "timeoutSeconds": 60,
                "enabled": True,
            }

        if config.get("enabled") is False:
            raise ModelClientError("admin model config is disabled")
        return config

    @staticmethod
    def _extract_content(payload: dict[str, Any]) -> str:
        anthropic_content = payload.get("content")
        if isinstance(anthropic_content, list):
            parts = [
                str(item.get("text") or "").strip()
                for item in anthropic_content
                if isinstance(item, dict) and item.get("type") == "text"
            ]
            return "\n".join(part for part in parts if part).strip()
        if isinstance(anthropic_content, str):
            return anthropic_content.strip()

        choices = payload.get("choices") or []
        if not choices:
            return ""

        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content.strip()
        return ""
