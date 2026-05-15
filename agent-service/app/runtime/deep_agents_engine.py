import importlib.util
import inspect
import re
from collections.abc import AsyncIterable, Callable, Iterable
from dataclasses import dataclass
from types import ModuleType
from typing import Any

from app.config import settings
from langchain_core.callbacks import AsyncCallbackHandler

from app.core.event_types import (
    MEMORY_CANDIDATE_CREATED,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    SUBAGENT_COMPLETED,
    SUBAGENT_FAILED,
    SUBAGENT_STARTED,
    WORKSPACE_FILE_CREATED,
    WORKSPACE_FILE_READ,
)
from app.core.schemas import ChatMessage, RunComplete, RunContext, RunEventCreate, RunFail, WorkspaceMemoryItem
from app.runtime.subagent_profiles import default_subagent_profiles, profiles_to_deepagents_subagents
from app.runtime.workspace_files import WorkspaceFileContext, build_workspace_file_context


DEEP_AGENTS_INTENT = "deep_agents"
DEEP_AGENTS_SYSTEM_PROMPT = (
    "You are a workspace agent for an AI tool marketplace. Plan and execute long-running tasks carefully, "
    "use available context from the conversation and files, and return a concise final answer for the user."
)


class DeepAgentsRuntimeEngine:
    def __init__(
        self,
        backend_client,
        model_client,
        *,
        deep_agents_enabled: bool = False,
        dependency_loader: Callable[[], ModuleType | None] | None = None,
    ) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled
        self.dependency_loader = dependency_loader or self._load_deepagents

    async def run(self, context: RunContext) -> None:
        if not self.deep_agents_enabled:
            await self._fail(context.runId, "DEEP_AGENTS_DISABLED", "Deep Agents runtime is disabled")
            return

        module = self.ensure_available()
        create_deep_agent = getattr(module, "create_deep_agent", None)
        if not callable(create_deep_agent):
            raise RuntimeError("deepagents package does not expose create_deep_agent")

        try:
            chat_model = self._chat_model()
            workspace_memory_items = await self._workspace_memory_items(context)
            workspace_file_context = await self._workspace_file_context(context)
            agent = create_deep_agent(
                tools=[],
                system_prompt=DEEP_AGENTS_SYSTEM_PROMPT,
                model=chat_model,
                subagents=profiles_to_deepagents_subagents(
                    default_subagent_profiles(),
                    model=chat_model,
                    tools=[],
                ),
                memory=_format_workspace_memory_items(workspace_memory_items) + workspace_file_context.memory_items,
            )
        except AttributeError as exception:
            await self._fail(
                context.runId,
                "DEEP_AGENTS_MODEL_UNSUPPORTED",
                f"Deep Agents requires a LangChain-compatible chat model: {exception}",
            )
            return
        workspace_memory_context = _format_workspace_memory_context(workspace_memory_items)
        stream_result = await _invoke_agent_streaming(
            agent,
            {"messages": _messages(context, workspace_memory_context, workspace_file_context.prompt_context)},
            context.runId,
            self.backend_client,
            config={"callbacks": [SubagentTraceCallbackHandler(context.runId, self.backend_client)]},
        )
        if stream_result.result is not None:
            answer = _extract_final_answer(stream_result.result)
        elif stream_result.streamed_text:
            answer = stream_result.streamed_text
        else:
            raise RuntimeError("deepagents returned no final answer")
        artifact = _parse_artifact_directive(answer)
        if artifact is not None:
            await self._create_artifact(context.runId, artifact)
        if stream_result.streamed_text:
            missing_delta = _completion_suffix(answer, stream_result.streamed_text)
            if missing_delta:
                await _append_answer_delta(self.backend_client, context.runId, missing_delta)
            await _append_answer_completed(self.backend_client, context.runId, answer)
        else:
            await self._emit_answer_events(context.runId, answer)
        await self.backend_client.complete_run(
            context.runId,
            RunComplete(
                finalAnswer=answer,
                intent=DEEP_AGENTS_INTENT,
                modelProviderCode="deepagents",
                modelName=getattr(self.model_client, "model_name", settings.model_name),
                consumedCredits=settings.agent_default_consumed_credits,
            ),
        )
        await self._emit_memory_candidate(context.runId, answer, artifact)

    def ensure_available(self) -> ModuleType:
        module = self.dependency_loader()
        if module is None:
            raise RuntimeError("deepagents package is not installed; enable the preview runtime only after installing it")
        return module

    @staticmethod
    def _load_deepagents() -> ModuleType | None:
        if importlib.util.find_spec("deepagents") is None:
            return None
        return importlib.import_module("deepagents")

    async def _fail(self, run_id: int, error_code: str, error_message: str) -> None:
        await self.backend_client.fail_run(run_id, RunFail(errorCode=error_code, errorMessage=error_message))

    def _chat_model(self):
        return getattr(self.model_client, "chat_model", self.model_client)

    async def _workspace_memory_items(self, context: RunContext) -> list[WorkspaceMemoryItem]:
        workspace_id = context.workspaceId
        if workspace_id is None:
            return []
        try:
            return await self.backend_client.retrieve_workspace_memory(workspace_id=workspace_id, query=context.message, limit=5)
        except Exception:
            return []

    async def _workspace_file_context(self, context: RunContext) -> WorkspaceFileContext:
        workspace_file_context = build_workspace_file_context(context)
        if workspace_file_context.is_empty:
            return workspace_file_context
        await self.backend_client.append_event(
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

    async def _emit_answer_events(self, run_id: int, answer: str) -> None:
        for chunk in _chunks(answer, 80):
            await _append_answer_delta(self.backend_client, run_id, chunk)
        await _append_answer_completed(self.backend_client, run_id, answer)

    async def _create_artifact(self, run_id: int, artifact: "ArtifactDirective") -> None:
        response = await self.backend_client.create_run_artifact(
            run_id=run_id,
            filename=artifact.filename,
            content=artifact.content,
            content_type=artifact.content_type,
        )
        if _backend_emitted_workspace_file_created(response):
            return
        await self.backend_client.append_event(
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

    async def _emit_memory_candidate(self, run_id: int, answer: str, artifact: "ArtifactDirective | None") -> None:
        content = artifact.content if artifact is not None else answer
        title = artifact.filename if artifact is not None else _memory_candidate_title(content)
        await self.backend_client.append_event(
            run_id,
            RunEventCreate(
                eventType=MEMORY_CANDIDATE_CREATED,
                eventText=title,
                eventJson={"title": title, "content": content, "sourceRunId": run_id},
            ),
        )


async def _invoke_agent(agent, payload: dict, config: dict | None = None):
    if hasattr(agent, "ainvoke"):
        result = agent.ainvoke(payload, config=config)
    elif hasattr(agent, "invoke"):
        result = agent.invoke(payload, config=config)
    else:
        raise RuntimeError("deepagents agent does not expose invoke or ainvoke")
    if inspect.isawaitable(result):
        return await result
    return result


@dataclass(frozen=True)
class AgentStreamResult:
    result: Any | None
    streamed_text: str


async def _invoke_agent_streaming(agent, payload: dict, run_id: int, backend_client, config: dict | None = None) -> AgentStreamResult:
    stream_result = await _try_stream_events(agent, payload, run_id, backend_client, config)
    if stream_result is not None:
        return stream_result
    result = await _invoke_agent(agent, payload, config=config)
    return AgentStreamResult(result=result, streamed_text="")


async def _try_stream_events(agent, payload: dict, run_id: int, backend_client, config: dict | None = None) -> AgentStreamResult | None:
    for method_name in ("stream_events", "astream_events"):
        method = getattr(agent, method_name, None)
        if not callable(method):
            continue
        try:
            stream = await _call_stream_events(method, payload, config=config)
        except TypeError:
            continue
        result = await _consume_agent_event_stream(stream, run_id, backend_client)
        if result.streamed_text or result.result is not None:
            return result
    return None


async def _call_stream_events(method, payload: dict, config: dict | None = None):
    try:
        stream = method(payload, config=config, version="v2")
    except TypeError:
        try:
            stream = method(payload, config=config)
        except TypeError:
            stream = method(payload)
    if inspect.isawaitable(stream):
        return await stream
    return stream


async def _consume_agent_event_stream(stream, run_id: int, backend_client) -> AgentStreamResult:
    chunks: list[str] = []
    result: Any | None = None
    projection_messages = getattr(stream, "messages", None)
    if projection_messages is not None:
        if callable(projection_messages):
            projection_messages = projection_messages()
        async for message in _iterate_maybe_async(projection_messages):
            chunk = _stream_delta_content(message)
            if chunk:
                chunks.append(chunk)
                await _append_answer_delta(backend_client, run_id, chunk)
        result = await _stream_output(stream)
        return AgentStreamResult(result=result, streamed_text="".join(chunks))
    async for event in _iterate_maybe_async(stream):
        chunk = _event_delta(event)
        if chunk:
            chunks.append(chunk)
            await _append_answer_delta(backend_client, run_id, chunk)
        final_output = _event_final_output(event)
        if final_output is not None:
            result = final_output
    return AgentStreamResult(result=result, streamed_text="".join(chunks))


async def _iterate_maybe_async(value):
    if inspect.isawaitable(value):
        value = await value
    if isinstance(value, AsyncIterable):
        async for item in value:
            yield item
        return
    if isinstance(value, Iterable) and not isinstance(value, (str, bytes, dict)):
        for item in value:
            yield item
        return
    return


async def _stream_output(stream):
    output = getattr(stream, "output", None)
    if callable(output):
        output = output()
    if inspect.isawaitable(output):
        output = await output
    return output


async def _append_answer_delta(backend_client, run_id: int, chunk: str) -> None:
    await backend_client.append_event(
        run_id,
        RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
    )


async def _append_answer_completed(backend_client, run_id: int, answer: str) -> None:
    await backend_client.append_event(
        run_id,
        RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
    )


def _event_delta(event) -> str:
    if not isinstance(event, dict):
        return _stream_delta_content(event)
    event_name = event.get("event") or event.get("eventType")
    if event_name not in {"message.delta", "on_chat_model_stream", "on_llm_stream"}:
        return ""
    data = event.get("data")
    if isinstance(data, dict):
        for key in ("delta", "text", "content"):
            value = data.get(key)
            if isinstance(value, str):
                return value
        return _stream_delta_content(data.get("chunk") or data.get("message") or data.get("output"))
    return _stream_delta_content(event.get("delta") or event.get("content"))


def _event_final_output(event) -> Any | None:
    if not isinstance(event, dict):
        return None
    event_name = event.get("event") or event.get("eventType")
    if event_name not in {"message.completed", "on_chain_end", "on_graph_end", "run.completed"}:
        return None
    data = event.get("data")
    if isinstance(data, dict):
        for key in ("output", "result", "final_answer", "finalAnswer"):
            if key in data:
                return data[key]
    for key in ("output", "result", "final_answer", "finalAnswer"):
        if key in event:
            return event[key]
    return None


def _stream_delta_content(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("delta", "text", "content"):
            content = value.get(key)
            if isinstance(content, str):
                return content
        content = value.get("chunk") or value.get("message")
    else:
        content = getattr(value, "content", None)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts)
    return ""


def _completion_suffix(answer: str, streamed_text: str) -> str:
    if not answer or answer == streamed_text:
        return ""
    return answer[len(streamed_text) :] if answer.startswith(streamed_text) else ""


class SubagentTraceCallbackHandler(AsyncCallbackHandler):
    def __init__(self, run_id: int, backend_client) -> None:
        self.run_id = run_id
        self.backend_client = backend_client
        self._subagent_names_by_tool_run: dict[str, str] = {}

    async def on_tool_start(
        self,
        serialized: dict[str, Any],
        input_str: str,
        *,
        run_id,
        inputs: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        if not _is_task_tool(serialized):
            return
        subagent_name = _subagent_name_from_inputs(inputs)
        if not subagent_name:
            return
        self._subagent_names_by_tool_run[str(run_id)] = subagent_name
        task_description = _task_description_from_inputs(inputs, input_str)
        await self._append_event(
            SUBAGENT_STARTED,
            subagent_name,
            {
                "subagentName": subagent_name,
                "taskDescription": task_description,
            },
        )

    async def on_tool_end(self, output: Any, *, run_id, **kwargs: Any) -> None:
        subagent_name = self._subagent_names_by_tool_run.pop(str(run_id), None)
        if not subagent_name:
            return
        await self._append_event(
            SUBAGENT_COMPLETED,
            subagent_name,
            {
                "subagentName": subagent_name,
            },
        )

    async def on_tool_error(self, error: BaseException, *, run_id, **kwargs: Any) -> None:
        subagent_name = self._subagent_names_by_tool_run.pop(str(run_id), None)
        if not subagent_name:
            return
        await self._append_event(
            SUBAGENT_FAILED,
            subagent_name,
            {
                "subagentName": subagent_name,
                "error": str(error),
            },
        )

    async def _append_event(self, event_type: str, event_text: str, event_json: dict[str, Any]) -> None:
        try:
            await self.backend_client.append_event(
                self.run_id,
                RunEventCreate(eventType=event_type, eventText=event_text, eventJson=event_json),
            )
        except Exception:
            return


def _is_task_tool(serialized: dict[str, Any]) -> bool:
    return serialized.get("name") == "task"


def _subagent_name_from_inputs(inputs: dict[str, Any] | None) -> str:
    if not isinstance(inputs, dict):
        return ""
    value = inputs.get("subagent_type") or inputs.get("subagentType")
    return value if isinstance(value, str) else ""


def _task_description_from_inputs(inputs: dict[str, Any] | None, input_str: str) -> str:
    if isinstance(inputs, dict) and isinstance(inputs.get("description"), str):
        return inputs["description"]
    return input_str


def _messages(context: RunContext, workspace_memory_context: str = "", workspace_file_context: str = "") -> list[dict[str, str]]:
    messages = [_message(message) for message in context.history]
    if workspace_memory_context:
        messages.append({"role": "system", "content": workspace_memory_context})
    if workspace_file_context:
        messages.append({"role": "system", "content": workspace_file_context})
    messages.append({"role": "user", "content": context.message})
    return messages


def _message(message: ChatMessage) -> dict[str, str]:
    role = message.role.lower()
    if role in {"assistant", "ai"}:
        role = "assistant"
    elif role != "system":
        role = "user"
    return {"role": role, "content": message.content}


def _extract_final_answer(result) -> str:
    if isinstance(result, dict):
        for key in ("final_answer", "finalAnswer", "output"):
            value = result.get(key)
            if isinstance(value, str) and value.strip():
                return value
        messages = result.get("messages")
        if isinstance(messages, list):
            for message in reversed(messages):
                content = _message_content(message)
                if content:
                    return content
    content = _message_content(result)
    if content:
        return content
    raise RuntimeError("deepagents returned no final answer")


@dataclass(frozen=True)
class ArtifactDirective:
    filename: str
    content: str
    content_type: str


def _parse_artifact_directive(answer: str) -> ArtifactDirective | None:
    match = re.match(r"^\[artifact:([^\]\r\n]+)\]\r?\n([\s\S]*)\Z", answer)
    if not match:
        return None
    filename = match.group(1).strip()
    content = match.group(2)
    if not filename:
        return None
    return ArtifactDirective(filename=filename, content=content, content_type=_content_type_for_filename(filename))


def _content_type_for_filename(filename: str) -> str:
    lower_filename = filename.lower()
    if lower_filename.endswith(".md") or lower_filename.endswith(".markdown"):
        return "text/markdown"
    if lower_filename.endswith(".json"):
        return "application/json"
    if lower_filename.endswith(".html") or lower_filename.endswith(".htm"):
        return "text/html"
    if lower_filename.endswith(".csv"):
        return "text/csv"
    return "text/plain"


def _backend_emitted_workspace_file_created(response: dict[str, Any]) -> bool:
    if response.get("eventEmitted") is True:
        return True
    if response.get("emittedEventType") == WORKSPACE_FILE_CREATED:
        return True
    event = response.get("event")
    return isinstance(event, dict) and event.get("eventType") == WORKSPACE_FILE_CREATED


def _memory_candidate_title(content: str) -> str:
    for line in content.splitlines():
        title = line.strip().lstrip("#").strip()
        if title:
            return title[:120]
    return "Deep Agents result"


def _message_content(message) -> str:
    if isinstance(message, dict):
        content = message.get("content")
    else:
        content = getattr(message, "content", None)
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts).strip()
    return ""


def _chunks(value: str, size: int) -> list[str]:
    return [value[index : index + size] for index in range(0, len(value), size)] or [""]


def _format_file_context(context: RunContext) -> str:
    ready_chunks = [chunk for chunk in context.agentFileChunks if chunk.contentText.strip()]
    if ready_chunks:
        sections = ["Relevant excerpts retrieved from the user's uploaded files:"]
        for chunk in ready_chunks:
            sections.append(f"\n[File: {chunk.originalFilename}, chunk {chunk.chunkIndex}]\n{chunk.contentText[:4000]}")
        return "\n".join(sections)
    ready_files = [file for file in context.agentFiles if file.status == "READY" and file.extractedText.strip()]
    if not ready_files:
        return ""
    sections = ["Uploaded file context:"]
    for file in ready_files:
        sections.append(f"\n[File: {file.originalFilename}]\n{file.extractedText[:12000]}")
    return "\n".join(sections)


def _format_workspace_memory_context(items: list[WorkspaceMemoryItem]) -> str:
    active_items = [item for item in items[:5] if item.content.strip() or item.title.strip()]
    if not active_items:
        return ""
    sections = [
        "Workspace memory: Long-term memories retrieved for this workspace. Use them when relevant, and cite them with memory:<id> labels when they inform the answer."
    ]
    for item in active_items:
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        sections.append(f"\n[memory:{item.id}] {title} ({memory_type}, score={item.score})\n{content[:4000]}")
    return "\n".join(sections)


def _format_workspace_memory_items(items: list[WorkspaceMemoryItem]) -> list[str]:
    memory_items = []
    for item in items[:5]:
        if not item.content.strip() and not item.title.strip():
            continue
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        memory_items.append(f"[memory:{item.id}] {title} ({memory_type}, score={item.score})\n{content[:4000]}")
    return memory_items
