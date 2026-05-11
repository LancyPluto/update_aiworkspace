import pytest

from app.clients.model_client import ModelClient, ModelClientError
from app.config import Settings
from app.core.schemas import ChatMessage


class FakeLangChainMessage:
    def __init__(self, content: str):
        self.content = content


class FakeLangChainModel:
    def __init__(self, response):
        self.response = response
        self.messages = []

    async def ainvoke(self, messages):
        self.messages.append(messages)
        if isinstance(self.response, Exception):
            raise self.response
        return FakeLangChainMessage(self.response)


@pytest.mark.asyncio
async def test_mock_model_returns_deterministic_answer():
    client = ModelClient(Settings(model_provider="mock"))

    answer = await client.chat([ChatMessage(role="user", content="hello")])

    assert "hello" in answer


@pytest.mark.asyncio
async def test_model_client_invokes_langchain_chat_model():
    langchain_model = FakeLangChainModel("provider answer")
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=langchain_model,
    )

    answer = await client.chat([ChatMessage(role="user", content="hello")])

    assert answer == "provider answer"
    assert langchain_model.messages[0][0].type == "human"
    assert langchain_model.messages[0][0].content == "hello"


@pytest.mark.asyncio
async def test_model_error_maps_to_model_client_error():
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=FakeLangChainModel(RuntimeError("boom")),
    )

    with pytest.raises(ModelClientError):
        await client.chat([ChatMessage(role="user", content="hello")])


def test_model_client_exposes_langchain_chat_model_for_agent_runtimes():
    langchain_model = FakeLangChainModel("provider answer")
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=langchain_model,
    )

    assert client.chat_model is langchain_model
