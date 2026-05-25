from collections.abc import AsyncIterator
from typing import Any

import httpx
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.utils.function_calling import convert_to_openai_tool

from app.clients.chat_model_factory import ChatModelFactory, ChatModelProviderError
from app.config import Settings, settings as default_settings
from app.core.schemas import ChatMessage


class ModelClientError(RuntimeError):
    pass


class ModelClient:
    def __init__(self, settings: Settings = default_settings, chat_model=None) -> None:
        self.settings = settings
        self.prompt_tokens = 0
        self.completion_tokens = 0
        try:
            self._chat_model = chat_model or ChatModelFactory(settings).create()
        except ChatModelProviderError as exception:
            raise ModelClientError(str(exception)) from exception

    @property
    def model_name(self) -> str:
        return self.settings.model_name

    @property
    def model_provider(self) -> str:
        return self.settings.model_provider

    @property
    def chat_model(self):
        return self._chat_model

    @property
    def usage(self) -> dict[str, int]:
        return {
            "promptTokens": max(0, self.prompt_tokens),
            "completionTokens": max(0, self.completion_tokens),
        }

    async def chat(self, messages: list[ChatMessage], tools: list[dict[str, Any]] | None = None) -> str:
        safe_tools = _prepare_tools_for_provider(tools, self.settings.model_provider)
        if self._should_use_direct_openai_compatible():
            return await self._chat_openai_compatible_direct(messages, tools=safe_tools)
        kwargs = {}
        if safe_tools:
            kwargs["tools"] = safe_tools
        try:
            result = await self._chat_model.ainvoke(_to_langchain_messages(messages), **kwargs)
        except Exception as exception:
            raise ModelClientError(f"model request failed: {exception}") from exception
        self._record_usage(result)
        return _message_content(result)

    async def chat_stream(self, messages: list[ChatMessage], tools: list[dict[str, Any]] | None = None) -> AsyncIterator[str]:
        if self._should_stream_locally():
            for chunk in _chunk_text(await self.chat(messages, tools=tools)):
                yield chunk
            return
        if not hasattr(self._chat_model, "astream"):
            yield await self.chat(messages, tools=tools)
            return
        kwargs = {}
        safe_tools = _prepare_tools_for_provider(tools, self.settings.model_provider)
        if safe_tools:
            kwargs["tools"] = safe_tools
        try:
            async for chunk in self._chat_model.astream(_to_langchain_messages(messages), **kwargs):
                self._record_usage(chunk)
                text = _message_content(chunk, allow_empty=True)
                if text:
                    yield text
        except Exception as exception:
            raise ModelClientError(f"model stream failed: {_format_exception(exception)}") from exception

    def _should_stream_locally(self) -> bool:
        provider = self.settings.model_provider.strip().lower()
        base_url = self.settings.model_api_base_url.strip().lower()
        return provider == "openai_compatible" and (
            "siliconflow.cn" in base_url or self._should_use_direct_openai_compatible()
        )

    def _should_use_direct_openai_compatible(self) -> bool:
        provider = self.settings.model_provider.strip().lower()
        base_url = self.settings.model_api_base_url.strip().lower()
        return provider == "openai_compatible" and "api-inference.modelscope.cn" in base_url

    async def _chat_openai_compatible_direct(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> str:
        base_url = self.settings.model_api_base_url.rstrip("/")
        payload: dict[str, Any] = {
            "model": self.settings.model_name,
            "messages": [_to_openai_message(message) for message in messages],
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.settings.model_api_key}"}
        try:
            async with httpx.AsyncClient(timeout=self.settings.model_timeout_seconds) as client:
                response = await client.post(f"{base_url}/chat/completions", headers=headers, json=payload)
                response.raise_for_status()
                data = response.json()
        except httpx.HTTPStatusError as exception:
            raise ModelClientError(f"model request failed: {_format_http_error(exception.response)}") from exception
        except Exception as exception:
            raise ModelClientError(f"model request failed: {exception}") from exception
        self._record_openai_usage(data)
        return _openai_compatible_content(data)

    def _record_usage(self, result: Any) -> None:
        usage = getattr(result, "usage_metadata", None)
        if isinstance(usage, dict):
            self.prompt_tokens += _int_usage(usage.get("input_tokens") or usage.get("prompt_tokens"))
            self.completion_tokens += _int_usage(usage.get("output_tokens") or usage.get("completion_tokens"))
            return
        metadata = getattr(result, "response_metadata", None)
        if isinstance(metadata, dict):
            token_usage = metadata.get("token_usage") or metadata.get("usage")
            if isinstance(token_usage, dict):
                self.prompt_tokens += _int_usage(token_usage.get("prompt_tokens") or token_usage.get("input_tokens"))
                self.completion_tokens += _int_usage(token_usage.get("completion_tokens") or token_usage.get("output_tokens"))

    def _record_openai_usage(self, data: Any) -> None:
        if not isinstance(data, dict):
            return
        usage = data.get("usage")
        if not isinstance(usage, dict):
            return
        self.prompt_tokens += _int_usage(usage.get("prompt_tokens") or usage.get("input_tokens"))
        self.completion_tokens += _int_usage(usage.get("completion_tokens") or usage.get("output_tokens"))


def _to_langchain_message(message: ChatMessage):
    role = message.role.lower()
    if role == "system":
        return SystemMessage(content=message.content)
    if role == "assistant" or role == "ai":
        return AIMessage(content=message.content)
    return HumanMessage(content=message.content)


def _to_langchain_messages(messages: list[ChatMessage]):
    return [_to_langchain_message(message) for message in messages]


def _sanitize_tools(tools: list[dict[str, Any]] | None) -> list[dict[str, Any]] | None:
    """Validate and sanitize tool definitions before sending to the model API.

    Some providers (e.g. MiniMax) reject tools with empty function names or
    parameters (error 2013). This function filters out malformed tools and
    returns None if no valid tools remain.
    """
    if not tools:
        return None
    valid = []
    for tool in tools:
        if not isinstance(tool, dict):
            continue
        if tool.get("type") != "function":
            continue
        func = tool.get("function")
        if not isinstance(func, dict):
            continue
        name = func.get("name", "").strip()
        if not name:
            continue
        params = func.get("parameters")
        if not isinstance(params, dict):
            continue
        if params.get("type") != "object" or not params.get("properties"):
            continue
        valid.append(tool)
    return valid if valid else None


def _prepare_tools_for_provider(tools: list[dict[str, Any]] | None, provider: str) -> list[dict[str, Any]] | None:
    safe_tools = _sanitize_tools(tools)
    if not safe_tools:
        return None
    if provider.strip().lower() != "anthropic_compatible":
        return safe_tools
    return [_to_anthropic_tool(tool) for tool in safe_tools]


def _to_anthropic_tool(tool: dict[str, Any]) -> dict[str, Any]:
    func = tool["function"]
    anthropic_tool = {
        "name": func["name"].strip(),
        "input_schema": func["parameters"],
    }
    description = func.get("description")
    if isinstance(description, str) and description.strip():
        anthropic_tool["description"] = description
    return anthropic_tool


def _to_openai_message(message: ChatMessage) -> dict[str, str]:
    role = message.role.lower()
    if role == "ai":
        role = "assistant"
    if role not in {"system", "assistant", "user", "tool"}:
        role = "user"
    return {"role": role, "content": message.content}


def _openai_compatible_content(data: Any) -> str:
    if not isinstance(data, dict):
        raise ModelClientError("model returned non-object chat completion response")
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        error = data.get("error")
        if isinstance(error, dict) and error.get("message"):
            raise ModelClientError(f"model returned error: {error.get('message')}")
        raise ModelClientError("model returned empty chat completion choices")
    first = choices[0]
    if not isinstance(first, dict):
        raise ModelClientError("model returned invalid chat completion choice")
    message = first.get("message")
    if isinstance(message, dict):
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return _flatten_content(content)
    text = first.get("text")
    if isinstance(text, str):
        return text
    raise ModelClientError("model returned invalid chat completion message")


def _int_usage(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def _message_content(message, *, allow_empty: bool = False) -> str:
    content = getattr(message, "content", None)
    if isinstance(content, str):
        if content or allow_empty:
            return content
    if isinstance(content, list):
        return _flatten_content(content, allow_empty=allow_empty)
    if allow_empty:
        return ""
    raise ModelClientError("model returned invalid chat completion response")


def _flatten_content(content: list[Any], *, allow_empty: bool = False) -> str:
    parts: list[str] = []
    for item in content:
        if isinstance(item, str):
            parts.append(item)
        elif isinstance(item, dict):
            if isinstance(item.get("text"), str):
                parts.append(item["text"])
            elif item.get("type") in ("thinking", "reasoning") or "thinking" in item or "reasoning" in item:
                continue
    if not parts:
        if allow_empty:
            return ""
        raise ModelClientError("model returned invalid chat completion response")
    return "".join(parts)


def _has_only_non_text_blocks(content: list[Any]) -> bool:
    for item in content:
        if isinstance(item, str) or (isinstance(item, dict) and isinstance(item.get("text"), str)):
            return False
    return True


def _extract_content(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        if not content or _has_only_non_text_blocks(content):
            return ""
        return _flatten_content(content)
    raise ModelClientError("model returned invalid chat completion response")


def _chunk_text(value: str, size: int = 80) -> list[str]:
    return [value[index : index + size] for index in range(0, len(value), size)] or [""]


def _format_exception(exception: Exception) -> str:
    message = str(exception).strip()
    if message:
        return message
    return exception.__class__.__name__


def _format_http_error(response: httpx.Response) -> str:
    try:
        data = response.json()
    except ValueError:
        text = response.text.strip()
        return f"status={response.status_code}, body={text[:300]}"
    if isinstance(data, dict):
        error = data.get("error")
        if isinstance(error, dict) and error.get("message"):
            return f"status={response.status_code}, message={error.get('message')}"
    return f"status={response.status_code}, body={str(data)[:300]}"
