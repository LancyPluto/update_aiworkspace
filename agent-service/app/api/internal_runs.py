from fastapi import APIRouter, BackgroundTasks, HTTPException, Request

from app.config import Settings
from app.core.schemas import AgentModelConfig, RunContext
from app.security.signature import verify_signature

router = APIRouter()


@router.post("/internal/v1/agent/runs/{run_id}/execute")
async def execute_run(run_id: int, request: Request, background_tasks: BackgroundTasks):
    await _verify_internal_request(request)
    runtime = request.app.state.runtime
    if request.app.state.execution_mode == "background":
        background_tasks.add_task(runtime.execute_run, run_id)
    else:
        await runtime.execute_run(run_id)
    return {"runId": run_id, "status": "accepted"}


@router.post("/internal/v1/agent/runs/{run_id}/confirm-tool")
async def confirm_tool(run_id: int, request: Request, background_tasks: BackgroundTasks):
    await _verify_internal_request(request)
    payload = await request.json()
    tool_code = payload.get("toolCode")
    if not tool_code:
        raise HTTPException(status_code=400, detail="toolCode is required")
    runtime = request.app.state.runtime
    if request.app.state.execution_mode == "background":
        background_tasks.add_task(runtime.execute_confirmed_tool, run_id, tool_code)
    else:
        await runtime.execute_confirmed_tool(run_id, tool_code)
    return {"runId": run_id, "toolCode": tool_code, "status": "accepted"}


@router.post("/internal/v1/agent/model-config/test")
async def test_model_config(config: AgentModelConfig, request: Request):
    await _verify_internal_request(request)
    return await request.app.state.model_config_tester.test(config)


@router.post("/internal/v1/agent/route-debug")
async def debug_route(context: RunContext, request: Request):
    await _verify_internal_request(request)
    return await request.app.state.runtime.debug_route(context)


async def _verify_internal_request(request: Request):
    settings: Settings = request.app.state.settings
    body = await request.body()
    if request.app.state.verify_signature:
        ok = verify_signature(
            request.method,
            request.url.path,
            request.headers.get("X-Internal-Timestamp"),
            request.headers.get("X-Internal-Nonce"),
            request.headers.get("X-Internal-Signature"),
            body,
            settings.internal_api_token,
        )
        if not ok:
            raise HTTPException(status_code=401, detail="invalid internal signature")
