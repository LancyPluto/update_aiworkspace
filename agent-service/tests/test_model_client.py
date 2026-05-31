import pytest

from app.clients.model_client import ModelClient, ModelClientError
from app.config import Settings
from app.core.schemas import ChatMessage


class FakeLangChainMessage:
    def __init__(self, content: str, tool_calls=None, additional_kwargs=None):
        self.content = content
        self.tool_calls = tool_calls or []
        self.additional_kwargs = additional_kwargs or {}


class FakeLangChainModel:
    def __init__(self, response):
        self.response = response
        self.messages = []
        self.kwargs = []
        self.stream_calls = 0

    async def ainvoke(self, messages, **kwargs):
        self.messages.append(messages)
        self.kwargs.append(kwargs)
        if isinstance(self.response, Exception):
            raise self.response
        if isinstance(self.response, FakeLangChainMessage):
            return self.response
        return FakeLangChainMessage(self.response)

    async def astream(self, messages, **kwargs):
        self.stream_calls += 1
        self.messages.append(messages)
        self.kwargs.append(kwargs)
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
async def test_model_client_converts_tools_for_anthropic_compatible_stream():
    langchain_model = FakeLangChainModel(["ok"])
    client = ModelClient(
        Settings(model_provider="anthropic_compatible", model_api_base_url="https://api.minimaxi.com/anthropic", model_api_key="key"),
        chat_model=langchain_model,
    )
    tools = [
        {
            "type": "function",
            "function": {
                "name": "memory_add",
                "description": "save memory",
                "parameters": {
                    "type": "object",
                    "properties": {"content": {"type": "string"}},
                    "required": ["content"],
                },
            },
        }
    ]

    chunks = [chunk async for chunk in client.chat_stream([ChatMessage(role="user", content="hello")], tools=tools)]

    assert chunks == ["ok"]
    assert langchain_model.kwargs[0]["tools"] == [
        {
            "name": "memory_add",
            "description": "save memory",
            "input_schema": {
                "type": "object",
                "properties": {"content": {"type": "string"}},
                "required": ["content"],
            },
        }
    ]


@pytest.mark.asyncio
async def test_model_client_drops_invalid_tools_before_request():
    langchain_model = FakeLangChainModel(["ok"])
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=langchain_model,
    )
    tools = [
        {"type": "function", "function": {"name": "", "parameters": {"type": "object", "properties": {"x": {"type": "string"}}}}},
        {"type": "function", "function": {"name": "empty_params", "parameters": {"type": "object", "properties": {}}}},
    ]

    chunks = [chunk async for chunk in client.chat_stream([ChatMessage(role="user", content="hello")], tools=tools)]

    assert chunks == ["ok"]
    assert "tools" not in langchain_model.kwargs[0]


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


@pytest.mark.asyncio
async def test_chat_turn_parses_langchain_tool_calls():
    langchain_model = FakeLangChainModel(
        FakeLangChainMessage(
            "",
            tool_calls=[
                {
                    "id": "call_1",
                    "name": "memory_add",
                    "args": {"memory_type": "preference", "title": "Style", "content": "Likes playful images."},
                }
            ],
        )
    )
    client = ModelClient(
        Settings(model_provider="openai_compatible", model_api_base_url="http://model", model_api_key="key"),
        chat_model=langchain_model,
    )

    turn = await client.chat_turn([ChatMessage(role="user", content="remember this")], tools=[
        {
            "type": "function",
            "function": {
                "name": "memory_add",
                "parameters": {"type": "object", "properties": {"content": {"type": "string"}}},
            },
        }
    ])

    assert turn.content == ""
    assert turn.tool_calls[0].id == "call_1"
    assert turn.tool_calls[0].name == "memory_add"
    assert turn.tool_calls[0].arguments["memory_type"] == "preference"


@pytest.mark.asyncio
async def test_chat_turn_parses_openai_compatible_tool_calls(monkeypatch):
    FakeAsyncHttpClient.requests = []

    class ToolCallHttpClient(FakeAsyncHttpClient):
        async def post(self, url, headers=None, json=None):
            FakeAsyncHttpClient.requests.append({"url": url, "headers": headers, "json": json, "timeout": self.timeout})
            return FakeAsyncResponse(
                {
                    "choices": [
                        {
                            "finish_reason": "tool_calls",
                            "message": {
                                "content": "",
                                "tool_calls": [
                                    {
                                        "id": "call_2",
                                        "type": "function",
                                        "function": {
                                            "name": "memory_add",
                                            "arguments": "{\"memory_type\":\"user_profile\",\"title\":\"Profile\",\"content\":\"Creative user.\"}",
                                        },
                                    }
                                ],
                            },
                        }
                    ]
                }
            )

    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", ToolCallHttpClient)
    client = ModelClient(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://api-inference.modelscope.cn/v1",
            model_api_key="key",
            model_name="deepseek-ai/DeepSeek-V4-Pro",
        ),
        chat_model=FakeLangChainModel(RuntimeError("langchain should not be used")),
    )

    turn = await client.chat_turn([ChatMessage(role="user", content="remember")], tools=[
        {
            "type": "function",
            "function": {
                "name": "memory_add",
                "parameters": {"type": "object", "properties": {"content": {"type": "string"}}},
            },
        }
    ])

    assert turn.content == ""
    assert turn.finish_reason == "tool_calls"
    assert turn.tool_calls[0].id == "call_2"
    assert turn.tool_calls[0].arguments["content"] == "Creative user."
