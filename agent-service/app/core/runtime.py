import logging
import time

from app.observability.metrics import record_run_completed, record_run_started, record_tool_call
from app.observability.model_request_audit import AuditedModelClient, ModelRequestAuditRecorder
from app.clients.backend_client import BackendClient, BackendClientError
from app.clients.model_client import ModelClient, ModelClientError
from app.config import Settings
from app.core.schemas import RunFail
from app.runtime.context_manager import ContextManager
from app.runtime.conversation_summary import ensure_rolling_conversation_summary
from app.runtime.agent_graph import AgentGraphEngine
from app.tools.backend_tool import ToolExecutionError

TERMINAL_RUN_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"}
logger = logging.getLogger(__name__)


class AgentRuntime:
    def __init__(
        self,
        backend_client: BackendClient,
        model_client: ModelClient | None = None,
        *,
        model_client_factory=ModelClient,
        default_settings: Settings | None = None,
    ) -> None:
        self.backend = backend_client
        self.model_client = model_client
        self._injected_model_client = model_client is not None
        self.model_client_factory = model_client_factory
        self.default_settings = default_settings or Settings()

    async def execute_run(self, run_id: int) -> None:
        entrypoint = "run"
        engine_name = "unknown"
        started_at = time.perf_counter()
        audited_model = None
        record_run_started(entrypoint)
        try:
            context = await self.backend.get_run_context(run_id)
            if getattr(context, "status", None) in TERMINAL_RUN_STATUSES:
                record_run_completed(entrypoint, "skipped", "terminal_status", engine_name, time.perf_counter() - started_at)
                return
            model_client = await self._model_client(context)
            if _supports_model_requests(model_client):
                audited_model = AuditedModelClient(model_client, ModelRequestAuditRecorder(self.backend, run_id))
                model_client = audited_model
            context = await self._with_rolling_summary(context, model_client)
            engine = AgentGraphEngine(self.backend, model_client)
            engine_name = "AgentGraphEngine"
            await engine.run(context)
            record_run_completed(entrypoint, "success", "none", engine_name, time.perf_counter() - started_at)
        except BackendClientError as exc:
            logger.exception("Agent run failed while calling backend, runId=%s", run_id)
            await self._fail(run_id, "BACKEND_CALL_FAILED", str(exc))
            record_run_completed(entrypoint, "failed", "BACKEND_CALL_FAILED", engine_name, time.perf_counter() - started_at)
        except ModelClientError as exc:
            logger.exception("Agent run failed while calling model provider, runId=%s", run_id)
            await self._fail(run_id, "MODEL_CALL_FAILED", str(exc))
            record_run_completed(entrypoint, "failed", "MODEL_CALL_FAILED", engine_name, time.perf_counter() - started_at)
        except ToolExecutionError as exc:
            error_code = exc.error_code or "TOOL_CALL_FAILED"
            logger.exception("Agent run failed while executing tool, runId=%s", run_id)
            await self._fail(run_id, error_code, str(exc))
            record_run_completed(entrypoint, "failed", error_code, engine_name, time.perf_counter() - started_at)
        except Exception as exc:  # pragma: no cover - defensive runtime boundary.
            logger.exception("Agent run failed with internal error, runId=%s", run_id)
            await self._fail(run_id, "AGENT_INTERNAL_ERROR", str(exc))
            record_run_completed(entrypoint, "failed", "AGENT_INTERNAL_ERROR", engine_name, time.perf_counter() - started_at)
        finally:
            if audited_model is not None:
                audited_model.schedule_audit_flush()

    async def execute_confirmed_tool(self, run_id: int, tool_code: str) -> None:
        entrypoint = "confirmed_tool"
        engine_name = "unknown"
        started_at = time.perf_counter()
        audited_model = None
        record_run_started(entrypoint)
        try:
            context = await self.backend.get_run_context(run_id)
            if getattr(context, "status", None) in TERMINAL_RUN_STATUSES:
                record_run_completed(entrypoint, "skipped", "terminal_status", engine_name, time.perf_counter() - started_at)
                return
            model_client = await self._model_client(context)
            if _supports_model_requests(model_client):
                audited_model = AuditedModelClient(model_client, ModelRequestAuditRecorder(self.backend, run_id))
                model_client = audited_model
            context = await self._with_rolling_summary(context, model_client)
            engine = AgentGraphEngine(self.backend, model_client)
            engine_name = "AgentGraphEngine"
            await engine.run_confirmed_tool(context, tool_code)
            record_tool_call(tool_code, "success")
            record_run_completed(entrypoint, "success", "none", engine_name, time.perf_counter() - started_at)
        except BackendClientError as exc:
            logger.exception("Agent confirmed-tool run failed while calling backend, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "BACKEND_CALL_FAILED", str(exc))
            record_tool_call(tool_code, "failed")
            record_run_completed(entrypoint, "failed", "BACKEND_CALL_FAILED", engine_name, time.perf_counter() - started_at)
        except ModelClientError as exc:
            logger.exception("Agent confirmed-tool run failed while calling model provider, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "MODEL_CALL_FAILED", str(exc))
            record_tool_call(tool_code, "failed")
            record_run_completed(entrypoint, "failed", "MODEL_CALL_FAILED", engine_name, time.perf_counter() - started_at)
        except ToolExecutionError as exc:
            error_code = exc.error_code or "TOOL_CALL_FAILED"
            logger.exception("Agent confirmed-tool run failed while executing tool, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, error_code, str(exc))
            record_tool_call(tool_code, "failed")
            record_run_completed(entrypoint, "failed", error_code, engine_name, time.perf_counter() - started_at)
        except Exception as exc:  # pragma: no cover - defensive runtime boundary.
            logger.exception("Agent confirmed-tool run failed with internal error, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "AGENT_INTERNAL_ERROR", str(exc))
            record_tool_call(tool_code, "failed")
            record_run_completed(entrypoint, "failed", "AGENT_INTERNAL_ERROR", engine_name, time.perf_counter() - started_at)
        finally:
            if audited_model is not None:
                audited_model.schedule_audit_flush()

    async def debug_route(self, context):
        try:
            model_client = await self._model_client(context)
        except ModelClientError as exc:
            logger.warning("Route debug falls back to mock model because model config is unavailable: %s", exc)
            model_client = self.model_client_factory(Settings(model_provider="mock", model_name="mock"))
        return await AgentGraphEngine(self.backend, model_client).debug_route(context)

    async def _model_client(self, context=None) -> ModelClient:
        # Respect externally injected clients (tests/mocks), but avoid reusing
        # a runtime-created client across different runs/model selections.
        if self._injected_model_client and self.model_client is not None:
            return self.model_client
        config = getattr(context, "modelConfig", None) if context is not None else None
        if config is None:
            config = await self.backend.get_active_model_config()
        if not config.enabled:
            settings = Settings(model_provider="mock", model_name="mock")
        else:
            settings = Settings(
                model_provider=config.provider,
                model_name=config.modelName,
                model_api_base_url=config.baseUrl or self.default_settings.model_api_base_url,
                model_api_key=config.apiKey,
                minimax_group_id=config.minimaxGroupId or "",
                model_timeout_seconds=config.timeoutSeconds,
            )
        return self.model_client_factory(settings)

    async def _with_rolling_summary(self, context, model_client):
        context_manager = ContextManager.from_settings(self.default_settings, getattr(context, "runtimeSettings", None))
        return await ensure_rolling_conversation_summary(
            context=context,
            context_manager=context_manager,
            model=model_client,
            backend=self.backend,
        )

    async def _fail(self, run_id: int, error_code: str, error_message: str) -> None:
        try:
            usage = getattr(self.model_client, "usage", None)
            prompt_tokens = 0
            completion_tokens = 0
            if isinstance(usage, dict):
                prompt_tokens = max(0, int(usage.get("promptTokens") or 0))
                completion_tokens = max(0, int(usage.get("completionTokens") or 0))
            consumed_credits = self.default_settings.agent_default_consumed_credits if prompt_tokens or completion_tokens else None
            await self.backend.fail_run(
                run_id,
                RunFail(
                    errorCode=error_code,
                    errorMessage=error_message,
                    consumedCredits=consumed_credits,
                    promptTokens=prompt_tokens,
                    completionTokens=completion_tokens,
                ),
            )
        except Exception:
            logger.exception(
                "Could not report failed agent run to backend, runId=%s, errorCode=%s, originalError=%s",
                run_id,
                error_code,
                error_message,
            )


def _supports_model_requests(model_client) -> bool:
    return any(callable(getattr(model_client, name, None)) for name in ("chat", "chat_turn", "chat_stream", "chat_stream_parts"))
