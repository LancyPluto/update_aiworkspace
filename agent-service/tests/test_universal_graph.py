import pytest

from app.core.budget_guard import BudgetState
from app.core.schemas import AgentFileChunkContext, AgentFileContext, RunContext, ToolDescriptor, ToolPreference
from app.graphs.universal_agent_graph import UniversalAgentGraph
from app.tools.backend_tool import BackendToolBridge


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

    async def fail_tool_call(self, tool_call_id, request):
        self.tool_calls.append(("fail", tool_call_id, request.errorCode, request.errorMessage))

    async def create_task(self, request):
        self.tool_calls.append(("task", request.toolCode, request.params, request.clientRequestId))
        return type("TaskStatus", (), {"taskId": 123, "status": "QUEUED"})()

    async def get_task_detail(self, user_id, task_id):
        result = type("TaskResult", (), {"resourceType": "MARKDOWN", "contentText": "# Generated copy"})()
        return type(
            "TaskDetail",
            (),
            {
                "taskId": task_id,
                "status": "SUCCESS",
                "progress": 100,
                "progressMessage": "done",
                "errorCode": None,
                "errorMessage": None,
                "result": result,
            },
        )()

    async def get_run_context(self, run_id):
        return type("RunContextStatus", (), {"status": "RUNNING"})()

    async def cancel_task(self, user_id, task_id):
        self.tool_calls.append(("cancel_task", user_id, task_id))

    async def complete_run(self, run_id, request):
        self.completed.append((run_id, request.finalAnswer, request.intent))

    async def fail_run(self, run_id, request):
        self.failed.append((run_id, request.errorCode))


class FakeModel:
    def __init__(self, responses=None, stream_responses=None):
        self.messages = []
        self.responses = list(responses or [])
        self.stream_messages = []
        self.stream_responses = list(stream_responses or [])

    async def chat(self, messages):
        self.messages.append(messages)
        if self.responses:
            return self.responses.pop(0)
        return "model answer: " + messages[-1].content

    async def chat_stream(self, messages):
        self.stream_messages.append(messages)
        if self.stream_responses:
            for chunk in self.stream_responses.pop(0):
                yield chunk
            return
        yield await self.chat(messages)


def xiaohongshu_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="Xiaohongshu",
        description="Xiaohongshu note copywriting",
        autoCallable=True,
    )


def xiaohongshu_tool_requiring_topic() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="Xiaohongshu",
        description="Xiaohongshu note copywriting",
        autoCallable=True,
        inputSchema={"type": "object", "required": ["topic"], "properties": {"topic": {"type": "string"}}},
    )


def title_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="product_title_optimizer",
        toolName="标题优化",
        description="商品 标题 优化",
        autoCallable=True,
    )


def longform_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="wechat_longform_generator",
        toolName="长文生成",
        description="商品 长文 内容",
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
async def test_graph_streams_chat_answer_chunks():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel(stream_responses=[["hello ", "world"]]))
    context = RunContext(runId=1, sessionId=1, userId=1, message="tell me about this platform")

    await graph.run(context)

    delta_events = [event for event in backend.events if event[1] == "message.delta"]
    assert [event[2] for event in delta_events] == ["hello ", "world"]
    assert backend.completed[0][1] == "hello world"


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
async def test_graph_asks_for_required_tool_arguments_before_auto_calling():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="please use xiaohongshu_copywriting",
        availableTools=[xiaohongshu_tool_requiring_topic()],
        toolPreferences=[ToolPreference(toolCode="xiaohongshu_copywriting", autoCallEnabled=True)],
    )

    await graph.run(context)

    assert backend.tool_calls == []
    assert backend.completed[0][2] == "tool_use"
    assert any(event[1] == "message.completed" for event in backend.events)


@pytest.mark.asyncio
async def test_graph_generates_tool_specific_clarification_for_missing_arguments():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="请直接帮我生成小红书文案",
        availableTools=[xiaohongshu_tool_requiring_topic()],
        toolPreferences=[ToolPreference(toolCode="xiaohongshu_copywriting", autoCallEnabled=True)],
    )

    await graph.run(context)

    assert "如果想使用" in backend.completed[0][1]
    assert "主题" in backend.completed[0][1]


