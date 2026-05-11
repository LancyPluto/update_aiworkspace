from dataclasses import dataclass
from typing import Any

from app.config import Settings


class ChatModelProviderError(RuntimeError):
    pass


@dataclass(slots=True)
class MockChatModel:
    provider: str = "mock"

    async def ainvoke(self, messages: list[Any]):
        last = messages[-1].content if messages else ""
        return type("MockMessage", (), {"content": f"Mock agent answer for: {last}"})()


class ChatModelFactory:
    def __init__(
        self,
        settings: Settings,
        *,
        chat_openai_cls=None,
        minimax_chat_cls=None,
    ) -> None:
        self.settings = settings
        self.chat_openai_cls = chat_openai_cls
        self.minimax_chat_cls = minimax_chat_cls

    def create(self):
        provider = self.settings.model_provider.strip().lower()
        if provider == "mock":
            return MockChatModel()
        if provider == "openai_compatible":
            return self._create_openai_compatible()
        if provider == "minimax":
            return self._create_minimax()
        raise ChatModelProviderError(f"unsupported model provider: {self.settings.model_provider}")

    def _create_openai_compatible(self):
        chat_openai_cls = self.chat_openai_cls or _load_chat_openai()
        return chat_openai_cls(
            model=self.settings.model_name,
            api_key=self.settings.model_api_key,
            base_url=self.settings.model_api_base_url.rstrip("/"),
            timeout=self.settings.model_timeout_seconds,
        )

    def _create_minimax(self):
        if not self.settings.minimax_group_id.strip():
            raise ChatModelProviderError("MINIMAX_GROUP_ID is required when MODEL_PROVIDER=minimax")
        minimax_chat_cls = self.minimax_chat_cls or _load_minimax_chat()
        return minimax_chat_cls(
            model=self.settings.model_name,
            minimax_api_key=self.settings.model_api_key,
            minimax_group_id=self.settings.minimax_group_id,
        )


def _load_chat_openai():
    try:
        from langchain_openai import ChatOpenAI
    except ImportError as exception:  # pragma: no cover - depends on optional runtime package.
        raise ChatModelProviderError("langchain-openai is required for MODEL_PROVIDER=openai_compatible") from exception
    return ChatOpenAI


def _load_minimax_chat():
    try:
        from langchain_community.chat_models import MiniMaxChat
    except ImportError as exception:  # pragma: no cover - depends on optional runtime package.
        raise ChatModelProviderError("langchain-community is required for MODEL_PROVIDER=minimax") from exception
    return MiniMaxChat
