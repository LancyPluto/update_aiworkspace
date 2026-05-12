import pytest

from app.clients import chat_model_factory
from app.clients.chat_model_factory import ChatModelFactory, ChatModelProviderError
from app.config import Settings


class FakeChatOpenAI:
    calls = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        FakeChatOpenAI.calls.append(kwargs)


class FakeChatAnthropic:
    calls = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        FakeChatAnthropic.calls.append(kwargs)


class FakeMiniMaxChat:
    calls = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        FakeMiniMaxChat.calls.append(kwargs)


def test_factory_prefers_langchain_init_chat_model_for_minimax(monkeypatch):
    FakeChatOpenAI.calls = []
    factory = ChatModelFactory(
        Settings(
            model_provider="minimax",
            model_api_base_url="https://api.minimax.io/v1",
            model_api_key="key",
            model_name="MiniMax-M2.7",
            model_timeout_seconds=30,
        ),
        chat_openai_cls=FakeChatOpenAI,
    )

    model = factory.create()

    assert isinstance(model, FakeChatOpenAI)
    assert FakeChatOpenAI.calls == [
        {
            "model": "MiniMax-M2.7",
            "api_key": "key",
            "base_url": "https://api.minimax.io/v1",
            "timeout": 30,
        }
    ]


def test_factory_creates_mock_chat_model():
    model = ChatModelFactory(Settings(model_provider="mock")).create()

    assert model.provider == "mock"


def test_factory_creates_openai_compatible_chat_model_from_settings():
    FakeChatOpenAI.calls = []
    factory = ChatModelFactory(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://api.minimax.io/v1",
            model_api_key="key",
            model_name="MiniMax-M2.7",
            model_timeout_seconds=45,
        ),
        chat_openai_cls=FakeChatOpenAI,
    )

    model = factory.create()

    assert isinstance(model, FakeChatOpenAI)
    assert FakeChatOpenAI.calls[0] == {
        "model": "MiniMax-M2.7",
        "api_key": "key",
        "base_url": "https://api.minimax.io/v1",
        "timeout": 45,
    }


def test_factory_creates_minimax_chat_model_from_settings():
    FakeMiniMaxChat.calls = []
    factory = ChatModelFactory(
        Settings(
            model_provider="minimax",
            model_api_base_url="https://api.minimaxi.com/v1/text/chatcompletion_v2",
            model_api_key="key",
            minimax_group_id="group",
            model_name="MiniMax-M2.7",
        ),
        minimax_chat_cls=FakeMiniMaxChat,
    )

    model = factory.create()

    assert isinstance(model, FakeMiniMaxChat)
    assert FakeMiniMaxChat.calls[0] == {
        "model": "MiniMax-M2.7",
        "minimax_api_key": "key",
        "minimax_group_id": "group",
        "base_url": "https://api.minimaxi.com/v1/text/chatcompletion_v2",
    }


def test_factory_uses_openai_compatible_minimax_without_group_id():
    FakeChatOpenAI.calls = []
    factory = ChatModelFactory(
        Settings(
            model_provider="minimax",
            model_api_base_url="",
            model_api_key="key",
            minimax_group_id="",
            model_name="MiniMax-M2.7",
            model_timeout_seconds=45,
        ),
        chat_openai_cls=FakeChatOpenAI,
    )

    model = factory.create()

    assert isinstance(model, FakeChatOpenAI)
    assert FakeChatOpenAI.calls[0] == {
        "model": "MiniMax-M2.7",
        "api_key": "key",
        "base_url": "https://api.minimax.io/v1",
        "timeout": 45,
    }


def test_factory_creates_anthropic_compatible_chat_model_from_settings():
    FakeChatAnthropic.calls = []
    factory = ChatModelFactory(
        Settings(
            model_provider="anthropic_compatible",
            model_api_base_url="https://api.minimax.io/anthropic",
            model_api_key="key",
            model_name="MiniMax-M2.7",
            model_timeout_seconds=45,
        ),
        chat_anthropic_cls=FakeChatAnthropic,
    )

    model = factory.create()

    assert isinstance(model, FakeChatAnthropic)
    assert FakeChatAnthropic.calls[0] == {
        "model": "MiniMax-M2.7",
        "api_key": "key",
        "base_url": "https://api.minimax.io/anthropic",
        "timeout": 45,
    }


def test_minimax_requires_group_id():
    factory = ChatModelFactory(
        Settings(
            model_provider="minimax",
            model_api_base_url="https://api.minimaxi.com/v1/text/chatcompletion_v2",
            minimax_group_id="",
        )
    )

    with pytest.raises(ChatModelProviderError, match="MINIMAX_GROUP_ID"):
        factory.create()


def test_unknown_provider_raises_clear_error():
    factory = ChatModelFactory(Settings(model_provider="unknown"))

    with pytest.raises(ChatModelProviderError, match="unsupported model provider"):
        factory.create()
