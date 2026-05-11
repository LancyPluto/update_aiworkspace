import pytest

from app.core.schemas import AgentFileChunkContext, AgentFileContext, RunContext, ToolDescriptor, ToolPreference
from app.graphs.universal_agent_graph import UniversalAgentGraph


class FakeBackend:
    def __init__(self):
        self.events = []
        self.completed = []
        self.failed = []
        self.tool_calls = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType, event.eventText, event.eventJson))

    async def create_tool_call(self, run_id, request):
        self.tool_calls.append((run_id, request.toolCode, request.argumentsJson))
        return type("ToolCall", (), {"id": 99, "toolCode": request.toolCode})()

    async def complete_tool_call(self, tool_call_id, request):
        self.tool_calls.append(("complete", tool_call_id, request.resultJson))

    async def complete_run(self, run_id, request):
        self.completed.append((run_id, request.finalAnswer, request.intent))

    async def fail_run(self, run_id, request):
        self.failed.append((run_id, request.errorCode))


class FakeModel:
    def __init__(self):
        self.messages = []

    async def chat(self, messages):
        self.messages.append(messages)
        return "model answer: " + messages[-1].content


def xiaohongshu_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="Xiaohongshu",
        description="Xiaohongshu note copywriting",
        autoCallable=True,
    )


@pytest.mark.asyncio
async def test_graph_completes_general_chat():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(runId=1, sessionId=1, userId=1, message="tell me about this platform")

    await graph.run(context)

    assert backend.completed[0][2] == "general_chat"
    assert any(event[1] == "message.completed" for event in backend.events)


@pytest.mark.asyncio
async def test_graph_executes_tool_use_when_preference_allows_auto_call():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="please use xiaohongshu_copywriting",
        availableTools=[xiaohongshu_tool()],
        toolPreferences=[ToolPreference(toolCode="xiaohongshu_copywriting", autoCallEnabled=True)],
    )

    await graph.run(context)

    assert backend.tool_calls[0][1] == "xiaohongshu_copywriting"
    assert backend.completed[0][2] == "tool_use"


@pytest.mark.asyncio
async def test_graph_requests_confirmation_before_first_tool_use():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="please use xiaohongshu_copywriting",
        availableTools=[xiaohongshu_tool()],
    )

    await graph.run(context)

    assert backend.tool_calls == []
    assert backend.completed == []
    assert any(event[1] == "tool.confirmation_required" for event in backend.events)


@pytest.mark.asyncio
async def test_graph_completes_unsupported_request_gracefully():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(runId=1, sessionId=1, userId=1, message="please run RAG")

    await graph.run(context)

    assert backend.completed[0][2] == "unsupported"


@pytest.mark.asyncio
async def test_graph_adds_ready_file_context_to_general_chat_prompt():
    backend = FakeBackend()
    model = FakeModel()
    graph = UniversalAgentGraph(backend, model)
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="请基于上传文件总结标准版权益",
        agentFiles=[
            AgentFileContext(
                id=7,
                originalFilename="product.txt",
                status="READY",
                extractedText="产品说明：标准版包含 3 个项目和团队协作。",
            )
        ],
    )

    await graph.run(context)

    prompt_text = "\n".join(message.content for message in model.messages[0])
    assert "product.txt" in prompt_text
    assert "标准版包含 3 个项目和团队协作" in prompt_text
    assert backend.completed[0][2] == "file_analysis"


@pytest.mark.asyncio
async def test_graph_prefers_retrieved_file_chunks_over_full_file_context():
    backend = FakeBackend()
    model = FakeModel()
    graph = UniversalAgentGraph(backend, model)
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="What security features are included?",
        agentFiles=[
            AgentFileContext(
                id=7,
                originalFilename="product.txt",
                status="READY",
                extractedText="Pricing: Pro plan includes unlimited exports.\n\nSecurity: SSO and audit logs are included.",
            )
        ],
        agentFileChunks=[
            AgentFileChunkContext(
                id=11,
                fileId=7,
                originalFilename="product.txt",
                chunkIndex=1,
                contentText="Security: SSO and audit logs are included.",
                score=4,
            )
        ],
    )

    await graph.run(context)

    prompt_text = "\n".join(message.content for message in model.messages[0])
    assert "product.txt" in prompt_text
    assert "Security: SSO and audit logs are included." in prompt_text
    assert "Pricing: Pro plan includes unlimited exports." not in prompt_text
    assert backend.completed[0][2] == "file_analysis"


@pytest.mark.asyncio
async def test_langgraph_uses_shared_subagent_profiles_for_delegation_hint():
    backend = FakeBackend()
    model = FakeModel()
    graph = UniversalAgentGraph(backend, model)
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="Research competitors and compare their pricing in a concise report.",
    )

    await graph.run(context)

    prompt_text = "\n".join(message.content for message in model.messages[0])
    assert "Subagent delegation hint" in prompt_text
    assert "researcher" in prompt_text
    assert "Research complex questions" in prompt_text
