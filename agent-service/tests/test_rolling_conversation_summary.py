import pytest

from app.clients.model_client import ChatTurnResult
from app.config import Settings
from app.core.event_types import CONVERSATION_SUMMARY_UPDATED
from app.core.runtime import AgentRuntime
from app.core.schemas import ChatMessage, RunContext
from app.runtime.agent_executor import AgentExecutor
from app.runtime.context_manager import ContextManager
from app.runtime.deep_agents_engine import _messages as deep_agent_messages


class SummaryModel:
    model_name = "fake-model"

    def __init__(self, summary: str = "【初始目标】\n用户最初要求生成 Azure Keep 蓝色城堡主题歌。"):
        self.summary = summary
        self.summary_calls = []
        self.turn_calls = []
        self.usage = {"promptTokens": 3, "completionTokens": 5}

    async def chat(self, messages, tools=None):
        self.summary_calls.append(messages)
        return self.summary

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.turn_calls.append({"messages": messages, "tools": tools, "tool_choice": tool_choice})
        return ChatTurnResult(content="ok", tool_calls=[])


class FakeBackend:
    def __init__(self, context: RunContext | None = None):
        self.context = context
        self.events = []
        self.summaries = []

    async def get_run_context(self, run_id):
        return self.context

    async def update_conversation_summary(self, run_id, conversation_summary):
        self.summaries.append((run_id, conversation_summary))

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))

    async def retrieve_workspace_memory(self, workspace_id, query, limit, view=None, memory_ids=None, session_id=None):
        return []


class FakeEngine:
    def __init__(self):
        self.run_contexts = []

    async def run(self, context):
        self.run_contexts.append(context)


class FakeRuntimeRouter:
    instances = []

    def __init__(self, backend_client=None, model_client=None, deep_agents_enabled=False, graph_engine_enabled=False):
        self.engine = FakeEngine()
        FakeRuntimeRouter.instances.append(self)

    def select_engine(self, message, requested_runtime=None):
        return self.engine


def _history():
    return [
        ChatMessage(role="user", content="第一句话：请生成一首关于蓝色城堡的歌，城堡名叫 Azure Keep。"),
        ChatMessage(role="assistant", content="好的，我会围绕 Azure Keep 写歌。"),
        ChatMessage(role="user", content="第二轮：风格改成空灵电子。"),
        ChatMessage(role="assistant", content="已记录空灵电子风格。"),
        ChatMessage(role="user", content="第三轮：副歌更明亮。"),
        ChatMessage(role="assistant", content="已把副歌调整得更明亮。"),
        ChatMessage(role="user", content="第四轮：加入钟声。"),
        ChatMessage(role="assistant", content="已加入钟声意象。"),
        ChatMessage(role="user", content="第五轮：歌词中文。"),
        ChatMessage(role="assistant", content="已切换成中文歌词。"),
        ChatMessage(role="user", content="第六轮：继续。"),
    ]


@pytest.mark.asyncio
async def test_runtime_summarizes_evicted_first_turn_before_engine_run():
    FakeRuntimeRouter.instances = []
    context = RunContext(runId=11, sessionId=2, userId=3, message="第一句话是什么？", history=_history())
    backend = FakeBackend(context)
    model = SummaryModel()
    settings = Settings(agent_context_max_recent_turns=1, agent_max_history_messages=20)
    runtime = AgentRuntime(
        backend,
        model_client=model,
        runtime_router_factory=FakeRuntimeRouter,
        default_settings=settings,
    )

    assert "Azure Keep" not in "\n".join(
        message.content for message in ContextManager.from_settings(settings).build_working_memory(context.history)
    )

    await runtime.execute_run(11)

    assert "Azure Keep" in model.summary_calls[0][0].content
    assert backend.summaries == [(11, model.summary)]
    assert backend.events[0][1].eventType == CONVERSATION_SUMMARY_UPDATED
    engine_context = FakeRuntimeRouter.instances[0].engine.run_contexts[0]
    assert engine_context.conversationSummary == model.summary


@pytest.mark.asyncio
async def test_agent_executor_injects_summary_before_recent_history_when_first_turn_is_evicted():
    context = RunContext(
        runId=12,
        sessionId=2,
        userId=3,
        message="第一句话是什么？",
        history=_history(),
        conversationSummary="【初始目标】\n用户最初要求生成 Azure Keep 蓝色城堡主题歌。",
    )
    executor = AgentExecutor(FakeBackend(), SummaryModel())
    executor.context_manager = ContextManager(max_recent_turns=1, max_history_messages=20)

    messages = await executor._build_initial_messages(context)
    joined = "\n".join(message.content or "" for message in messages)

    assert "<Conversation_Summary>" in joined
    assert "Azure Keep 蓝色城堡主题歌" in joined
    assert any(message.role == "user" and message.content == "第六轮：继续。" for message in messages)


def test_deep_agents_messages_inject_summary_when_first_turn_is_evicted():
    context = RunContext(
        runId=13,
        sessionId=2,
        userId=3,
        message="第一句话是什么？",
        history=_history(),
        conversationSummary="【初始目标】\n用户最初要求生成 Azure Keep 蓝色城堡主题歌。",
    )

    messages = deep_agent_messages(context)
    joined = "\n".join(message["content"] for message in messages)

    assert "<Conversation_Summary>" in joined
    assert "Azure Keep 蓝色城堡主题歌" in joined
