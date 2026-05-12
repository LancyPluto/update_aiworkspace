import json
from typing import Any

import httpx

from app.config import Settings, settings as default_settings
from app.core.schemas import (
    RunArtifactCreate,
    RunComplete,
    RunContext,
    RunEventCreate,
    RunFail,
    AgentModelConfig,
    ToolCallComplete,
    ToolCallCreate,
    ToolCallFail,
    ToolCallResponse,
    WorkspaceMemoryItem,
)
from app.security.signature import signature_headers


class BackendClientError(RuntimeError):
    pass


class BackendBusinessError(BackendClientError):
    pass


class BackendClient:
    def __init__(self, settings: Settings = default_settings, http_client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.base_url = settings.backend_internal_base_url.rstrip("/")
        self._client = http_client or httpx.AsyncClient(timeout=10)

    async def get_run_context(self, run_id: int) -> RunContext:
        data = await self._request("GET", f"/api/internal/v1/agent/runs/{run_id}/context")
        return RunContext.model_validate(data)

    async def get_active_model_config(self) -> AgentModelConfig:
        data = await self._request("GET", "/api/internal/v1/agent/model-config")
        return AgentModelConfig.model_validate(data)

    async def retrieve_workspace_memory(self, workspace_id: int, query: str, limit: int) -> list[WorkspaceMemoryItem]:
        data = await self._request(
            "POST",
            f"/api/internal/v1/agent/workspaces/{workspace_id}/memory/retrieve",
            {"query": query, "limit": limit},
        )
        return [WorkspaceMemoryItem.model_validate(item) for item in data.get("list", [])]

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

    async def complete_tool_call(self, tool_call_id: int, request: ToolCallComplete) -> None:
        await self._request("POST", f"/api/internal/v1/agent/tool-calls/{tool_call_id}/complete", request)

    async def fail_tool_call(self, tool_call_id: int, request: ToolCallFail) -> None:
        await self._request("POST", f"/api/internal/v1/agent/tool-calls/{tool_call_id}/fail", request)

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
        try:
            response = await self._client.request(method, self.base_url + path, content=body if payload is not None else None, headers=headers)
        except httpx.HTTPError as exc:
            raise BackendClientError(f"backend request failed: {exc}") from exc
        return self._parse_response(response)

    def _parse_response(self, response: httpx.Response) -> dict[str, Any]:
        if response.status_code < 200 or response.status_code >= 300:
            raise BackendClientError(f"backend request failed: status={response.status_code}, body={response.text}")
        try:
            payload = response.json()
        except ValueError as exc:
            raise BackendClientError("backend returned non-json response") from exc
        if payload.get("code") != "SUCCESS":
            raise BackendBusinessError(f"backend business error: code={payload.get('code')}, message={payload.get('message', '')}")
        return payload.get("data") or {}
