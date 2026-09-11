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

    def __init__(self, payload, *, content=b"", headers=None):
        self.payload = payload
        self.text = str(payload)
        self.content = content
        self.headers = headers or {}

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeStreamResponse:
    status_code = 200

    def __init__(self, lines):
        self.lines = lines

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None

    async def aread(self):
        return b""

    async def aiter_lines(self):
        for line in self.lines:
            yield line


class FakeAsyncHttpClient:
    requests = []
    stream_lines = []

    def __init__(self, timeout=None):
        self.timeout = timeout

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None

    async def post(self, url, headers=None, json=None):
        FakeAsyncHttpClient.requests.append({"url": url, "headers": headers, "json": json, "timeout": self.timeout})
        return FakeAsyncResponse({"choices": [{"message": {"content": "modelscope ready"}}]})

    async def get(self, url, headers=None):
        FakeAsyncHttpClient.requests.append({"method": "GET", "url": url, "headers": headers, "timeout": self.timeout})
        return FakeAsyncResponse({}, content=b"image-bytes", headers={"content-type": "image/png"})

    def stream(self, method, url, headers=None, json=None):
        FakeAsyncHttpClient.requests.append({"method": method, "url": url, "headers": headers, "json": json, "timeout": self.timeout})
        return FakeStreamResponse(self.stream_lines)


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
async def test_openai_compatible_stream_keeps_metadata_only_tool_call_delta(monkeypatch):
    FakeAsyncHttpClient.requests = []
    FakeAsyncHttpClient.stream_lines = [
        'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_cat","function":{"name":"gpt_image2","arguments":""}}]}}]}',
        'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"prompt\\":\\"a cat\\"}"}}]}}]}',
        "data: [DONE]",
    ]
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    client = ModelClient(
        Settings(model_provider="deepseek", model_api_base_url="https://api.deepseek.com", model_api_key="key"),
        chat_model=FakeLangChainModel("unused"),
    )

    parts = [
        part
        async for part in client._chat_openai_compatible_stream_direct(
            [ChatMessage(role="user", content="draw a cat")],
            tools=[{"type": "function", "function": {"name": "gpt_image2", "parameters": {"type": "object", "properties": {"prompt": {"type": "string"}}}}}],
            emit_parts=True,
        )
    ]

    assert [(part.kind, part.tool_call_id, part.tool_call_name, part.text) for part in parts] == [
        ("tool_call", "call_cat", "gpt_image2", ""),
        ("tool_call", "", "", '{"prompt":"a cat"}'),
    ]


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
async def test_model_client_preserves_multimodal_content_for_openai_direct(monkeypatch):
    FakeAsyncHttpClient.requests = []
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    client = ModelClient(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://api-inference.modelscope.cn/v1",
            model_api_key="key",
            model_name="qwen-vl-max",
            model_timeout_seconds=60,
        ),
        chat_model=FakeLangChainModel(RuntimeError("langchain should not be used")),
    )
    content = [
        {"type": "text", "text": "描述图2并用于提示词"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
    ]

    await client.chat([ChatMessage(role="user", content=content)])

    assert FakeAsyncHttpClient.requests[0]["json"]["messages"] == [{"role": "user", "content": content}]


@pytest.mark.asyncio
async def test_model_client_calls_qwen_directly(monkeypatch):
    FakeAsyncHttpClient.requests = []
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    client = ModelClient(
        Settings(
            model_provider="qwen",
            model_api_base_url="https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            model_api_key="key",
            model_name="qwen3.6-plus",
            model_timeout_seconds=60,
        ),
        chat_model=FakeLangChainModel(RuntimeError("langchain should not be used")),
    )

    answer = await client.chat([ChatMessage(role="user", content="ping")])

    assert answer == "modelscope ready"
    assert FakeAsyncHttpClient.requests[0]["url"] == "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
    assert FakeAsyncHttpClient.requests[0]["json"]["model"] == "qwen3.6-plus"
    assert FakeAsyncHttpClient.requests[0]["json"]["messages"] == [{"role": "user", "content": "ping"}]


@pytest.mark.asyncio
async def test_model_client_inlines_private_qwen_image_urls(monkeypatch):
    FakeAsyncHttpClient.requests = []
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    client = ModelClient(
        Settings(
            model_provider="qwen",
            model_api_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
            model_api_key="key",
            model_name="qwen3.6-plus",
            backend_internal_base_url="http://backend:8080",
        ),
        chat_model=FakeLangChainModel(RuntimeError("langchain should not be used")),
    )
    content = [
        {"type": "text", "text": "看图"},
        {"type": "image_url", "image_url": {"url": "http://backend:8080/generated/uploads/a.png"}},
    ]

    await client.chat([ChatMessage(role="user", content=content)])

    post_request = [request for request in FakeAsyncHttpClient.requests if "json" in request][0]
    sent_url = post_request["json"]["messages"][0]["content"][1]["image_url"]["url"]
    assert sent_url == "data:image/png;base64,aW1hZ2UtYnl0ZXM="


@pytest.mark.asyncio
async def test_model_client_inlines_local_openai_compatible_image_urls(monkeypatch):
    FakeAsyncHttpClient.requests = []
    client = ModelClient(
        Settings(
            model_provider="openai_compatible",
            model_api_base_url="https://ark.cn-beijing.volces.com/api/v3",
            model_api_key="key",
            model_name="doubao-seed-2.0-lite",
            backend_internal_base_url="http://127.0.0.1:8080",
        )
    )
    monkeypatch.setattr("app.clients.model_client.httpx.AsyncClient", FakeAsyncHttpClient)
    content = [
        {"type": "text", "text": "描述图片"},
        {"type": "image_url", "image_url": {"url": "http://127.0.0.1:8080/generated/uploads/a.png"}},
    ]

    await client.chat([ChatMessage(role="user", content=content)])

    post_request = [request for request in FakeAsyncHttpClient.requests if "json" in request][0]
    sent_url = post_request["json"]["messages"][0]["content"][1]["image_url"]["url"]
    assert sent_url == "data:image/png;base64,aW1hZ2UtYnl0ZXM="


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
