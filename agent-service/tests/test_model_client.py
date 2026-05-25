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
        self.stream_calls = 0

    async def ainvoke(self, messages):
        self.messages.append(messages)
        if isinstance(self.response, Exception):
            raise self.response
        return FakeLangChainMessage(self.response)

    async def astream(self, messages):
        self.stream_calls += 1
        self.messages.append(messages)
        for chunk in self.response:
            yield FakeLangChainMessage(chunk)


class FakeAsyncResponse:
    status_code = 200

    def __init__(self, payload):
        self.payload = payload
        self.text = str(payload)

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeAsyncHttpClient:
    requests = []

    def __init__(self, timeout=None):
        self.timeout = timeout

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None

    async def post(self, url, headers=None, json=None):
        FakeAsyncHttpClient.requests.append({"url": url, "headers": headers, "json": json, "timeout": self.timeout})
        return FakeAsyncResponse({"choices": [{"message": {"content": "modelscope ready"}}]})


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


@pytest.mark.asyncio
async def test_model_client_streams_langchain_chat_chunks():
    langchain_model = FakeLangChainModel(["hello ", "world"])
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=langchain_model,
    )

    chunks = [chunk async for chunk in client.chat_stream([ChatMessage(role="user", content="hello")])]

    assert chunks == ["hello ", "world"]
    assert langchain_model.messages[0][0].type == "human"


@pytest.mark.asyncio
async def test_model_client_streams_siliconflow_locally():
    langchain_model = FakeLangChainModel("provider answer")
    client = ModelClient(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://api.siliconflow.cn/v1",
            model_api_key="key",
        ),
        chat_model=langchain_model,
    )

    chunks = [chunk async for chunk in client.chat_stream([ChatMessage(role="user", content="hello")])]

    assert chunks == ["provider answer"]
    assert langchain_model.stream_calls == 0
    assert langchain_model.messages[0][0].type == "human"


@pytest.mark.asyncio
async def test_model_client_calls_modelscope_directly(monkeypatch):
    FakeAsyncHttpClient.requests = []
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    langchain_model = FakeLangChainModel(RuntimeError("langchain should not be used"))
    client = ModelClient(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://api-inference.modelscope.cn/v1",
            model_api_key="key",
            model_name="deepseek-ai/DeepSeek-V4-Pro",
            model_timeout_seconds=60,
        ),
        chat_model=langchain_model,
    )

    answer = await client.chat([ChatMessage(role="user", content="ping")])

    assert answer == "modelscope ready"
    assert FakeAsyncHttpClient.requests[0]["url"] == "https://api-inference.modelscope.cn/v1/chat/completions"
    assert FakeAsyncHttpClient.requests[0]["json"]["model"] == "deepseek-ai/DeepSeek-V4-Pro"
    assert FakeAsyncHttpClient.requests[0]["json"]["messages"] == [{"role": "user", "content": "ping"}]
