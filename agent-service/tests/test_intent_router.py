from app.core.intent_router import Intent, IntentResult, IntentRouter

from app.core.preferred_tool_bias import (

    apply_preferred_tool_override,

    inject_preferred_tool_hint,

    message_suggests_tool_use,

)

from app.core.schemas import AgentFileContext, PendingToolContext, ChatMessage, RunContext, ToolDescriptor

from app.routing.attachment_signals import build_attachment_signal

from app.routing.state_guard import StateGuard





def context(message: str) -> RunContext:

    return RunContext(

        runId=1,

        sessionId=1,

        userId=1,

        message=message,

        availableTools=[

            ToolDescriptor(

                toolCode="xiaohongshu_copywriting",

                toolName="Xiaohongshu",

                description="小红书 种草 笔记",

                autoCallable=True,

            ),

        ],

    )





def test_state_guard_empty_message():

    result = StateGuard().classify(context(""))



    assert result.intent == Intent.NEEDS_CLARIFICATION

    assert result.reason == "empty_request"





def test_state_guard_does_not_short_circuit_file_analysis():

    ctx = context("总结文件")

    ctx.agentFiles = [AgentFileContext(id=1, originalFilename="a.txt", status="READY")]

    result = StateGuard().classify(ctx)



    assert result.intent != Intent.FILE_ANALYSIS

    assert result.reason == "awaiting_semantic_router"





def test_ready_image_reference_generation_awaits_semantic_router():

    ctx = context("按这张参考图生成 Suno 吉祥物")

    ctx.agentFiles = [

        AgentFileContext(

            id=1,

            originalFilename="ref.jpg",

            contentType="image/jpeg",

            status="READY",

            downloadUrl="/api/v1/agent/sessions/1/files/1/content",

        )

    ]



    result = StateGuard().classify(ctx)



    assert result.intent != Intent.FILE_ANALYSIS

    assert result.reason == "awaiting_semantic_router"





def test_attachment_signal_counts_ready_images():

    ctx = context("生成")

    ctx.agentFiles = [

        AgentFileContext(id=-1, originalFilename="@图片1", contentType="image/png", status="READY"),

        AgentFileContext(id=2, originalFilename="brief.pdf", contentType="application/pdf", status="READY"),

    ]

    signal = build_attachment_signal(ctx)



    assert signal.ready_image_count == 1

    assert signal.ready_document_count == 1

    assert signal.explicit_user_selection is True





def test_continues_tool_use_from_pending_tool_context():

    ctx = RunContext(

        runId=2,

        sessionId=1,

        userId=1,

        message="你来自由发挥补充即可",

        history=[

            ChatMessage(role="ASSISTANT", content="请补充视频风格与 prompt"),

        ],

        availableTools=[

            ToolDescriptor(

                toolCode="happyhorse_reference_to_video",

                toolName="参考图生视频",

                description="图生视频",

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

            collectedArgumentsJson={"referenceImage": "https://example.com/frame.png"},

        ),

    )

    result = StateGuard().classify(ctx)

    assert result.intent == Intent.TOOL_USE

    assert result.selectedToolCode == "happyhorse_reference_to_video"

    assert result.reason == "restored_from_pending_tool_context"





def test_preferred_tool_hint_for_generation_message():

    ctx = RunContext(

        runId=4,

        sessionId=1,

        userId=1,

        message="生成一张电影海报",

        preferredToolCode="ofox_gpt_image2",

        availableTools=[

            ToolDescriptor(

                toolCode="ofox_gpt_image2",

                toolName="GPT-image2",

                description="image generation",

                autoCallable=True,

            ),

        ],

    )

    result = StateGuard().classify(ctx)

    result = inject_preferred_tool_hint(ctx, result)

    assert result.selectedToolCode == "ofox_gpt_image2"

    assert "ofox_gpt_image2" in result.candidateToolCodes





def test_apply_preferred_tool_override_replaces_llm_choice():

    ctx = RunContext(

        runId=6,

        sessionId=1,

        userId=1,

        message="生成一张海报",

        preferredToolCode="ofox_gpt_image2",

        availableTools=[

            ToolDescriptor(

                toolCode="ofox_gpt_image2",

                toolName="GPT-image2",

                description="image generation",

                autoCallable=True,

            ),

            ToolDescriptor(

                toolCode="kling-image-v21",

                toolName="Kling Image",

                description="image generation",

                autoCallable=True,

            ),

        ],

    )

    llm_result = IntentResult(

        intent=Intent.TOOL_USE,

        confidence=0.9,

        selectedToolCode="kling-image-v21",

        candidateToolCodes=["kling-image-v21"],

        reason="unified_router_tool_call",

    )

    result = apply_preferred_tool_override(ctx, llm_result)

    assert result.selectedToolCode == "ofox_gpt_image2"





def test_message_suggests_tool_use():

    assert message_suggests_tool_use("生成一张海报") is True

    assert message_suggests_tool_use("你好") is False





def test_intent_router_is_state_guard_alias():

    assert isinstance(IntentRouter(), StateGuard)


