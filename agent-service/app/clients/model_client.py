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
            result = await self._chat_model.ainvoke([_to_langchain_message(message) for message in messages])
        except Exception as exception:
            raise ModelClientError(f"model request failed: {exception}") from exception
        content = getattr(result, "content", None)
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return _flatten_content(content)
        raise ModelClientError("model returned invalid chat completion response")


def _to_langchain_message(message: ChatMessage):
    role = message.role.lower()
    if role == "system":
        return SystemMessage(content=message.content)
    if role == "assistant" or role == "ai":
        return AIMessage(content=message.content)
    return HumanMessage(content=message.content)


def _flatten_content(content: list[Any]) -> str:
    parts: list[str] = []
    for item in content:
        if isinstance(item, str):
            parts.append(item)
        elif isinstance(item, dict) and isinstance(item.get("text"), str):
            parts.append(item["text"])
    if not parts:
        raise ModelClientError("model returned invalid chat completion response")
    return "".join(parts)
