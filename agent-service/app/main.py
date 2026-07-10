from fastapi import FastAPI
from fastapi import Request
import time

from app.api import health, internal_files, internal_market, internal_runs, metrics
from app.clients.backend_client import BackendClient
from app.clients.model_config_tester import ModelConfigTester
from app.config import Settings, settings as default_settings
from app.core.runtime import AgentRuntime
from app.observability.logging import configure_logging
from app.observability.metrics import record_http_request
from app.observability.trace import TRACE_ID_HEADER, reset_trace_id, resolve_trace_id, set_trace_id


def create_app(
    *,
    settings: Settings = default_settings,
    runtime=None,
    model_config_tester=None,
    execution_mode: str | None = None,
    verify_signature: bool | None = None,
) -> FastAPI:
    configure_logging(settings.log_level)
    app = FastAPI(title="AI Tool Market Agent Service", version="0.1.0")
    app.state.settings = settings
    app.state.execution_mode = execution_mode or settings.agent_execution_mode
    app.state.verify_signature = settings.agent_verify_internal_signature if verify_signature is None else verify_signature
    app.state.runtime = runtime or AgentRuntime(BackendClient(settings), default_settings=settings)
    app.state.model_config_tester = model_config_tester or ModelConfigTester()

    @app.middleware("http")
    async def trace_id_middleware(request: Request, call_next):
        trace_id = resolve_trace_id(request.headers.get(TRACE_ID_HEADER))
        token = set_trace_id(trace_id)
        start = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers[TRACE_ID_HEADER] = trace_id
            return response
        finally:
            reset_trace_id(token)
            record_http_request(request.method, request.url.path, status_code, time.perf_counter() - start)

    app.include_router(health.router)
    app.include_router(metrics.router)
    app.include_router(internal_runs.router)
    app.include_router(internal_files.router)
    app.include_router(internal_market.router)
    return app


app = create_app()


