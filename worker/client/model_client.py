import json
import time
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

import requests
from requests.exceptions import ConnectionError as RequestsConnectionError
from requests.exceptions import SSLError

from config import settings
from volcengine_model import normalize_volcengine_openai_base_url, resolve_volcengine_model_name


class ModelClientError(RuntimeError):
    pass


class ModelTimeoutError(ModelClientError):
    pass


class ModelOutputEmptyError(ModelClientError):
    pass


@dataclass(frozen=True)
class ModelGenerationResult:
    content: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


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
        return self.generate_with_usage(
            prompt,
            system_prompt=system_prompt,
            provider=provider,
            model_name=model_name,
            base_url=base_url,
            api_key=api_key,
            timeout_seconds=timeout_seconds,
            max_tokens=max_tokens,
        ).content

    def generate_with_usage(
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
    ) -> ModelGenerationResult:
        effective_provider = provider or settings.model_provider
        effective_base_url = normalize_volcengine_openai_base_url((base_url or self.base_url).rstrip("/"))
        effective_api_key = api_key or self.api_key
        effective_model_name = resolve_volcengine_model_name(
            model_name or self.default_model_name,
            effective_base_url,
        )
        timeout = (5, timeout_seconds or self.timeout[1])
        effective_max_tokens = max_tokens or self.default_max_tokens

        if effective_provider == "mock":
            content = (
                "Local demo result: Worker received the task and generated a mock response.\n\n"
                f"Input:\n{prompt[:500]}"
            )
            return ModelGenerationResult(
                content=content,
                prompt_tokens=self._estimate_tokens(system_prompt, prompt),
                completion_tokens=self._estimate_tokens(content),
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
        prompt_tokens, completion_tokens = self._extract_openai_usage(payload)
        return ModelGenerationResult(content, prompt_tokens, completion_tokens)

    def generate_stream_with_usage(
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
    ) -> Iterator[str]:
        """OpenAI-compatible SSE 流式生成，逐段 yield 文本增量。"""
        effective_provider = provider or settings.model_provider
        if effective_provider in {"mock", "anthropic_compatible"}:
            content = self.generate(
                prompt,
                system_prompt=system_prompt,
                provider=provider,
                model_name=model_name,
                base_url=base_url,
                api_key=api_key,
                timeout_seconds=timeout_seconds,
                max_tokens=max_tokens,
            )
            for chunk in self._chunk_text(content, 24):
                yield chunk
            return

        effective_base_url = normalize_volcengine_openai_base_url((base_url or self.base_url).rstrip("/"))
        effective_api_key = api_key or self.api_key
        effective_model_name = resolve_volcengine_model_name(
            model_name or self.default_model_name,
            effective_base_url,
        )
        timeout = (5, timeout_seconds or self.timeout[1])
        effective_max_tokens = max_tokens or self.default_max_tokens

        if not effective_api_key or effective_api_key == "replace-with-model-key":
            raise ModelClientError("MODEL_API_KEY is not configured")

        messages: list[dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        response = self._post_with_timeout_retry(
            f"{effective_base_url}/chat/completions",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {effective_api_key}",
                "Accept": "text/event-stream",
            },
            payload={
                "model": effective_model_name,
                "messages": messages,
                "stream": True,
                "max_tokens": effective_max_tokens,
            },
            timeout=timeout,
            stream=True,
        )
        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise ModelClientError(
                f"model request failed: status={response.status_code}, body={response.text}"
            ) from exc

        for raw_line in response.iter_lines(decode_unicode=True):
            if not raw_line or not raw_line.startswith("data:"):
                continue
            data = raw_line[5:].strip()
            if not data or data == "[DONE]":
                continue
            try:
                payload = json.loads(data)
            except json.JSONDecodeError:
                continue
            choices = payload.get("choices") or []
            if not choices:
                continue
            delta = choices[0].get("delta") or {}
            piece = delta.get("content")
            if isinstance(piece, str) and piece:
                yield piece

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
    ) -> ModelGenerationResult:
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
        prompt_tokens, completion_tokens = self._extract_anthropic_usage(payload)
        return ModelGenerationResult(content, prompt_tokens, completion_tokens)

    @staticmethod
    def _chunk_text(value: str, size: int) -> list[str]:
        return [value[index : index + size] for index in range(0, len(value), size)] or [""]

    def _post_with_timeout_retry(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, Any],
        timeout: tuple[int, int],
        stream: bool = False,
    ) -> requests.Response:
        request_headers = {**headers, "Connection": "close"}
        last_timeout: requests.Timeout | None = None
        last_transient: Exception | None = None
        max_attempts = 4
        for attempt in range(max_attempts):
            try:
                return requests.post(
                    url,
                    headers=request_headers,
                    json=payload,
                    timeout=timeout,
                    stream=stream,
                )
            except requests.Timeout as exc:
                last_timeout = exc
            except (SSLError, RequestsConnectionError) as exc:
                last_transient = exc
                if attempt < max_attempts - 1:
                    time.sleep(0.5 * (attempt + 1))
                    continue
            except requests.RequestException as exc:
                raise ModelClientError(f"model request failed: {exc}") from exc
        if last_transient is not None:
            raise ModelClientError(f"model request failed: {last_transient}") from last_transient
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

    @staticmethod
    def _extract_openai_usage(payload: dict[str, Any]) -> tuple[int, int]:
        usage = payload.get("usage") or {}
        return (
            ModelClient._non_negative_int(usage.get("prompt_tokens") or usage.get("input_tokens")),
            ModelClient._non_negative_int(usage.get("completion_tokens") or usage.get("output_tokens")),
        )

    @staticmethod
    def _extract_anthropic_usage(payload: dict[str, Any]) -> tuple[int, int]:
        usage = payload.get("usage") or {}
        return (
            ModelClient._non_negative_int(usage.get("input_tokens")),
            ModelClient._non_negative_int(usage.get("output_tokens")),
        )

    @staticmethod
    def _non_negative_int(value: Any) -> int:
        try:
            return max(0, int(value or 0))
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _estimate_tokens(*parts: str) -> int:
        text = "\n".join(part for part in parts if part)
        if not text:
            return 0
        return max(1, len(text) // 4)
