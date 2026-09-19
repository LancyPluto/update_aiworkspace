from app.core.execution import execution_identity, recovering, LeaseLost, RecoveryDeferred, RecoveryUnsafe
import logging
import time
import asyncio
import uuid

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
        await self._execute_owned(run_id)

    async def execute_confirmed_tool(self, run_id: int, tool_code: str) -> None:
        # A matching durable approval is consumed by recovery, never inferred from this notification.
        await self._execute_owned(run_id, confirmation=tool_code)

    async def recover_run(self, run_id: int, owner_token: str) -> None:
        await self._execute_owned(run_id, owner_token=owner_token)

    async def _execute_owned(self, run_id: int, *, owner_token: str | None = None, confirmation: str | None = None) -> None:
        entrypoint = "recover" if owner_token else ("confirmed_tool" if confirmation else "run")
        started_at = time.perf_counter()
        audited_model = None
        engine = None
        if owner_token:
            lease = f"agent-service:{uuid.uuid4()}"
            if not await self.backend.adopt_execution_lease(run_id, owner_token, lease):
                return
        else:
            lease = await self._acquire_lease(run_id)
        if lease is False:
            return
        identity_token = execution_identity.set((run_id, lease) if isinstance(lease, str) else None)
        recovery_token = recovering.set(bool(owner_token or confirmation))
        parent = asyncio.current_task()
        lease_task = None
        preserve = False
        outcome = "success"
        record_run_started(entrypoint)
        try:
            if owner_token and not await self.backend.renew_execution_lease(run_id, lease, 60):
                raise LeaseLost()
            if isinstance(lease, str):
                lease_task = asyncio.create_task(self._renew_lease(run_id, lease, parent))
            context = await self.backend.get_run_context(run_id)
            if context.status in TERMINAL_RUN_STATUSES or context.status == "WAITING_USER_CONFIRMATION":
                return
            model_client = await self._model_client(context)
            if _supports_model_requests(model_client):
                audited_model = AuditedModelClient(model_client, ModelRequestAuditRecorder(self.backend, run_id))
                model_client = audited_model
            engine = AgentGraphEngine(self.backend, model_client)
            if owner_token or confirmation:
                await engine.recover(context)
            else:
                # Duplicate execute notifications after a crash must not reset an existing graph.
                loader = getattr(self.backend, "recovery_snapshot", None)
                snapshot = await loader(run_id) if loader else {}
                checkpoint = snapshot.get("checkpoint") or {}
                if checkpoint.get("checkpointId") or snapshot.get("hasStarted") or snapshot.get("toolCalls") or snapshot.get("runtimeJson") or snapshot.get("hasOperations"):
                    recovering.set(True)
                    await engine.recover(context)
                else:
                    context = await self._with_rolling_summary(context, model_client)
                    await engine.run(context)
        except (LeaseLost, asyncio.CancelledError):
            preserve = True
            outcome = "lease_lost"
            logger.warning("agent execution stopped after lease loss or shutdown runId=%s", run_id)
        except RecoveryUnsafe as exc:
            outcome = "unsafe"
            await self.backend.recovery_outcome(run_id, str(exc), permanent=True)
        except (RecoveryDeferred, BackendClientError) as exc:
            preserve = True
            outcome = "deferred"
            try:
                await self.backend.recovery_outcome(run_id, getattr(exc, "error_code", "") or type(exc).__name__)
            except (BackendClientError, LeaseLost):
                logger.warning("could not persist recovery outcome runId=%s", run_id)
        except Exception as exc:
            outcome = "failed"
            if engine is not None:
                await engine._fail_run(run_id, getattr(exc, "error_code", None) or "AGENT_INTERNAL_ERROR", str(exc))
            else:
                await self._fail(run_id, getattr(exc, "error_code", None) or "AGENT_INTERNAL_ERROR", str(exc))
        finally:
            if lease_task is not None:
                lease_task.cancel()
                await asyncio.gather(lease_task, return_exceptions=True)
            if audited_model is not None and not preserve:
                try:
                    await audited_model.flush_audit()
                except (Exception, LeaseLost):
                    logger.warning("audit flush interrupted runId=%s", run_id)
            if not preserve:
                await self._release_lease(run_id, lease)
            record_run_completed(entrypoint, outcome, "none", "AgentGraphEngine", time.perf_counter() - started_at)
            execution_identity.reset(identity_token)
            recovering.reset(recovery_token)

    async def _acquire_lease(self, run_id: int) -> str | None | bool:
        acquire = getattr(self.backend, "acquire_execution_lease", None)
        if not callable(acquire):
            return None
        owner = f"agent-service:{uuid.uuid4()}"
        return owner if await acquire(run_id, owner, 60) else False

    async def _release_lease(self, run_id: int, owner: str | None | bool) -> None:
        if not isinstance(owner, str):
            return
        try:
            await self.backend.release_execution_lease(run_id, owner)
        except Exception:
            logger.warning("failed to release agent execution lease runId=%s", run_id)

    async def _renew_lease(self, run_id: int, owner: str, execution_task=None) -> None:
        try:
            while True:
                await asyncio.sleep(20)
                if not await self.backend.renew_execution_lease(run_id, owner, 60):
                    raise LeaseLost()
        except asyncio.CancelledError:
            # LeaseLost is cancellation-shaped but must cancel the graph as well.
            if execution_task is not None and not asyncio.current_task().cancelling():
                execution_task.cancel()
        except Exception:
            if execution_task is not None:
                execution_task.cancel()

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
