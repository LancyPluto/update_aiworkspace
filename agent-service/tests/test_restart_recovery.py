"""Restart tests instantiate a fresh engine against persisted native checkpoints."""
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from app.core.execution import RecoveryDeferred, RecoveryUnsafe, execution_identity, recovering
from app.core.runtime import AgentRuntime
from app.core.schemas import RunContext, ToolCallResponse, TaskDetailResponse, TaskResultResponse
from app.runtime.agent_graph import AgentGraphEngine
from app.clients.model_client import ChatToolCall, ChatTurnResult
from tests.test_agent_graph_engine import FakeBackend, FakeModel, ToolDescriptor

class DurableBackend(FakeBackend):
    def __init__(self):
        super().__init__()
        self.runtime_json = None
        self.approvals = []
        self.outcomes = []
        self.saved_calls = {}
        self.crash_after_task = False
        self.run_context = RunContext(runId=9,sessionId=2,userId=3,status="RUNNING",message="处理需求",creditBudget=100)
    async def save_recovery_runtime(self, run_id, state):
        self.runtime_json = json.dumps(state)
    async def recovery_snapshot(self, run_id):
        return {"status": self.run_context.status,"checkpoint": await self.load_native_graph_checkpoint(run_id, f"agent-run:{run_id}") or {},"runtimeJson": self.runtime_json,"confirmations": self.approvals,"hasStarted": bool(self.events),"toolCalls": list(self.saved_calls.values())}
    async def recovery_outcome(self, run_id, error=None, *, permanent=False, waiting=False):
        self.outcomes.append((error, permanent, waiting))
        if waiting:
            self.run_context.status = "WAITING_USER_CONFIRMATION"
    async def create_tool_call(self, run_id, payload):
        if payload.idempotencyKey not in self.saved_calls:
            self.saved_calls[payload.idempotencyKey] = ToolCallResponse(id=1,runId=run_id,toolCode=payload.toolCode,status="RUNNING",argumentsJson=payload.argumentsJson)
        return self.saved_calls[payload.idempotencyKey]
    async def create_task(self, payload):
        task = await super().create_task(payload)
        if self.crash_after_task:
            self.crash_after_task = False
            raise RecoveryDeferred("simulated death after task commit")
        return task
    async def find_task_by_request(self, run_id, user_id, request_id):
        return await self.get_task_detail(user_id,100) if self.tasks else None
    async def bind_tool_call_task(self, call_id, task_id):
        for call in self.saved_calls.values():
            call.taskId = task_id
    async def get_task_detail(self, user_id, task_id):
        return TaskDetailResponse(taskId=task_id,status="SUCCESS",result=TaskResultResponse(resourceType="text",contentText="saved result"))
    async def complete_tool_call(self, call_id, payload):
        await super().complete_tool_call(call_id,payload)
        for call in self.saved_calls.values():
            call.status = "SUCCESS"
            call.resultJson = payload.resultJson

