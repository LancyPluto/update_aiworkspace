import pytest



from app.clients.model_client import ChatToolCall, ChatTurnResult

from app.core.schemas import PendingToolContext, ChatMessage, RunContext, ToolDescriptor

from app.routing.types import Intent

from app.routing.v2.router_result import map_chat_turn_to_intent

from app.routing.v2.routing_context import build_routing_messages

from app.routing.v2.thread_state import build_thread_state, format_thread_state_block

from app.routing.v2.unified_router import UnifiedSemanticRouter





class _FakeBackend:

    async def append_event(self, run_id, event):

        return None





class _FakeModel:

    def __init__(self, turn: ChatTurnResult) -> None:

        self._turn = turn



    async def chat_turn(self, messages, tools=None, tool_choice=None):

        return self._turn





def _video_followup_context() -> RunContext:

    return RunContext(

        runId=502,

        sessionId=99,

        userId=1,

        message="你来自由发挥补充即可",

        history=[

            ChatMessage(role="USER", content="用刚生成的图作首帧，帮我生成一段视频"),

            ChatMessage(

                role="ASSISTANT",

                content="请补充视频风格与 motion prompt；你可以自由描述想要的镜头与氛围。",

            ),

        ],

        availableTools=[

            ToolDescriptor(

                toolCode="happyhorse_reference_to_video",

                toolName="参考图生视频",

                description="reference image to video generation",

                autoCallable=True,

            ),

            ToolDescriptor(

                toolCode="ofox_gpt_image2",

                toolName="GPT-image2",

                description="image generation",

                autoCallable=True,

            ),

        ],

        pendingToolContext=PendingToolContext(

            status="ACTIVE",

            selectedToolCode="happyhorse_reference_to_video",

            missingArgumentsJson=["prompt", "style"],

            collectedArgumentsJson={"referenceImage": "https://cdn.example/frame.png"},

        ),

    )





def test_thread_state_block_includes_active_tool():

    ctx = _video_followup_context()

    state = build_thread_state(ctx)

    block = format_thread_state_block(state)



    assert "happyhorse_reference_to_video" in block

    assert "prompt" in block

    assert state.has_active_task is True





def test_routing_messages_include_thread_state_not_json_dump():

    ctx = _video_followup_context()

    messages = build_routing_messages(ctx, thread_state=build_thread_state(ctx))



    assert messages[0].role == "system"

    assert messages[-1].role == "user"

    assert messages[-1].content == ctx.message

    thread_msgs = [m.content for m in messages if m.content and "Active routing thread" in m.content]

    assert thread_msgs

    assert "Routing input:" not in "\n".join(m.content or "" for m in messages)





def test_map_chat_turn_selects_disclosed_tool():

    tools = _video_followup_context().availableTools

    turn = ChatTurnResult(

        tool_calls=[

            ChatToolCall(

                id="1",

                name="happyhorse_reference_to_video",

                arguments={"prompt": "赛博朋克城市夜景", "style": "电影感"},

            )

        ]

    )

    result = map_chat_turn_to_intent(turn, aliases={}, tools=tools)



    assert result.intent == Intent.TOOL_USE

    assert result.selectedToolCode == "happyhorse_reference_to_video"

    assert result.arguments["prompt"] == "赛博朋克城市夜景"





@pytest.mark.asyncio

async def test_unified_router_thread_continuation_on_general_chat():

    ctx = _video_followup_context()

    router = UnifiedSemanticRouter(_FakeBackend(), _FakeModel(ChatTurnResult(content="好的")))

    result = await router.route(ctx)



    assert result is not None

    assert result.intent == Intent.TOOL_USE

    assert result.selectedToolCode == "happyhorse_reference_to_video"

    assert result.reason == "unified_router_thread_continuation"