def test_tool_bridge_extracts_explicit_required_arguments_from_message():
    bridge = BackendToolBridge(FakeBackend())
    context = RunContext(runId=1, sessionId=1, userId=1, message="please use xiaohongshu_copywriting topic: sunscreen launch")
    tool = xiaohongshu_tool_requiring_topic()

    arguments = bridge.build_arguments(context, tool)

    assert arguments["userRequest"] == "please use xiaohongshu_copywriting topic: sunscreen launch"
    assert arguments["topic"] == "sunscreen launch"
    assert bridge.missing_required_arguments(context, tool) == []


@pytest.mark.asyncio
async def test_graph_auto_calls_tool_when_required_arguments_are_explicit():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="please use xiaohongshu_copywriting topic: sunscreen launch",
        availableTools=[xiaohongshu_tool_requiring_topic()],
        toolPreferences=[ToolPreference(toolCode="xiaohongshu_copywriting", autoCallEnabled=True)],
    )

    await graph.run(context)

    assert backend.tool_calls[0][0] == 1
    assert backend.tool_calls[0][1] == "xiaohongshu_copywriting"
    assert backend.tool_calls[0][2]["userRequest"] == "please use xiaohongshu_copywriting topic: sunscreen launch"
    assert backend.tool_calls[0][2]["topic"] == "sunscreen launch"
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
async def test_confirmed_tool_requests_missing_arguments_instead_of_faking_success():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="请直接帮我生成小红书文案",
        availableTools=[xiaohongshu_tool_requiring_topic()],
    )

    await graph.run_confirmed_tool(context, "xiaohongshu_copywriting")

    assert backend.tool_calls == []
    assert backend.completed[0][2] == "tool_use"
    assert "如果想使用" in backend.completed[0][1]
    assert "主题" in backend.completed[0][1]


@pytest.mark.asyncio
async def test_confirmed_tool_executes_real_tool_when_arguments_are_present():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="please use xiaohongshu_copywriting topic: sunscreen launch",
        availableTools=[xiaohongshu_tool_requiring_topic()],
    )

    await graph.run_confirmed_tool(context, "xiaohongshu_copywriting")

    assert backend.tool_calls[0][0] == 1
    assert backend.tool_calls[0][1] == "xiaohongshu_copywriting"
    assert backend.tool_calls[0][2]["topic"] == "sunscreen launch"
    assert backend.tool_calls[1][0] == "task"
    assert backend.tool_calls[1][1] == "xiaohongshu_copywriting"
    assert backend.tool_calls[1][2]["topic"] == "sunscreen launch"
    assert backend.tool_calls[2][0] == "complete"
    assert backend.completed[0][2] == "tool_use"
    event_types = [event[1] for event in backend.events]
    assert "tool.task_dispatched" in event_types
    assert "tool.started" not in event_types


@pytest.mark.asyncio
async def test_graph_asks_for_disambiguation_when_multiple_tools_match():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="帮我优化这个商品内容",
        availableTools=[title_tool(), longform_tool()],
    )

    await graph.run(context)

    assert backend.tool_calls == []
    assert backend.completed[0][2] == "needs_clarification"
    assert "标题优化" in backend.completed[0][1]
    assert "长文生成" in backend.completed[0][1]


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

    prompt_text = "\n".join(message.content for message in model.stream_messages[0])
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

    prompt_text = "\n".join(message.content for message in model.stream_messages[0])
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

    prompt_text = "\n".join(message.content for message in model.stream_messages[0])
    assert "Subagent delegation hint" in prompt_text
    assert "researcher" in prompt_text
    assert "Research complex questions" in prompt_text


@pytest.mark.asyncio
async def test_tool_result_summary_prompt_avoids_duplicate_content():
    backend = FakeBackend()
    model = FakeModel(stream_responses=[["done"]])
    graph = UniversalAgentGraph(backend, model)
    state = {
        "run_id": 1,
        "context": RunContext(runId=1, sessionId=1, userId=1, message="给朋友圈生成一段新品文案"),
        "budget": BudgetState(credit_budget=20),
        "tool_result": {
            "arguments": {"topic": "周末肩颈放松活动"},
            "data": {"contentText": "周末到了，是时候给肩颈放个假。"},
            "summary": "周末到了，是时候给肩颈放个假。",
        },
    }

    await graph._synthesize_tool_answer(state)

    prompt_text = "\n".join(message.content for message in model.stream_messages[0])
    assert prompt_text.count("周末到了，是时候给肩颈放个假。") == 1