def tool(auto=True):
    return ToolDescriptor(toolCode="text_tool",toolName="Text",description="text",autoCallable=auto,inputSchema={"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]})

def call_model(call_id="stable-call"):
    return FakeModel([ChatTurnResult(content="",tool_calls=[ChatToolCall(id=call_id,name="agent_tool__text_tool",arguments={"prompt":"hello"})])])

@pytest.mark.asyncio
async def test_crash_after_task_creation_reuses_task_and_budget():
    backend = DurableBackend()
    backend.run_context.availableTools = [tool()]
    backend.crash_after_task = True
    with pytest.raises(RecoveryDeferred):
        await AgentGraphEngine(backend,call_model()).run(backend.run_context)
    assert len(backend.tasks) == 1
    assert not backend.failed_runs
    second = AgentGraphEngine(backend,FakeModel([ChatTurnResult(content="finished",tool_calls=[])]))
    token = recovering.set(True)
    try:
        await second.recover(backend.run_context)
    finally:
        recovering.reset(token)
    assert len(backend.tasks) == len(backend.saved_calls) == 1
    assert second._budget.tool_calls == 1
    assert second._budget.model_calls == 2
    assert backend.completed_runs[-1].finalAnswer == "finished"

@pytest.mark.asyncio
async def test_completed_graph_only_replays_settlement():
    backend = DurableBackend()
    original = backend.complete_run
    backend.complete_run = AsyncMock(side_effect=RecoveryDeferred("death before settlement"))
    with pytest.raises(RecoveryDeferred):
        await AgentGraphEngine(backend,FakeModel([ChatTurnResult(content="durable answer",tool_calls=[])])).run(backend.run_context)
    backend.complete_run = original
    model = FakeModel([])
    await AgentGraphEngine(backend,model).recover(backend.run_context)
    assert not model.calls
    assert backend.completed_runs[-1].finalAnswer == "durable answer"

@pytest.mark.asyncio
async def test_confirmation_requires_matching_durable_approval():
    backend = DurableBackend()
    backend.run_context.availableTools = [tool(False)]
    await AgentGraphEngine(backend,call_model("approve-this")).run(backend.run_context)
    backend.approvals = [{"callId":"different-call","toolCode":"text_tool","approvedAt":"now"}]
    await AgentGraphEngine(backend,FakeModel([])).recover(backend.run_context)
    assert backend.outcomes[-1][2] is True
    assert not backend.tasks
    backend.run_context.status = "RUNNING"
    backend.approvals = [{"callId":"approve-this","toolCode":"text_tool","approvedAt":"now"}]
    await AgentGraphEngine(backend,FakeModel([ChatTurnResult(content="done",tool_calls=[])])).recover(backend.run_context)
    assert len(backend.tasks) == 1
    assert backend.completed_runs

@pytest.mark.asyncio
async def test_missing_checkpoint_with_execution_evidence_fails_closed():
    backend = DurableBackend()
    backend.runtime_json = json.dumps({"version":1})
    with pytest.raises(RecoveryUnsafe):
        await AgentGraphEngine(backend,FakeModel([])).recover(backend.run_context)
    assert not backend.tasks

@pytest.mark.asyncio
async def test_renewal_failure_cancels_graph_without_failing_or_cancelling_task(monkeypatch):
    runtime = AgentRuntime(SimpleNamespace(renew_execution_lease=AsyncMock(return_value=False)))
    entered = asyncio.Event()
    async def graph():
        entered.set()
        await asyncio.Future()
    task = asyncio.create_task(graph())
    await entered.wait()
    monkeypatch.setattr("app.core.runtime.asyncio.sleep",AsyncMock())
    await runtime._renew_lease(9,"owner",task)
    with pytest.raises(asyncio.CancelledError):
        await task

@pytest.mark.asyncio
async def test_duplicate_recovery_delivery_must_adopt_once():
    backend = SimpleNamespace(adopt_execution_lease=AsyncMock(return_value=False),get_run_context=AsyncMock())
    await AgentRuntime(backend).recover_run(9,"already-consumed-owner")
    backend.get_run_context.assert_not_called()
    assert execution_identity.get() is None

class ProcessBackend(DurableBackend):
    """A tiny durable transport substitute; graph serialization remains the production saver."""
    def __init__(self, path, crash=False):
        super().__init__()
        from pathlib import Path
        self.path = Path(path)
        self.crash = crash
        self.run_context.availableTools = [tool()]
        if self.path.exists():
            data = json.loads(self.path.read_text())
            self.runtime_json = data["runtime"]
            self.native_checkpoints = data["checkpoints"]
            self.native_writes = {tuple(key): value for key, value in data["writes"]}
            self.saved_calls = {key: ToolCallResponse.model_validate(value) for key, value in data["calls"].items()}
            self.tasks = [None] * data["taskCount"]
    def persist(self):
        import os
        data = {"runtime":self.runtime_json,"checkpoints":self.native_checkpoints,
                "writes":[[list(key),value] for key,value in self.native_writes.items()],
                "calls":{key:value.model_dump(mode="json") for key,value in self.saved_calls.items()},"taskCount":len(self.tasks)}
        temporary = self.path.with_suffix(".pending")
        with temporary.open("w") as output:
            json.dump(data,output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary,self.path)
    async def save_recovery_runtime(self,*args):
        await super().save_recovery_runtime(*args)
        self.persist()
    async def save_native_graph_checkpoint(self,*args,**kwargs):
        await super().save_native_graph_checkpoint(*args,**kwargs)
        self.persist()
    async def save_native_graph_checkpoint_writes(self,*args,**kwargs):
        await super().save_native_graph_checkpoint_writes(*args,**kwargs)
        self.persist()
    async def create_tool_call(self,*args):
        call = await super().create_tool_call(*args)
        self.persist()
        return call
    async def create_task(self,*args):
        result = await super().create_task(*args)
        self.persist()
        if self.crash:
            import os
            os._exit(73)  # No finally blocks, lease release, binding, or checkpoint cleanup.
        return result

def crash_worker(path):
    backend = ProcessBackend(path,crash=True)
    asyncio.run(AgentGraphEngine(backend,call_model()).run(backend.run_context))

@pytest.mark.asyncio
async def test_real_process_exit_after_task_commit_recovers_from_disk(tmp_path):
    import subprocess
    import sys
    from pathlib import Path
    path = tmp_path / "backend.json"
    process = subprocess.run([sys.executable,"-c","from tests.test_restart_recovery import crash_worker; import sys; crash_worker(sys.argv[1])",str(path)],capture_output=True,timeout=30,cwd=Path(__file__).resolve().parents[1])
    assert process.returncode == 73, process.stderr.decode()
    backend = ProcessBackend(path)
    assert len(backend.tasks) == 1
    engine = AgentGraphEngine(backend,FakeModel([ChatTurnResult(content="recovered across process exit",tool_calls=[])]))
    token = recovering.set(True)
    try:
        await engine.recover(backend.run_context)
    finally:
        recovering.reset(token)
    assert len(backend.tasks) == 1
    assert engine._budget.tool_calls == 1
    assert backend.completed_runs[-1].finalAnswer == "recovered across process exit"

@pytest.mark.asyncio
async def test_shared_backend_client_keeps_owners_task_local():
    import httpx
    from app.clients.backend_client import BackendClient
    from app.config import Settings
    seen = {}
    async def transport(request):
        seen[request.headers["X-Agent-Run-Id"]] = request.headers["X-Agent-Execution-Owner"]
        return httpx.Response(200,json={"code":"SUCCESS","data":{}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        client = BackendClient(Settings(backend_internal_base_url="http://backend"),http_client=http)
        async def execute(run_id, owner):
            token = execution_identity.set((run_id,owner))
            try:
                await client._request("POST",f"/api/internal/v1/agent/runs/{run_id}/events",{})
            finally:
                execution_identity.reset(token)
        await asyncio.gather(execute(1,"owner-one"),execute(2,"owner-two"))
    assert seen == {"1":"owner-one","2":"owner-two"}
    assert execution_identity.get() is None

@pytest.mark.asyncio
async def test_recovery_query_failure_preserves_external_task():
    from app.clients.backend_client import BackendClientError
    backend = DurableBackend()
    backend.run_context.availableTools = [tool()]
    backend.get_task_detail = AsyncMock(side_effect=BackendClientError("temporary query outage"))
    backend.cancel_task = AsyncMock()
    token = recovering.set(True)
    try:
        with pytest.raises(RecoveryDeferred):
            await AgentGraphEngine(backend,call_model()).run(backend.run_context)
    finally:
        recovering.reset(token)
    backend.cancel_task.assert_not_called()
    assert not backend.failed_runs
    assert not backend.failed_tool_calls
    assert len(backend.tasks) == 1
