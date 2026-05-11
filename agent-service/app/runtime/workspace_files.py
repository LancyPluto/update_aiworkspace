from dataclasses import dataclass, field

from app.core.schemas import AgentFileChunkContext, AgentFileContext, RunContext


@dataclass(frozen=True)
class WorkspaceFileContext:
    file_ids: list[int] = field(default_factory=list)
    filenames: list[str] = field(default_factory=list)
    prompt_context: str = ""
    memory_items: list[str] = field(default_factory=list)

    @property
    def is_empty(self) -> bool:
        return not self.file_ids and not self.prompt_context and not self.memory_items


def build_workspace_file_context(context: RunContext) -> WorkspaceFileContext:
    chunk_entries = [_entry_from_chunk(chunk) for chunk in context.agentFileChunks if chunk.contentText.strip()]
    if chunk_entries:
        return _build_context(chunk_entries)

    file_entries = [_entry_from_file(file) for file in context.agentFiles if file.status == "READY" and file.extractedText.strip()]
    if file_entries:
        return _build_context(file_entries)

    return WorkspaceFileContext()


def _entry_from_chunk(chunk: AgentFileChunkContext) -> dict[str, object]:
    filename = _safe_filename(chunk.originalFilename, chunk.fileId)
    return {
        "file_id": chunk.fileId,
        "filename": filename,
        "label": f"[workspace_file:{chunk.fileId}] {filename}, chunk {chunk.chunkIndex}",
        "content": chunk.contentText.strip()[:4000],
    }


def _entry_from_file(file: AgentFileContext) -> dict[str, object]:
    filename = _safe_filename(file.originalFilename, file.id)
    return {
        "file_id": file.id,
        "filename": filename,
        "label": f"[workspace_file:{file.id}] {filename}",
        "content": file.extractedText.strip()[:12000],
    }


def _build_context(entries: list[dict[str, object]]) -> WorkspaceFileContext:
    file_ids: list[int] = []
    filenames: list[str] = []
    sections = ["Workspace files (read-only): Use these platform-provided file excerpts when relevant. Do not infer or request host filesystem paths."]

    for entry in entries:
        file_id = int(entry["file_id"])
        filename = str(entry["filename"])
        if file_id not in file_ids:
            file_ids.append(file_id)
            filenames.append(filename)
        sections.append(f"\n{entry['label']}\n{entry['content']}")

    context_text = "\n".join(sections)
    return WorkspaceFileContext(
        file_ids=file_ids,
        filenames=filenames,
        prompt_context=context_text,
        memory_items=[context_text],
    )


def _safe_filename(value: str, file_id: int) -> str:
    normalized = value.replace("\\", "/").rstrip("/")
    filename = normalized.rsplit("/", 1)[-1].strip()
    return filename or f"file-{file_id}"
