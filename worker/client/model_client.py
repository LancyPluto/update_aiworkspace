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

    @property
    def default_max_tokens(self) -> int:
        return 1024

    def generate(
        self,
        prompt: str,
        *,
        system_prompt: str = "",
        provider: str | None = None,
        model_name: str | None = None,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_seconds: int | None = None,
        max_tokens: int | None = None,
    ) -> str:
        effective_provider = provider or settings.model_provider
        effective_base_url = (base_url or self.base_url).rstrip("/")
        effective_api_key = api_key or self.api_key
        effective_model_name = model_name or self.default_model_name
        timeout = (5, timeout_seconds or self.timeout[1])
        effective_max_tokens = max_tokens or self.default_max_tokens

        if effective_provider == "mock":
            return (
                "Local demo result: Worker received the task and generated a mock response.\n\n"
                f"Input:\n{prompt[:500]}"
            )

        if not effective_api_key or effective_api_key == "replace-with-model-key":
            raise ModelClientError("MODEL_API_KEY is not configured")

        messages: list[dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        if effective_provider == "anthropic_compatible":
            return self._generate_anthropic_compatible(
                prompt,
                system_prompt=system_prompt,
                model_name=effective_model_name,
                base_url=effective_base_url,
                api_key=effective_api_key,
                timeout=timeout,
                max_tokens=effective_max_tokens,
            )

        response = self._post_with_timeout_retry(
            f"{effective_base_url}/chat/completions",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {effective_api_key}",
            },
            payload={
                "model": effective_model_name,
                "messages": messages,
                "stream": False,
                "max_tokens": effective_max_tokens,
            },
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

    def _generate_anthropic_compatible(
        self,
        prompt: str,
        *,
        system_prompt: str,
        model_name: str,
        base_url: str,
        api_key: str,
        timeout: tuple[int, int],
        max_tokens: int,
    ) -> str:
        response = self._post_with_timeout_retry(
            f"{base_url}/v1/messages",
            headers={
                "Content-Type": "application/json",
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
            payload={
                "model": model_name,
                "system": system_prompt,
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": max_tokens,
            },
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

        content = self._extract_anthropic_content(payload)
        if not content:
            raise ModelOutputEmptyError("model returned empty content")
        return content

    def _post_with_timeout_retry(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, Any],
        timeout: tuple[int, int],
    ) -> requests.Response:
        last_timeout: requests.Timeout | None = None
        for _ in range(2):
            try:
                return requests.post(url, headers=headers, json=payload, timeout=timeout)
            except requests.Timeout as exc:
                last_timeout = exc
            except requests.RequestException as exc:
                raise ModelClientError(f"model request failed: {exc}") from exc
        raise ModelTimeoutError("model request timed out") from last_timeout

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

    @staticmethod
    def _extract_anthropic_content(payload: dict[str, Any]) -> str:
        blocks = payload.get("content") or []
        parts: list[str] = []
        for block in blocks:
            if isinstance(block, dict) and isinstance(block.get("text"), str):
                parts.append(block["text"])
        return "\n".join(part.strip() for part in parts if part.strip())
