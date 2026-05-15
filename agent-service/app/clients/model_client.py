from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from app.clients.chat_model_factory import ChatModelFactory, ChatModelProviderError
from app.config import Settings, settings as default_settings
from app.core.schemas import ChatMessage


class ModelClientError(RuntimeError):
    pass


class ModelClient:
    def __init__(self, settings: Settings = default_settings, chat_model=None) -> None:
        self.settings = settings
        try:
            self._chat_model = chat_model or ChatModelFactory(settings).create()
        except ChatModelProviderError as exception:
            raise ModelClientError(str(exception)) from exception

    @property
    def model_name(self) -> str:
        return self.settings.model_name

    @property
    def chat_model(self):
        return self._chat_model

    async def chat(self, messages: list[ChatMessage]) -> str:
        try:
            result = await self._chat_model.ainvoke(_to_langchain_messages(messages))
        except Exception as exception:
            raise ModelClientError(f"model request failed: {exception}") from exception
        return _message_content(result)

    async def chat_stream(self, messages: list[ChatMessage]) -> AsyncIterator[str]:
        if not hasattr(self._chat_model, "astream"):
            yield await self.chat(messages)
            return
        try:
            async for chunk in self._chat_model.astream(_to_langchain_messages(messages)):
                text = _message_content(chunk, allow_empty=True)
                if text:
                    yield text
        except Exception as exception:
            raise ModelClientError(f"model stream failed: {exception}") from exception


def _to_langchain_message(message: ChatMessage):
    role = message.role.lower()
    if role == "system":
        return SystemMessage(content=message.content)
    if role == "assistant" or role == "ai":
        return AIMessage(content=message.content)
    return HumanMessage(content=message.content)


def _to_langchain_messages(messages: list[ChatMessage]):
    return [_to_langchain_message(message) for message in messages]


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
        elif isinstance(item, dict) and isinstance(item.get("text"), str):
            parts.append(item["text"])
    if not parts:
        if allow_empty:
            return ""
        raise ModelClientError("model returned invalid chat completion response")
    return "".join(parts)
