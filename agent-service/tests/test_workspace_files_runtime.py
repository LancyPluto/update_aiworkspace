from app.core.schemas import AgentFileChunkContext, AgentFileContext, RunContext
from app.runtime.workspace_files import build_workspace_file_context


def test_workspace_file_context_prefers_chunks_and_hides_host_paths():
    context = RunContext(
        runId=1,
        sessionId=2,
        userId=3,
        message="summarize",
        agentFiles=[
            AgentFileContext(
                id=7,
                originalFilename="C:\\Users\\alice\\Desktop\\product.txt",
                status="READY",
                extractedText="Full file text should not be used when chunks exist.",
            )
        ],
        agentFileChunks=[
            AgentFileChunkContext(
                id=11,
                fileId=7,
                originalFilename="C:\\Users\\alice\\Desktop\\product.txt",
                chunkIndex=1,
                contentText="Chunk text for the agent.",
                score=4,
            )
        ],
    )

    workspace_files = build_workspace_file_context(context)

    assert workspace_files.file_ids == [7]
    assert workspace_files.filenames == ["product.txt"]
    assert "Workspace files (read-only)" in workspace_files.prompt_context
    assert "workspace_file:7" in workspace_files.memory_items[0]
    assert "Chunk text for the agent." in workspace_files.memory_items[0]
    assert "Full file text" not in workspace_files.memory_items[0]
    assert "C:\\Users\\alice" not in workspace_files.prompt_context
    assert "C:\\Users\\alice" not in workspace_files.memory_items[0]


def test_workspace_file_context_uses_ready_files_when_chunks_are_absent():
    context = RunContext(
        runId=1,
        sessionId=2,
        userId=3,
        message="summarize",
        agentFiles=[
            AgentFileContext(
                id=8,
                originalFilename="/home/alice/notes.md",
                status="READY",
                extractedText="Ready file text.",
            ),
            AgentFileContext(
                id=9,
                originalFilename="draft.md",
                status="PROCESSING",
                extractedText="This is not ready.",
            ),
        ],
    )

    workspace_files = build_workspace_file_context(context)

    assert workspace_files.file_ids == [8]
    assert workspace_files.filenames == ["notes.md"]
    assert "Ready file text." in workspace_files.prompt_context
    assert "This is not ready." not in workspace_files.prompt_context
    assert "/home/alice" not in workspace_files.prompt_context


def test_workspace_file_context_is_empty_without_readable_text():
    context = RunContext(
        runId=1,
        sessionId=2,
        userId=3,
        message="summarize",
        agentFiles=[
            AgentFileContext(id=8, originalFilename="empty.md", status="READY", extractedText="   "),
        ],
    )

    workspace_files = build_workspace_file_context(context)

    assert workspace_files.is_empty
    assert workspace_files.file_ids == []
    assert workspace_files.filenames == []
    assert workspace_files.prompt_context == ""
    assert workspace_files.memory_items == []
