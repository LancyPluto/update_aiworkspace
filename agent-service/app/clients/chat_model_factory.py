from dataclasses import dataclass
from typing import Any

from app.clients.model_name_resolver import resolve_chat_model_name
from app.config import Settings

MINIMAX_OPENAI_COMPATIBLE_BASE_URL = "https://api.minimax.io/v1"
MINIMAX_ANTHROPIC_COMPATIBLE_BASE_URL = "https://api.minimaxi.com/anthropic"
DEEPSEEK_OPENAI_COMPATIBLE_BASE_URL = "https://api.deepseek.com"
QWEN_OPENAI_COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
OPENAI_CHAT_COMPATIBLE_PROVIDERS = {
    "openai",
    "openai_chat",
    "openai_compatible",
    "deepseek",
    "deepseek_compatible",
    "qwen",
    "qwen_compatible",
    "dashscope",
    "bailian",
    "agnes",
    "agnes_chat",
}


class ChatModelProviderError(RuntimeError):
    pass


@dataclass(slots=True)
class MockChatModel:
    provider: str = "mock"

    async def ainvoke(self, messages: list[Any]):
        last = messages[-1].content if messages else ""
        return type("MockMessage", (), {"content": f"Mock agent answer for: {last}"})()

    async def astream(self, messages: list[Any]):
        last = messages[-1].content if messages else ""
        for chunk in ("Mock agent answer for: ", str(last)):
            yield type("MockMessageChunk", (), {"content": chunk})()


class ChatModelFactory:
    def __init__(
        self,
        settings: Settings,
        *,
        chat_openai_cls=None,
        chat_anthropic_cls=None,
        minimax_chat_cls=None,
    ) -> None:
        self.settings = settings
        self.chat_openai_cls = chat_openai_cls
        self.chat_anthropic_cls = chat_anthropic_cls
        self.minimax_chat_cls = minimax_chat_cls

    def create(self):
        provider = self.settings.model_provider.strip().lower()
        if provider == "mock":
            return MockChatModel()
        if is_openai_chat_compatible_provider(provider):
            return self._create_openai_compatible()
        if provider == "anthropic_compatible":
            return self._create_anthropic_compatible()
        if provider == "minimax":
            return self._create_minimax()
        raise ChatModelProviderError(f"unsupported model provider: {self.settings.model_provider}")

    def _create_openai_compatible(self):
        chat_openai_cls = self.chat_openai_cls or _load_chat_openai()
        api_key = self.settings.model_api_key.strip()
        if not api_key:
            raise ChatModelProviderError(
                f"API Key is required for model provider {self.settings.model_provider}"
            )
        base_url = self.settings.model_api_base_url.strip()
        if not base_url and self.settings.model_provider.strip().lower() in {"deepseek", "deepseek_compatible"}:
            base_url = DEEPSEEK_OPENAI_COMPATIBLE_BASE_URL
        if not base_url and self.settings.model_provider.strip().lower() in {"qwen", "qwen_compatible", "dashscope", "bailian"}:
            base_url = QWEN_OPENAI_COMPATIBLE_BASE_URL
        if not base_url and self.settings.model_provider.strip().lower() in {"agnes", "agnes_chat"}:
            base_url = "https://apihub.agnes-ai.com/v1"
        if not base_url and self.settings.model_provider.strip().lower() in {"openai", "openai_chat", "openai_compatible"}:
            base_url = "https://api.openai.com/v1"
        resolved_base_url = base_url.rstrip("/")
        return chat_openai_cls(
            model=resolve_chat_model_name(self.settings.model_name, resolved_base_url, self.settings.model_provider),
            api_key=api_key,
            base_url=resolved_base_url,
            timeout=self.settings.model_timeout_seconds,
        )

    def _create_anthropic_compatible(self):
        chat_anthropic_cls = self.chat_anthropic_cls or _load_chat_anthropic()
        base_url = self.settings.model_api_base_url.strip() or MINIMAX_ANTHROPIC_COMPATIBLE_BASE_URL
        return chat_anthropic_cls(
            model=self.settings.model_name,
            api_key=self.settings.model_api_key,
            base_url=base_url.rstrip("/"),
            timeout=self.settings.model_timeout_seconds,
        )

    def _create_minimax(self):
        if self._should_use_minimax_openai_compatible():
            chat_openai_cls = self.chat_openai_cls or _load_chat_openai()
            base_url = self.settings.model_api_base_url.strip() or MINIMAX_OPENAI_COMPATIBLE_BASE_URL
            return chat_openai_cls(
                model=self.settings.model_name,
                api_key=self.settings.model_api_key,
                base_url=base_url.rstrip("/"),
                timeout=self.settings.model_timeout_seconds,
            )
        if not self.settings.minimax_group_id.strip():
            raise ChatModelProviderError("MINIMAX_GROUP_ID is required when MODEL_PROVIDER=minimax")
        kwargs = {
            "minimax_api_key": self.settings.model_api_key,
            "minimax_group_id": self.settings.minimax_group_id,
        }
        if self.settings.model_api_base_url.strip():
            kwargs["base_url"] = self.settings.model_api_base_url.rstrip("/")
        minimax_chat_cls = self.minimax_chat_cls or _load_minimax_chat()
        return minimax_chat_cls(
            model=self.settings.model_name,
            **kwargs,
        )

    def _should_use_minimax_openai_compatible(self) -> bool:
        base_url = self.settings.model_api_base_url.strip().lower()
        if not base_url:
            return True
        return "chatcompletion_v2" not in base_url


def _load_init_chat_model():
    try:
        from langchain.chat_models import init_chat_model
    except ImportError:
        return None
    return init_chat_model


def is_openai_chat_compatible_provider(provider: str) -> bool:
    return provider.strip().lower() in OPENAI_CHAT_COMPATIBLE_PROVIDERS


def _load_chat_openai():
    try:
        from langchain_openai import ChatOpenAI
    except ImportError as exception:  # pragma: no cover - depends on optional runtime package.
        raise ChatModelProviderError("langchain-openai is required for MODEL_PROVIDER=openai_compatible") from exception
    return ChatOpenAI


def _load_chat_anthropic():
    try:
        from langchain_anthropic import ChatAnthropic
    except ImportError as exception:  # pragma: no cover - depends on optional runtime package.
        raise ChatModelProviderError("langchain-anthropic is required for MODEL_PROVIDER=anthropic_compatible") from exception
    return ChatAnthropic


def _load_minimax_chat():
    try:
        from langchain_community.chat_models import MiniMaxChat
    except ImportError as exception:  # pragma: no cover - depends on optional runtime package.
        raise ChatModelProviderError("langchain-community is required for MODEL_PROVIDER=minimax") from exception
    return MiniMaxChat
