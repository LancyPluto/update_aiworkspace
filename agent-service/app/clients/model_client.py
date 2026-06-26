from collections.abc import AsyncIterator
from dataclasses import dataclass, field
import asyncio
import base64
import ipaddress
import json
import os
from typing import Any, Awaitable, Callable, TypeVar
from urllib.parse import urlparse

import httpx
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from app.clients.chat_model_factory import ChatModelFactory, ChatModelProviderError
from app.clients.model_name_resolver import resolve_chat_model_name
from app.clients.volcengine_model import normalize_volcengine_openai_base_url
from app.config import Settings, settings as default_settings
from app.core.schemas import ChatMessage


class ModelClientError(RuntimeError):
    pass


_T = TypeVar("_T")
_RETRY_BACKOFF_SECONDS = (0.5, 1.5)


@dataclass(slots=True)
class ChatToolCall:
    id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class ChatTurnResult:
    content: str = ""
    reasoning: str = ""
    tool_calls: list[ChatToolCall] = field(default_factory=list)
    finish_reason: str | None = None
    raw: Any | None = None


@dataclass(slots=True)
class StreamPart:
    kind: str
    text: str


class ModelClient:
    def __init__(self, settings: Settings = default_settings, chat_model=None) -> None:
        self.settings = settings
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self._uses_injected_chat_model = chat_model is not None
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
        if tools:
            turn = await self.chat_turn(messages, tools=tools)
            return turn.content
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

    async def chat_turn(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> ChatTurnResult:
        safe_tools = _prepare_tools_for_provider(tools, self.settings.model_provider)
        if self._should_use_direct_openai_compatible():
            return await self._chat_turn_openai_compatible_direct(
                messages,
                tools=safe_tools,
                tool_choice=tool_choice,
            )
        kwargs: dict[str, Any] = {}
        if safe_tools:
            kwargs["tools"] = safe_tools
        if tool_choice is not None and self.settings.model_provider.strip().lower() != "anthropic_compatible":
            kwargs["tool_choice"] = tool_choice
        try:
            result = await self._chat_model.ainvoke(_to_langchain_messages(messages), **kwargs)
        except Exception as exception:
            raise ModelClientError(f"model request failed: {exception}") from exception
        self._record_usage(result)
        return _langchain_turn_result(result)

    async def chat_stream_parts(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> AsyncIterator[StreamPart]:
        if not self._uses_injected_chat_model and self._should_use_direct_openai_stream():
            async for part in self._chat_openai_compatible_stream_parts_direct(messages, tools=tools):
                yield part
            return
        async for chunk in self.chat_stream(messages, tools=tools):
            if chunk:
                yield StreamPart(kind="text", text=chunk)

    async def chat_stream(self, messages: list[ChatMessage], tools: list[dict[str, Any]] | None = None) -> AsyncIterator[str]:
        if not self._uses_injected_chat_model and self._should_use_direct_openai_stream():
            async for chunk in self._chat_openai_compatible_stream_direct(messages, tools=tools):
                yield chunk
            return
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
        if provider == "openai_compatible" and "api-inference.modelscope.cn" in base_url:
            return True
        if self._uses_injected_chat_model:
            return False
        return self._should_use_direct_openai_stream()

    def _should_use_direct_openai_stream(self) -> bool:
        provider = self.settings.model_provider.strip().lower()
        base_url = self.settings.model_api_base_url.strip().lower()
        if provider in {"deepseek", "deepseek_compatible", "qwen", "qwen_compatible", "dashscope", "bailian"}:
            return True
        if provider == "openai_compatible":
            return bool(base_url) and not any(
                marker in base_url
                for marker in (
                    "anthropic",
                    "mineru.net",
                    "mineru",
                )
            )
        if provider == "minimax" and "chatcompletion_v2" not in base_url:
            return True
        return False

    async def _chat_openai_compatible_direct(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> str:
        base_url = normalize_volcengine_openai_base_url(self.settings.model_api_base_url.rstrip("/"))
        if not base_url and self.settings.model_provider.strip().lower() in {"deepseek", "deepseek_compatible"}:
            base_url = "https://api.deepseek.com"
        if not base_url and self.settings.model_provider.strip().lower() in {"qwen", "qwen_compatible", "dashscope", "bailian"}:
            base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        payload: dict[str, Any] = {
            "model": _resolved_model_name(self.settings, base_url),
            "messages": await _to_openai_messages_for_provider(messages, self.settings),
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
        headers = {"Authorization": f"Bearer {self.settings.model_api_key}"}

        async def _post_once() -> dict[str, Any]:
            async with _outbound_http_client(self.settings.model_timeout_seconds) as client:
                response = await client.post(f"{base_url}/chat/completions", headers=headers, json=payload)
                response.raise_for_status()
                return response.json()

        data = await _retry_openai_compatible_call(_post_once, self.settings.model_connect_retry_count)
        self._record_openai_usage(data)
        return _openai_compatible_content(data)

    async def _chat_turn_openai_compatible_direct(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> ChatTurnResult:
        base_url = normalize_volcengine_openai_base_url(self.settings.model_api_base_url.rstrip("/"))
        if not base_url and self.settings.model_provider.strip().lower() in {"deepseek", "deepseek_compatible"}:
            base_url = "https://api.deepseek.com"
        if not base_url and self.settings.model_provider.strip().lower() in {"qwen", "qwen_compatible", "dashscope", "bailian"}:
            base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        payload: dict[str, Any] = {
            "model": _resolved_model_name(self.settings, base_url),
            "messages": await _to_openai_messages_for_provider(messages, self.settings),
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
        if tool_choice is not None:
            payload["tool_choice"] = tool_choice
        headers = {"Authorization": f"Bearer {self.settings.model_api_key}"}

        async def _post_once() -> dict[str, Any]:
            async with _outbound_http_client(self.settings.model_timeout_seconds) as client:
                response = await client.post(f"{base_url}/chat/completions", headers=headers, json=payload)
                response.raise_for_status()
                return response.json()

        data = await _retry_openai_compatible_call(_post_once, self.settings.model_connect_retry_count)
        self._record_openai_usage(data)
        return _openai_compatible_turn_result(data)

    async def _chat_openai_compatible_stream_parts_direct(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> AsyncIterator[StreamPart]:
        async for chunk in self._chat_openai_compatible_stream_direct(messages, tools=tools, emit_parts=True):
            if isinstance(chunk, StreamPart):
                yield chunk

    async def _chat_openai_compatible_stream_direct(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
        *,
        emit_parts: bool = False,
    ) -> AsyncIterator[str | StreamPart]:
        base_url = normalize_volcengine_openai_base_url(self.settings.model_api_base_url.rstrip("/"))
        if not base_url and self.settings.model_provider.strip().lower() in {"deepseek", "deepseek_compatible"}:
            base_url = "https://api.deepseek.com"
        if not base_url and self.settings.model_provider.strip().lower() in {"qwen", "qwen_compatible", "dashscope", "bailian"}:
            base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        payload: dict[str, Any] = {
            "model": _resolved_model_name(self.settings, base_url),
            "messages": await _to_openai_messages_for_provider(messages, self.settings),
            "stream": True,
        }
        safe_tools = _prepare_tools_for_provider(tools, self.settings.model_provider)
        if safe_tools:
            payload["tools"] = safe_tools
        headers = {
            "Authorization": f"Bearer {self.settings.model_api_key}",
            "Accept": "text/event-stream",
        }
        try:
            async with _outbound_http_client(self.settings.model_timeout_seconds) as client:
                async with client.stream("POST", f"{base_url}/chat/completions", headers=headers, json=payload) as response:
                    if response.status_code >= 400:
                        body = (await response.aread()).decode("utf-8", errors="replace")
                        raise ModelClientError(
                            f"model stream failed: status={response.status_code}, body={body[:300]}"
                        )
                    async for line in response.aiter_lines():
                        if not line:
                            continue
                        line = line.strip()
                        if not line.startswith("data:"):
                            continue
                        data_text = line[5:].strip()
                        if data_text == "[DONE]":
                            break
                        try:
                            data = json.loads(data_text)
                        except ValueError:
                            continue
                        self._record_openai_usage(data)
                        if emit_parts:
                            for part in _openai_compatible_stream_parts(data):
                                if part.text:
                                    yield part
                        else:
                            delta = _openai_compatible_stream_delta(data)
                            if delta:
                                yield delta
        except ModelClientError:
            raise
        except Exception as exception:
            raise ModelClientError(f"model stream failed: {_format_connection_error(exception)}") from exception

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
        return SystemMessage(content=_langchain_content(message.content))
    if role == "tool":
        return ToolMessage(content=_content_text(message.content), tool_call_id=message.toolCallId or "")
    if role == "assistant" or role == "ai":
        if message.toolCalls:
            return AIMessage(content=_content_text(message.content), tool_calls=[_to_langchain_tool_call(call) for call in message.toolCalls])
        return AIMessage(content=_langchain_content(message.content))
    return HumanMessage(content=_langchain_content(message.content))


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


def _to_openai_message(message: ChatMessage) -> dict[str, Any]:
    role = message.role.lower()
    if role == "ai":
        role = "assistant"
    if role not in {"system", "assistant", "user", "tool"}:
        role = "user"
    payload: dict[str, Any] = {"role": role, "content": _openai_content(message.content)}
    if role == "tool" and message.toolCallId:
        payload["tool_call_id"] = message.toolCallId
    if role == "assistant" and message.toolCalls:
        payload["tool_calls"] = [_to_openai_tool_call(call) for call in message.toolCalls]
    if message.name:
        payload["name"] = message.name
    return payload


async def _to_openai_messages_for_provider(messages: list[ChatMessage], settings: Settings) -> list[dict[str, Any]]:
    payload = [_to_openai_message(message) for message in messages]
    if not _should_inline_private_image_urls(settings):
        return payload
    return await _inline_private_image_urls(payload, settings)


def _should_inline_private_image_urls(settings: Settings) -> bool:
    provider = settings.model_provider.strip().lower()
    base_url = settings.model_api_base_url.strip().lower()
    return provider in {"qwen", "qwen_compatible", "dashscope", "bailian"} or (
        "dashscope.aliyuncs.com" in base_url or "maas.aliyuncs.com" in base_url
    )


async def _inline_private_image_urls(messages: list[dict[str, Any]], settings: Settings) -> list[dict[str, Any]]:
    backend_host = _url_host(settings.backend_internal_base_url)
    converted: list[dict[str, Any]] = []
    for message in messages:
        content = message.get("content")
        if not isinstance(content, list):
            converted.append(message)
            continue
        parts: list[dict[str, Any]] = []
        changed = False
        for part in content:
            if not isinstance(part, dict) or part.get("type") != "image_url":
                parts.append(part)
                continue
            image_url = part.get("image_url")
            if not isinstance(image_url, dict):
                parts.append(part)
                continue
            url = image_url.get("url")
            if not isinstance(url, str) or not _should_inline_image_url(url, backend_host):
                parts.append(part)
                continue
            data_url = await _download_image_as_data_url(url, settings.model_timeout_seconds)
            next_part = dict(part)
            next_image_url = dict(image_url)
            next_image_url["url"] = data_url
            next_part["image_url"] = next_image_url
            parts.append(next_part)
            changed = True
        converted.append({**message, "content": parts} if changed else message)
    return converted


def _should_inline_image_url(url: str, backend_host: str) -> bool:
    value = (url or "").strip()
    if not value or value.startswith("data:"):
        return False
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"}:
        return False
    host = (parsed.hostname or "").strip().lower()
    if not host:
        return False
    if backend_host and host == backend_host:
        return True
    if host in {"localhost", "backend", "host.docker.internal"}:
        return True
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        return False


async def _download_image_as_data_url(url: str, timeout_seconds: int | float) -> str:
    timeout = min(float(timeout_seconds or 30), 30.0)
    async with _outbound_http_client(timeout) as client:
        response = await client.get(url, headers={"Accept": "image/*"})
        response.raise_for_status()
        content_type = _image_content_type(response.headers.get("content-type"), url)
        encoded = base64.b64encode(response.content).decode("ascii")
        return f"data:{content_type};base64,{encoded}"


def _image_content_type(header: str | None, url: str) -> str:
    content_type = (header or "").split(";", 1)[0].strip().lower()
    if content_type.startswith("image/"):
        return content_type
    path = urlparse(url).path.lower()
    if path.endswith(".png"):
        return "image/png"
    if path.endswith((".jpg", ".jpeg")):
        return "image/jpeg"
    if path.endswith(".webp"):
        return "image/webp"
    if path.endswith(".gif"):
        return "image/gif"
    if path.endswith(".bmp"):
        return "image/bmp"
    if path.endswith((".heic", ".heif")):
        return "image/heic"
    return "image/jpeg"


def _url_host(url: str | None) -> str:
    try:
        return (urlparse(url or "").hostname or "").strip().lower()
    except Exception:
        return ""


def _openai_content(content: str | list[dict[str, Any]] | None) -> str | list[dict[str, Any]]:
    if isinstance(content, list):
        return content
    return content or ""


def _langchain_content(content: str | list[dict[str, Any]] | None) -> str | list[dict[str, Any]]:
    if isinstance(content, list):
        return content
    return content or ""


def _content_text(content: str | list[dict[str, Any]] | None) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    texts: list[str] = []
    for part in content:
        if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
            texts.append(part["text"])
    return "\n".join(texts)


def _to_langchain_tool_call(call: dict[str, Any]) -> dict[str, Any]:
    function = call.get("function") if isinstance(call.get("function"), dict) else {}
    name = call.get("name") or function.get("name") or ""
    args = call.get("args") or call.get("arguments") or _parse_json_object(function.get("arguments"))
    return {"id": str(call.get("id") or ""), "name": str(name), "args": args if isinstance(args, dict) else {}}


def _to_openai_tool_call(call: dict[str, Any]) -> dict[str, Any]:
    function = call.get("function") if isinstance(call.get("function"), dict) else {}
    name = call.get("name") or function.get("name") or ""
    args = call.get("args") or call.get("arguments") or _parse_json_object(function.get("arguments"))
    return {
        "id": str(call.get("id") or ""),
        "type": "function",
        "function": {
            "name": str(name),
            "arguments": json_dumps(args if isinstance(args, dict) else {}),
        },
    }


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


def _openai_compatible_turn_result(data: Any) -> ChatTurnResult:
    if not isinstance(data, dict):
        raise ModelClientError("model returned non-object chat completion response")
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ModelClientError("model returned empty chat completion choices")
    first = choices[0]
    if not isinstance(first, dict):
        raise ModelClientError("model returned invalid chat completion choice")
    message = first.get("message")
    if not isinstance(message, dict):
        raise ModelClientError("model returned invalid chat completion message")
    content = _extract_content(message.get("content")) if message.get("content") is not None else ""
    reasoning = _extract_reasoning_content(message)
    tool_calls = _extract_openai_tool_calls(message.get("tool_calls"))
    return ChatTurnResult(
        content=content,
        reasoning=reasoning,
        tool_calls=tool_calls,
        finish_reason=first.get("finish_reason") if isinstance(first.get("finish_reason"), str) else None,
        raw=data,
    )


def _openai_compatible_stream_parts(data: Any) -> list[StreamPart]:
    if not isinstance(data, dict):
        return []
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        return []
    first = choices[0]
    if not isinstance(first, dict):
        return []
    delta = first.get("delta")
    if not isinstance(delta, dict):
        return []
    parts: list[StreamPart] = []
    reasoning = delta.get("reasoning_content") or delta.get("reasoning")
    if isinstance(reasoning, str) and reasoning:
        parts.append(StreamPart(kind="reasoning", text=reasoning))
    content = delta.get("content")
    if isinstance(content, str) and content:
        parts.append(StreamPart(kind="text", text=content))
    elif isinstance(content, list):
        text, reasoning_text = _split_content_blocks(content)
        if reasoning_text:
            parts.append(StreamPart(kind="reasoning", text=reasoning_text))
        if text:
            parts.append(StreamPart(kind="text", text=text))
    return parts


def _openai_compatible_stream_delta(data: Any) -> str:
    if not isinstance(data, dict):
        return ""
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        return ""
    first = choices[0]
    if not isinstance(first, dict):
        return ""
    delta = first.get("delta")
    if isinstance(delta, dict):
        content = delta.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return _flatten_content(content, allow_empty=True)
    text = first.get("text")
    if isinstance(text, str):
        return text
    return ""


def _langchain_turn_result(result: Any) -> ChatTurnResult:
    return ChatTurnResult(
        content=_message_content(result, allow_empty=True),
        tool_calls=_extract_langchain_tool_calls(result),
        finish_reason=_langchain_finish_reason(result),
        raw=result,
    )


def _extract_openai_tool_calls(raw_tool_calls: Any) -> list[ChatToolCall]:
    if not isinstance(raw_tool_calls, list):
        return []
    calls: list[ChatToolCall] = []
    for index, call in enumerate(raw_tool_calls):
        if not isinstance(call, dict):
            continue
        function = call.get("function")
        if not isinstance(function, dict):
            continue
        name = function.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        calls.append(
            ChatToolCall(
                id=str(call.get("id") or f"tool-call-{index}"),
                name=name.strip(),
                arguments=_parse_json_object(function.get("arguments")),
                raw=call,
            )
        )
    return calls


def _extract_langchain_tool_calls(result: Any) -> list[ChatToolCall]:
    raw_calls = getattr(result, "tool_calls", None)
    calls: list[ChatToolCall] = []
    if isinstance(raw_calls, list):
        for index, call in enumerate(raw_calls):
            if not isinstance(call, dict):
                continue
            name = call.get("name")
            if not isinstance(name, str) or not name.strip():
                continue
            args = call.get("args") if isinstance(call.get("args"), dict) else call.get("arguments")
            calls.append(
                ChatToolCall(
                    id=str(call.get("id") or f"tool-call-{index}"),
                    name=name.strip(),
                    arguments=args if isinstance(args, dict) else _parse_json_object(args),
                    raw=call,
                )
            )
    metadata = getattr(result, "additional_kwargs", None)
    if isinstance(metadata, dict):
        calls.extend(_extract_openai_tool_calls(metadata.get("tool_calls")))
    seen: set[str] = set()
    unique: list[ChatToolCall] = []
    for call in calls:
        key = call.id or call.name
        if key in seen:
            continue
        seen.add(key)
        unique.append(call)
    return unique


def _langchain_finish_reason(result: Any) -> str | None:
    metadata = getattr(result, "response_metadata", None)
    if isinstance(metadata, dict):
        reason = metadata.get("finish_reason") or metadata.get("stop_reason")
        if isinstance(reason, str):
            return reason
    return None


def _parse_json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value.strip():
        return {}
    try:
        parsed = json.loads(value)
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


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


def _split_content_blocks(content: list[Any]) -> tuple[str, str]:
    text_parts: list[str] = []
    reasoning_parts: list[str] = []
    for item in content:
        if isinstance(item, str):
            text_parts.append(item)
            continue
        if not isinstance(item, dict):
            continue
        block_type = str(item.get("type") or "").lower()
        if block_type in {"thinking", "reasoning"} or "thinking" in item or "reasoning" in item:
            value = item.get("text") or item.get("thinking") or item.get("reasoning")
            if isinstance(value, str) and value:
                reasoning_parts.append(value)
            continue
        if isinstance(item.get("text"), str):
            text_parts.append(item["text"])
    return "".join(text_parts), "".join(reasoning_parts)


def _flatten_content(content: list[Any], *, allow_empty: bool = False) -> str:
    text, _ = _split_content_blocks(content)
    if not text:
        if allow_empty:
            return ""
        raise ModelClientError("model returned invalid chat completion response")
    return text


def _extract_reasoning_content(message: dict[str, Any]) -> str:
    reasoning = message.get("reasoning_content") or message.get("reasoning")
    if isinstance(reasoning, str):
        return reasoning
    content = message.get("content")
    if isinstance(content, list):
        _, reasoning_text = _split_content_blocks(content)
        return reasoning_text
    return ""


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


def _resolved_model_name(settings: Settings, base_url: str) -> str:
    return resolve_chat_model_name(settings.model_name, base_url, settings.model_provider)


async def _retry_openai_compatible_call(
    operation: Callable[[], Awaitable[_T]],
    retry_count: int,
) -> _T:
    attempts = max(1, int(retry_count) + 1)
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            return await operation()
        except httpx.HTTPStatusError as exception:
            raise ModelClientError(f"model request failed: {_format_http_error(exception.response)}") from exception
        except Exception as exception:
            last_error = exception
            if attempt >= attempts - 1 or not _is_retryable_connection_error(exception):
                break
            await asyncio.sleep(_RETRY_BACKOFF_SECONDS[min(attempt, len(_RETRY_BACKOFF_SECONDS) - 1)])
    assert last_error is not None
    raise ModelClientError(f"model request failed: {_format_connection_error(last_error)}") from last_error


def _is_retryable_connection_error(exception: Exception) -> bool:
    if isinstance(exception, httpx.HTTPStatusError):
        return False
    if isinstance(exception, (httpx.ConnectError, httpx.ConnectTimeout, httpx.ReadTimeout, httpx.WriteTimeout, httpx.PoolTimeout)):
        return True
    message = _format_exception(exception).lower()
    return any(
        marker in message
        for marker in (
            "connection error",
            "connecterror",
            "connect timeout",
            "all connection attempts failed",
            "read timeout",
            "write timeout",
        )
    )


def _outbound_http_client(timeout: int | float) -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=timeout)


def _format_connection_error(exception: Exception) -> str:
    message = _format_exception(exception)
    lowered = message.lower()
    if "connection error" in lowered or "connecterror" in lowered or "connect timeout" in lowered:
        proxy = os.getenv("HTTPS_PROXY") or os.getenv("HTTP_PROXY") or ""
        hint = (
            "容器出站网络异常（常见于 Clash TUN/fake-ip 下代理未启动或 deploy/.env 中 HTTP_PROXY 不可达）。"
            "请确认本机 Clash 已开启且 host.docker.internal:7897 可访问，或临时关闭 fake-ip 后重试。"
        )
        if proxy:
            return f"{message}；当前代理={proxy}；{hint}"
        return f"{message}；{hint}"
    return message


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
