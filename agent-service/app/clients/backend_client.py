import json
import logging
import time
from typing import Any

import httpx
from pydantic import ValidationError

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
    DelegatedWorkflowResponse,
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
from app.observability.metrics import record_backend_request
from app.security.signature import signature_headers


class BackendClientError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        error_code: str = "",
        trace_id: str | None = None,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.trace_id = trace_id
        self.status_code = status_code


class BackendBusinessError(BackendClientError):
    def __init__(
        self,
        message: str,
        *,
        error_code: str = "",
        data: Any | None = None,
        trace_id: str | None = None,
        status_code: int | None = None,
    ) -> None:
        super().__init__(
            message,
            error_code=error_code,
            trace_id=trace_id,
            status_code=status_code,
        )
        self.data = data


class WorkflowDelegationUncertainError(BackendClientError):
    pass


logger = logging.getLogger(__name__)

WORKFLOW_DELEGATION_MAX_ATTEMPTS = 2


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

    async def record_model_request_snapshots(self, run_id: int, snapshots: list[dict[str, Any]]) -> None:
        if not snapshots:
            return
        await self._request(
            "POST",
            f"/api/internal/v1/agent/runs/{run_id}/model-request-snapshots",
            {"snapshots": snapshots},
        )

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

    async def delegate_workflow_tool_call(self, tool_call_id: int) -> DelegatedWorkflowResponse:
        path = f"/api/internal/v1/agent/tool-calls/{tool_call_id}/delegate-workflow"
        last_error: Exception | None = None
        for attempt in range(1, WORKFLOW_DELEGATION_MAX_ATTEMPTS + 1):
            try:
                data = await self._request("POST", path)
                return DelegatedWorkflowResponse.model_validate(data)
            except BackendBusinessError:
                raise
            except (BackendClientError, ValidationError) as exc:
                last_error = exc
                if attempt < WORKFLOW_DELEGATION_MAX_ATTEMPTS:
                    logger.warning(
                        "workflow delegation response uncertain; retrying persisted tool call toolCallId=%s attempt=%s",
                        tool_call_id,
                        attempt,
                    )

        raise WorkflowDelegationUncertainError(
            f"workflow delegation outcome is uncertain for tool call {tool_call_id}"
        ) from last_error

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

    async def save_native_graph_checkpoint(
        self,
        run_id: int,
        thread_id: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        checkpoint_serde: str,
        checkpoint_payload: str,
        metadata_json: str = "{}",
        parent_checkpoint_id: str | None = None,
    ) -> None:
        await self._request(
            "PUT",
            f"/api/internal/v1/agent/runs/{run_id}/langgraph-checkpoint",
            {
                "threadId": thread_id,
                "checkpointNs": checkpoint_ns,
                "checkpointId": checkpoint_id,
                "checkpointSerde": checkpoint_serde,
                "checkpointPayload": checkpoint_payload,
                "metadataJson": metadata_json,
                "parentCheckpointId": parent_checkpoint_id,
            },
        )

    async def load_native_graph_checkpoint(self, run_id: int, thread_id: str, checkpoint_ns: str = "", checkpoint_id: str | None = None) -> dict[str, Any] | None:
        query = f"threadId={thread_id}&checkpointNs={checkpoint_ns}"
        if checkpoint_id:
            query += f"&checkpointId={checkpoint_id}"
        data = await self._request(
            "GET",
            f"/api/internal/v1/agent/runs/{run_id}/langgraph-checkpoint?{query}",
        )
        return data if isinstance(data, dict) and data.get("checkpointPayload") else None

    async def save_native_graph_checkpoint_writes(self, run_id: int, thread_id: str, checkpoint_ns: str,
                                                   checkpoint_id: str, task_id: str, task_path: str,
                                                   writes: list[dict[str, Any]]) -> None:
        await self._request("PUT", f"/api/internal/v1/agent/runs/{run_id}/langgraph-checkpoint-writes", {
            "threadId": thread_id, "checkpointNs": checkpoint_ns, "checkpointId": checkpoint_id,
            "taskId": task_id, "taskPath": task_path, "writes": writes,
        })

    async def list_native_graph_checkpoints(self, run_id: int, thread_id: str, checkpoint_ns: str,
                                             before_checkpoint_id: str | None, limit: int | None,
                                             metadata_filter: dict[str, Any]) -> list[dict[str, Any]]:
        data = await self._request("POST", f"/api/internal/v1/agent/runs/{run_id}/langgraph-checkpoints/search", {
            "threadId": thread_id, "checkpointNs": checkpoint_ns, "beforeCheckpointId": before_checkpoint_id,
            "limit": limit, "metadataFilter": metadata_filter,
        })
        return data.get("list", []) if isinstance(data, dict) else []

    async def clear_native_graph_checkpoint(self, run_id: int, thread_id: str) -> None:
        try:
            await self._request(
                "DELETE",
                f"/api/internal/v1/agent/runs/{run_id}/langgraph-checkpoint?threadId={thread_id}",
            )
        except BackendClientError:
            logger.debug("clear native graph checkpoint failed (non-fatal) runId=%s", run_id)

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
        start = time.perf_counter()
        try:
            response = await self._client.request(method, self.base_url + path, content=body if payload is not None else None, headers=headers)
        except httpx.HTTPError as exc:
            record_backend_request(method, path, "network_error", time.perf_counter() - start)
            raise BackendClientError(f"backend request failed: {exc}") from exc
        record_backend_request(method, path, str(response.status_code), time.perf_counter() - start)
        return self._parse_response(response)

    def _parse_response(self, response: httpx.Response) -> dict[str, Any]:
        payload = self._read_json_payload(response)
        if response.status_code < 200 or response.status_code >= 300:
            if isinstance(payload, dict) and (payload.get("errorCode") or payload.get("code")):
                self._raise_business_error(response, payload)
            trace_id = response.headers.get(TRACE_ID_HEADER) or "-"
            message = (
                f"backend request failed: method={response.request.method}, "
                f"url={response.request.url}, status={response.status_code}, traceId={trace_id}"
            )
            logger.error(message)
            raise BackendClientError(
                message,
                error_code="SYSTEM_001",
                trace_id=None if trace_id == "-" else trace_id,
                status_code=response.status_code,
            )
        if not isinstance(payload, dict):
            trace_id = response.headers.get(TRACE_ID_HEADER) or "-"
            message = (
                f"backend returned non-json response: method={response.request.method}, "
                f"url={response.request.url}, status={response.status_code}, traceId={trace_id}"
            )
            logger.error(message)
            raise BackendClientError(
                message,
                error_code="API_001",
                trace_id=None if trace_id == "-" else trace_id,
                status_code=response.status_code,
            )
        if payload.get("errorCode") or payload.get("code") != "SUCCESS":
            self._raise_business_error(response, payload)
        return payload.get("data") or {}

    def _read_json_payload(self, response: httpx.Response) -> dict[str, Any] | None:
        try:
            payload = response.json()
        except ValueError:
            return None
        return payload if isinstance(payload, dict) else None

    def _raise_business_error(self, response: httpx.Response, payload: dict[str, Any]) -> None:
        error_code = str(payload.get("errorCode") or payload.get("code") or "SYSTEM_001")
        invalid_success_code = error_code.upper() == "SUCCESS"
        if invalid_success_code:
            error_code = "SYSTEM_001"
        trace_id = str(
            payload.get("traceId")
            or payload.get("requestId")
            or response.headers.get(TRACE_ID_HEADER)
            or ""
        )
        developer_message = (
            f"backend returned SUCCESS error code with HTTP status {response.status_code}"
            if invalid_success_code
            else str(
                payload.get("developerMessage")
                or payload.get("userMessage")
                or payload.get("message")
                or error_code
            )
        )
        message = (
            f"backend business error: method={response.request.method}, url={response.request.url}, "
            f"errorCode={error_code}, message={_truncate(developer_message)}, traceId={trace_id or '-'}"
        )
        logger.error(message)
        raise BackendBusinessError(
            developer_message,
            error_code=error_code,
            data=payload.get("data"),
            trace_id=trace_id or None,
            status_code=response.status_code,
        )


def _truncate(value: str, limit: int = 1200) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "...<truncated>"
