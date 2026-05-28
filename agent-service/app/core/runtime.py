import logging

from app.clients.backend_client import BackendClient, BackendClientError
from app.clients.model_client import ModelClient, ModelClientError
from app.config import Settings
from app.core.schemas import RunFail
from app.runtime.router import RuntimeRouter
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
        runtime_router_factory=RuntimeRouter,
        default_settings: Settings | None = None,
    ) -> None:
        self.backend = backend_client
        self.model_client = model_client
        self.model_client_factory = model_client_factory
        self.runtime_router_factory = runtime_router_factory
        self.default_settings = default_settings or Settings()

    async def execute_run(self, run_id: int) -> None:
        try:
            context = await self.backend.get_run_context(run_id)
            if getattr(context, "status", None) in TERMINAL_RUN_STATUSES:
                return
            model_client = await self._model_client(context)
            engine = self.runtime_router_factory(
                self.backend,
                model_client,
                deep_agents_enabled=self.default_settings.agent_deep_agents_enabled,
            ).select_engine(message=context.message)
            await engine.run(context)
        except BackendClientError as exc:
            logger.exception("Agent run failed while calling backend, runId=%s", run_id)
            await self._fail(run_id, "BACKEND_CALL_FAILED", str(exc))
        except ModelClientError as exc:
            logger.exception("Agent run failed while calling model provider, runId=%s", run_id)
            await self._fail(run_id, "MODEL_CALL_FAILED", str(exc))
        except ToolExecutionError as exc:
            logger.exception("Agent run failed while executing tool, runId=%s", run_id)
            await self._fail(run_id, "TOOL_CALL_FAILED", str(exc))
        except Exception as exc:  # pragma: no cover - defensive runtime boundary.
            logger.exception("Agent run failed with internal error, runId=%s", run_id)
            await self._fail(run_id, "AGENT_INTERNAL_ERROR", str(exc))

    async def execute_confirmed_tool(self, run_id: int, tool_code: str) -> None:
        try:
            context = await self.backend.get_run_context(run_id)
            if getattr(context, "status", None) in TERMINAL_RUN_STATUSES:
                return
            model_client = await self._model_client(context)
            engine = self.runtime_router_factory(
                self.backend,
                model_client,
                deep_agents_enabled=self.default_settings.agent_deep_agents_enabled,
            ).select_engine(message=context.message)
            await engine.run_confirmed_tool(context, tool_code)
        except BackendClientError as exc:
            logger.exception("Agent confirmed-tool run failed while calling backend, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "BACKEND_CALL_FAILED", str(exc))
        except ModelClientError as exc:
            logger.exception("Agent confirmed-tool run failed while calling model provider, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "MODEL_CALL_FAILED", str(exc))
        except ToolExecutionError as exc:
            logger.exception("Agent confirmed-tool run failed while executing tool, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "TOOL_CALL_FAILED", str(exc))
        except Exception as exc:  # pragma: no cover - defensive runtime boundary.
            logger.exception("Agent confirmed-tool run failed with internal error, runId=%s, toolCode=%s", run_id, tool_code)
            await self._fail(run_id, "AGENT_INTERNAL_ERROR", str(exc))

    async def _model_client(self, context=None) -> ModelClient:
        if self.model_client is not None:
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
        self.model_client = self.model_client_factory(settings)
        return self.model_client

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
