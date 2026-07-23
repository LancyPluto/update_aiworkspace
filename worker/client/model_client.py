import json
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

import requests
from requests.exceptions import ConnectionError as RequestsConnectionError
from requests.exceptions import ConnectTimeout, ReadTimeout, SSLError

from client.provider_error import (
    DELIVERY_NOT_SENT,
    RETRY_ACCOUNT,
    ProviderCallError,
    rejected_http_metadata,
    request_was_not_sent,
    response_provider_error_code,
)
from config import settings
from utils.model_contract import read_json_path, read_response_value, response_mapping_has
from volcengine_model import normalize_volcengine_openai_base_url, resolve_volcengine_model_name


class ModelClientError(ProviderCallError):
    pass


class ModelTimeoutError(ModelClientError):
    pass


class ModelOutputEmptyError(ModelClientError):
    pass


class ModelRequestNotSentError(ModelClientError):
    def __init__(self, message: str) -> None:
        super().__init__(
            message,
            delivery_state=DELIVERY_NOT_SENT,
            retry_scope=RETRY_ACCOUNT,
            failure_stage="BEFORE_PROVIDER",
        )


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
        model_params: dict[str, Any] | None = None,
        response_mapping: dict[str, Any] | None = None,
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
            model_params=model_params,
            response_mapping=response_mapping,
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
        model_params: dict[str, Any] | None = None,
        response_mapping: dict[str, Any] | None = None,
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
                model_params=model_params,
                response_mapping=response_mapping,
            )

        request_payload: dict[str, Any] = {
            "model": effective_model_name,
            "messages": messages,
            "stream": False,
            "max_tokens": effective_max_tokens,
        }
        _apply_openai_generation_params(request_payload, model_params)
        response = self._post_with_timeout_retry(
            f"{effective_base_url}/chat/completions",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {effective_api_key}",
            },
            payload=request_payload,
            timeout=timeout,
        )

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise ModelClientError(
                f"model request failed: status={response.status_code}, body={response.text}",
                **rejected_http_metadata(
                    response.status_code,
                    _retry_after_seconds(getattr(response, "headers", {}).get("Retry-After")),
                    response_provider_error_code(response),
                ),
            ) from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise ModelClientError("model returned non-json response") from exc

        content = self._extract_mapped_content(payload, response_mapping, provider="openai")
        if not content:
            raise ModelOutputEmptyError("model returned empty content")
        prompt_tokens, completion_tokens = self._extract_mapped_usage(
            payload,
            response_mapping,
            provider="openai",
        )
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
        model_params: dict[str, Any] | None = None,
        response_mapping: dict[str, Any] | None = None,
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
                model_params=model_params,
                response_mapping=response_mapping,
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

        request_payload: dict[str, Any] = {
            "model": effective_model_name,
            "messages": messages,
            "stream": True,
            "max_tokens": effective_max_tokens,
        }
        _apply_openai_generation_params(request_payload, model_params)
        request_payload["stream"] = True
        response = self._post_with_timeout_retry(
            f"{effective_base_url}/chat/completions",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {effective_api_key}",
                "Accept": "text/event-stream",
            },
            payload=request_payload,
            timeout=timeout,
            stream=True,
        )
        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise ModelClientError(
                f"model request failed: status={response.status_code}, body={response.text}",
                **rejected_http_metadata(
                    response.status_code,
                    _retry_after_seconds(getattr(response, "headers", {}).get("Retry-After")),
                    response_provider_error_code(response),
                ),
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
            piece = self._extract_stream_content(payload, response_mapping)
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
        model_params: dict[str, Any] | None,
        response_mapping: dict[str, Any] | None,
    ) -> ModelGenerationResult:
        request_payload: dict[str, Any] = {
            "model": model_name,
            "system": system_prompt,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": max_tokens,
        }
        _apply_anthropic_generation_params(request_payload, model_params)
        response = self._post_with_timeout_retry(
            f"{base_url}/v1/messages",
            headers={
                "Content-Type": "application/json",
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
            payload=request_payload,
            timeout=timeout,
        )

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise ModelClientError(
                f"model request failed: status={response.status_code}, body={response.text}",
                **rejected_http_metadata(
                    response.status_code,
                    _retry_after_seconds(getattr(response, "headers", {}).get("Retry-After")),
                    response_provider_error_code(response),
                ),
            ) from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise ModelClientError("model returned non-json response") from exc

        content = self._extract_mapped_content(payload, response_mapping, provider="anthropic")
        if not content:
            raise ModelOutputEmptyError("model returned empty content")
        prompt_tokens, completion_tokens = self._extract_mapped_usage(
            payload,
            response_mapping,
            provider="anthropic",
        )
        return ModelGenerationResult(content, prompt_tokens, completion_tokens)

    @classmethod
    def _extract_mapped_content(
        cls,
        payload: dict[str, Any],
        response_mapping: dict[str, Any] | None,
        *,
        provider: str,
    ) -> str:
        if response_mapping_has(response_mapping, "contentPath", "contentPaths"):
            value = _first_mapped_text(payload, response_mapping, "contentPath", "contentPaths")
            return value.strip()
        if provider == "anthropic":
            return cls._extract_anthropic_content(payload)
        return cls._extract_content(payload)

    @classmethod
    def _extract_mapped_usage(
        cls,
        payload: dict[str, Any],
        response_mapping: dict[str, Any] | None,
        *,
        provider: str,
    ) -> tuple[int, int]:
        if response_mapping_has(response_mapping, "usagePath", "usagePaths"):
            usage = read_response_value(payload, response_mapping, "usagePath", "usagePaths")
            if not isinstance(usage, dict):
                return 0, 0
            return (
                cls._non_negative_int(usage.get("prompt_tokens") or usage.get("input_tokens")),
                cls._non_negative_int(usage.get("completion_tokens") or usage.get("output_tokens")),
            )
        if provider == "anthropic":
            return cls._extract_anthropic_usage(payload)
        return cls._extract_openai_usage(payload)

    @staticmethod
    def _extract_stream_content(
        payload: dict[str, Any],
        response_mapping: dict[str, Any] | None,
    ) -> str:
        if response_mapping_has(response_mapping, "streamContentPath", "streamContentPaths"):
            return _first_mapped_text(
                payload,
                response_mapping,
                "streamContentPath",
                "streamContentPaths",
            )
        choices = payload.get("choices") or []
        if not choices or not isinstance(choices[0], dict):
            return ""
        delta = choices[0].get("delta") or {}
        piece = delta.get("content") if isinstance(delta, dict) else None
        return piece if isinstance(piece, str) else ""

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
        try:
            return requests.post(
                url,
                headers=request_headers,
                json=payload,
                timeout=timeout,
                stream=stream,
            )
        except ConnectTimeout as exc:
            raise ModelRequestNotSentError("model connect timed out before request was sent") from exc
        except ReadTimeout as exc:
            raise ModelTimeoutError("model response timed out after request may have been sent") from exc
        except SSLError as exc:
            if request_was_not_sent(exc):
                raise ModelRequestNotSentError("model TLS setup failed before request was sent") from exc
            raise ModelClientError("model TLS failure occurred after delivery became unknown") from exc
        except RequestsConnectionError as exc:
            if request_was_not_sent(exc):
                raise ModelRequestNotSentError(f"model connection failed before request was sent: {exc}") from exc
            raise ModelClientError(
                f"model connection failed after request may have been sent: {exc}"
            ) from exc
        except requests.Timeout as exc:
            raise ModelTimeoutError("model request timed out after delivery became unknown") from exc
        except requests.RequestException as exc:
            raise ModelClientError(f"model request failed after delivery became unknown: {exc}") from exc

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


_OPENAI_GENERATION_PARAM_ALIASES: dict[str, tuple[str, ...]] = {
    "temperature": ("temperature",),
    "top_p": ("top_p", "topP"),
    "max_tokens": ("max_tokens", "maxTokens"),
    "frequency_penalty": ("frequency_penalty", "frequencyPenalty"),
    "presence_penalty": ("presence_penalty", "presencePenalty"),
    "repetition_penalty": ("repetition_penalty", "repetitionPenalty"),
    "seed": ("seed",),
    "stop": ("stop",),
    "response_format": ("response_format", "responseFormat"),
    "enable_thinking": ("enable_thinking", "enableThinking"),
    "thinking_budget": ("thinking_budget", "thinkingBudget"),
    "thinking": ("thinking",),
}


def _apply_openai_generation_params(
    payload: dict[str, Any],
    model_params: dict[str, Any] | None,
) -> None:
    if not isinstance(model_params, dict):
        return
    for target, aliases in _OPENAI_GENERATION_PARAM_ALIASES.items():
        value = _first_present_param(model_params, aliases)
        if _has_param_value(value):
            payload[target] = value


def _apply_anthropic_generation_params(
    payload: dict[str, Any],
    model_params: dict[str, Any] | None,
) -> None:
    if not isinstance(model_params, dict):
        return
    aliases: dict[str, tuple[str, ...]] = {
        "temperature": ("temperature",),
        "top_p": ("top_p", "topP"),
        "top_k": ("top_k", "topK"),
        "max_tokens": ("max_tokens", "maxTokens"),
        "stop_sequences": ("stop_sequences", "stopSequences", "stop"),
        "thinking": ("thinking",),
    }
    for target, source_keys in aliases.items():
        value = _first_present_param(model_params, source_keys)
        if _has_param_value(value):
            payload[target] = value


def _first_present_param(params: dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        if key in params:
            return params[key]
    return None


def _has_param_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return True


def _first_mapped_text(
    payload: dict[str, Any],
    response_mapping: dict[str, Any] | None,
    *mapping_keys: str,
) -> str:
    if not isinstance(response_mapping, dict):
        return ""
    for mapping_key in mapping_keys:
        if mapping_key not in response_mapping:
            continue
        raw_paths = response_mapping[mapping_key]
        paths = [raw_paths] if isinstance(raw_paths, str) else raw_paths
        if not isinstance(paths, list):
            return ""
        for path in paths:
            if not isinstance(path, str):
                continue
            value = read_json_path(payload, path)
            if isinstance(value, str) and value.strip():
                return value
            if isinstance(value, list):
                text = _flatten_content(value, allow_empty=True)
                if text.strip():
                    return text
            if isinstance(value, dict) and isinstance(value.get("text"), str):
                text = value["text"]
                if text.strip():
                    return text
        return ""
    return ""


def _retry_after_seconds(value: Any) -> int | None:
    try:
        return max(0, int(float(str(value).strip())))
    except (TypeError, ValueError):
        return None
