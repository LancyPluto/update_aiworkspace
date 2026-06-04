from __future__ import annotations

from typing import Any

from app.core.event_types import WORKSPACE_FILE_CREATED, WORKSPACE_FILE_READ
from app.core.schemas import RunContext, RunEventCreate
from app.runtime.workspace_files import WorkspaceFileContext, build_workspace_file_context


class WorkspaceFileRuntime:
    def __init__(self, backend) -> None:
        self.backend = backend

    async def build_context(self, context: RunContext) -> WorkspaceFileContext:
        workspace_file_context = build_workspace_file_context(context)
        if workspace_file_context.is_empty:
            return workspace_file_context
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=WORKSPACE_FILE_READ,
                eventText=", ".join(workspace_file_context.filenames),
                eventJson={
                    "fileIds": workspace_file_context.file_ids,
                    "filenames": workspace_file_context.filenames,
                },
            ),
        )
        return workspace_file_context

    async def create_artifact(self, run_id: int, artifact) -> None:
        response = await self.backend.create_run_artifact(
            run_id=run_id,
            filename=artifact.filename,
            content=artifact.content,
            content_type=artifact.content_type,
        )
        if backend_emitted_workspace_file_created(response):
            return
        await self.backend.append_event(
            run_id,
            RunEventCreate(
                eventType=WORKSPACE_FILE_CREATED,
                eventText=artifact.filename,
                eventJson={
                    "artifactId": response.get("id"),
                    "filename": response.get("originalFilename") or response.get("filename") or artifact.filename,
                    "contentType": response.get("contentType") or artifact.content_type,
                    "sourceRunId": run_id,
                },
            ),
        )


def backend_emitted_workspace_file_created(response: dict[str, Any]) -> bool:
    events = response.get("events") if isinstance(response, dict) else None
    if not isinstance(events, list):
        return False
    return any(isinstance(event, dict) and event.get("eventType") == WORKSPACE_FILE_CREATED for event in events)
