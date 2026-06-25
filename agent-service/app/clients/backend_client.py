import json
import logging
from typing import Any

import httpx

from app.config import Settings, settings as default_settings
from app.core.attachment_catalog import user_message_for_llm
from app.core.schemas import (
    RunArtifactCreate,
    RunComplete,
    RunContext,
    RunEventCreate,
    RunFail,
    TaskCreate,
    TaskDetailResponse,
    TaskStatusResponse,
    AgentModelConfig,
    SessionSearchItem,
    ToolCallComplete,
    ToolCallCreate,
    ToolCallFail,
    ToolCallResponse,
    ToolCallTaskBind,
    WorkspaceMemoryItem,
)
from app.observability.trace import TRACE_ID_HEADER, current_trace_id
from app.security.signature import signature_headers


class BackendClientError(RuntimeError):
    pass


class BackendBusinessError(BackendClientError):
    def __init__(self, message: str, *, error_code: str = "", data: Any | None = None) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.data = data


logger = logging.getLogger(__name__)


class BackendClient:
    def __init__(self, settings: Settings = default_settings, http_client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.base_url = settings.backend_internal_base_url.rstrip("/")
        self._client = http_client or httpx.AsyncClient(timeout=10, trust_env=False)

    async def get_run_context(self, run_id: int) -> RunContext:
        data = await self._request("GET", f"/api/internal/v1/agent/runs/{run_id}/context")
        context = RunContext.model_validate(data)
        if not context.referenceMentions:
            return context
        return context.model_copy(update={"message": user_message_for_llm(context)})

    async def get_active_model_config(self) -> AgentModelConfig:
        data = await self._request("GET", "/api/internal/v1/agent/model-config")
        return AgentModelConfig.model_validate(data)

    async def get_agent_skill(self, skill_code: str) -> dict[str, Any]:
        return await self._request("GET", f"/api/internal/v1/agent/skills/{skill_code}")

    async def retrieve_workspace_memory(
        self,
        workspace_id: int,
        query: str,
        limit: int,
        view: str | None = None,
        memory_ids: list[int] | None = None,
        session_id: int | None = None,
    ) -> list[WorkspaceMemoryItem]:
        body: dict[str, Any] = {"query": query, "limit": limit}
        if view:
            body["view"] = view
        if memory_ids:
            body["memoryIds"] = memory_ids
        if session_id is not None:
            body["sessionId"] = session_id
        data = await self._request(
            "POST",
            f"/api/internal/v1/agent/workspaces/{workspace_id}/memory/retrieve",
            body,
        )
        return [WorkspaceMemoryItem.model_validate(item) for item in data.get("list", [])]

    async def create_workspace_memory(
        self, workspace_id: int, user_id: int,
        memory_type: str, title: str, content: str,
        source_run_id: int | None = None,
        source_message_id: int | None = None,
        source_tool_call_id: int | None = None,
        importance: int | None = None,
        confidence: float | None = None,
        pinned: bool | None = None,
        tags_json: str | None = None,
        metadata_json: str | None = None,
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/v1/agent/workspaces/{workspace_id}/memory",
            {
                "memoryType": memory_type,
                "title": title,
                "content": content,
                "sourceRunId": source_run_id,
                "sourceMessageId": source_message_id,
                "sourceToolCallId": source_tool_call_id,
                "importance": importance,
                "confidence": confidence,
                "pinned": pinned,
                "tagsJson": tags_json,
                "metadataJson": metadata_json,
                "userId": user_id,
            },
        )

    async def create_workspace_memory_candidate(
        self,
        workspace_id: int,
        user_id: int,
        action: str,
        memory_type: str,
        title: str,
        content: str,
        source_run_id: int | None = None,
        source_message_id: int | None = None,
        source_tool_call_id: int | None = None,
        importance: int | None = None,
        confidence: float | None = None,
        reason: str | None = None,
        tags_json: str | None = None,
        metadata_json: str | None = None,
        expires_at: str | None = None,
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/v1/agent/workspaces/{workspace_id}/memory/candidates",
            {
                "userId": user_id,
                "action": action,
                "memoryType": memory_type,
                "title": title,
                "content": content,
                "sourceRunId": source_run_id,
                "sourceMessageId": source_message_id,
                "sourceToolCallId": source_tool_call_id,
                "importance": importance,
                "confidence": confidence,
                "reason": reason,
                "tagsJson": tags_json,
                "metadataJson": metadata_json,
                "expiresAt": expires_at,
            },
        )

    async def search_session(
        self,
        user_id: int,
        session_id: int,
        query: str,
        limit: int = 8,
        tool_code: str | None = None,
    ) -> list[SessionSearchItem]:
        data = await self._request(
            "POST",
            "/api/internal/v1/agent/session-search",
            {
                "userId": user_id,
                "sessionId": session_id,
                "query": query,
                "toolCode": tool_code,
                "limit": limit,
            },
        )
        return [SessionSearchItem.model_validate(item) for item in data.get("list", [])]

    async def update_workspace_memory(
        self, workspace_id: int, memory_id: int,
        memory_type: str, title: str, content: str,
        metadata_json: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "memoryType": memory_type,
            "title": title,
            "content": content,
        }
        if metadata_json is not None:
            payload["metadataJson"] = metadata_json
        return await self._request("PUT", f"/api/internal/v1/agent/workspaces/{workspace_id}/memory/{memory_id}", payload)

    async def delete_workspace_memory(
        self, workspace_id: int, memory_id: int,
    ) -> dict[str, Any]:
        return await self._request(
            "DELETE",
            f"/api/internal/v1/agent/workspaces/{workspace_id}/memory/{memory_id}",
        )

    async def append_event(self, run_id: int, event: RunEventCreate) -> None:
        await self._request("POST", f"/api/internal/v1/agent/runs/{run_id}/events", event)

    async def create_run_artifact(self, run_id: int, filename: str, content: str, content_type: str) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/v1/agent/runs/{run_id}/artifacts",
            RunArtifactCreate(filename=filename, content=content, contentType=content_type),
        )

    async def create_tool_call(self, run_id: int, request: ToolCallCreate) -> ToolCallResponse:
        data = await self._request("POST", f"/api/internal/v1/agent/runs/{run_id}/tool-calls", request)
        return ToolCallResponse.model_validate(data)

    async def bind_tool_call_task(self, tool_call_id: int, task_id: int) -> ToolCallResponse:
        data = await self._request(
            "POST",
            f"/api/internal/v1/agent/tool-calls/{tool_call_id}/task",
            ToolCallTaskBind(taskId=task_id),
        )
        return ToolCallResponse.model_validate(data)

    async def complete_tool_call(self, tool_call_id: int, request: ToolCallComplete) -> None:
        await self._request("POST", f"/api/internal/v1/agent/tool-calls/{tool_call_id}/complete", request)

    async def fail_tool_call(self, tool_call_id: int, request: ToolCallFail) -> None:
        await self._request("POST", f"/api/internal/v1/agent/tool-calls/{tool_call_id}/fail", request)

    async def create_task(self, request: TaskCreate) -> TaskStatusResponse:
        data = await self._request("POST", "/api/internal/v1/tasks", request)
        return TaskStatusResponse.model_validate(data)

    async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
        data = await self._request("GET", f"/api/internal/v1/tasks/{task_id}?userId={user_id}")
        return TaskDetailResponse.model_validate(data)

    async def cancel_task(self, user_id: int, task_id: int) -> None:
        await self._request("POST", f"/api/internal/v1/tasks/{task_id}/cancel?userId={user_id}")

    async def save_graph_checkpoint(self, run_id: int, checkpoint_json: str) -> None:
        await self._request(
            "PUT",
            f"/api/internal/v1/agent/runs/{run_id}/graph-checkpoint",
            {"checkpointJson": checkpoint_json},
        )

    async def load_graph_checkpoint(self, run_id: int) -> str | None:
        data = await self._request("GET", f"/api/internal/v1/agent/runs/{run_id}/graph-checkpoint")
        value = data.get("checkpointJson") if isinstance(data, dict) else None
        return value if isinstance(value, str) and value.strip() else None

    async def clear_graph_checkpoint(self, run_id: int) -> None:
        try:
            await self._request("DELETE", f"/api/internal/v1/agent/runs/{run_id}/graph-checkpoint")
        except BackendClientError:
            logger.debug("clear graph checkpoint failed (non-fatal) runId=%s", run_id)

    async def upsert_streaming_answer(self, run_id: int, content_text: str) -> None:
        await self._request(
            "PUT",
            f"/api/internal/v1/agent/runs/{run_id}/streaming-answer",
            {"contentText": content_text},
        )

    async def update_conversation_summary(self, run_id: int, conversation_summary: str) -> None:
        await self._request(
            "PUT",
            f"/api/internal/v1/agent/runs/{run_id}/conversation-summary",
            {"conversationSummary": conversation_summary},
        )

    async def complete_run(self, run_id: int, request: RunComplete) -> None:
        await self._request("POST", f"/api/internal/v1/agent/runs/{run_id}/complete", request)

    async def fail_run(self, run_id: int, request: RunFail) -> None:
        await self._request("POST", f"/api/internal/v1/agent/runs/{run_id}/fail", request)

    async def _request(self, method: str, path: str, payload: Any | None = None) -> dict[str, Any]:
        body = b""
        headers = {"Content-Type": "application/json"}
        if payload is not None:
            data = payload.model_dump(mode="json", by_alias=True, exclude_none=True) if hasattr(payload, "model_dump") else payload
            body = json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        headers.update(signature_headers(method, path, body, self.settings.internal_api_token))
        trace_id = current_trace_id()
        if trace_id:
            headers[TRACE_ID_HEADER] = trace_id
        try:
            response = await self._client.request(method, self.base_url + path, content=body if payload is not None else None, headers=headers)
        except httpx.HTTPError as exc:
            raise BackendClientError(f"backend request failed: {exc}") from exc
        return self._parse_response(response)

    def _parse_response(self, response: httpx.Response) -> dict[str, Any]:
        payload = self._read_json_payload(response)
        if response.status_code < 200 or response.status_code >= 300:
            if isinstance(payload, dict) and payload.get("code"):
                self._raise_business_error(response, payload)
            message = (
                f"backend request failed: method={response.request.method}, "
                f"url={response.request.url}, status={response.status_code}, body={_truncate(response.text)}"
            )
            logger.error(message)
            raise BackendClientError(message)
        if not isinstance(payload, dict):
            message = (
                f"backend returned non-json response: method={response.request.method}, "
                f"url={response.request.url}, status={response.status_code}, body={_truncate(response.text)}"
            )
            logger.error(message)
            raise BackendClientError(message)
        if payload.get("code") != "SUCCESS":
            self._raise_business_error(response, payload)
        return payload.get("data") or {}

    def _read_json_payload(self, response: httpx.Response) -> dict[str, Any] | None:
        try:
            payload = response.json()
        except ValueError:
            return None
        return payload if isinstance(payload, dict) else None

    def _raise_business_error(self, response: httpx.Response, payload: dict[str, Any]) -> None:
        message = (
            f"backend business error: method={response.request.method}, url={response.request.url}, "
            f"code={payload.get('code')}, message={payload.get('message', '')}, traceId={payload.get('traceId')}"
        )
        logger.error(message)
        raise BackendBusinessError(
            str(payload.get("message") or payload.get("code") or "backend business error"),
            error_code=str(payload.get("code") or ""),
            data=payload.get("data"),
        )


def _truncate(value: str, limit: int = 1200) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "...<truncated>"
