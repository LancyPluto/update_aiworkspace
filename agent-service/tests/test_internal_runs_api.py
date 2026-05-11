from fastapi.testclient import TestClient

from app.main import create_app


class FakeRuntime:
    def __init__(self):
        self.executed = []
        self.confirmed = []

    async def execute_run(self, run_id: int):
        self.executed.append(run_id)

    async def execute_confirmed_tool(self, run_id: int, tool_code: str):
        self.confirmed.append((run_id, tool_code))


class FakeModelConfigTester:
    def __init__(self):
        self.payloads = []

    async def test(self, config):
        self.payloads.append(config)
        return {
            "success": True,
            "provider": config.provider,
            "modelName": config.modelName,
            "latencyMs": 12,
            "message": "ok",
            "sample": "pong",
        }


def test_health_endpoint_returns_ok():
    app = create_app()
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"service": "agent-service", "status": "ok"}


def test_execute_run_sync_mode_executes_runtime():
    runtime = FakeRuntime()
    app = create_app(runtime=runtime, execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post("/internal/v1/agent/runs/123/execute", json={})

    assert response.status_code == 200
    assert response.json() == {"runId": 123, "status": "accepted"}
    assert runtime.executed == [123]


def test_execute_run_rejects_missing_signature_when_enabled():
    app = create_app(runtime=FakeRuntime(), execution_mode="sync", verify_signature=True)
    client = TestClient(app)

    response = client.post("/internal/v1/agent/runs/123/execute", json={})

    assert response.status_code == 401


def test_confirm_tool_sync_mode_executes_runtime():
    runtime = FakeRuntime()
    app = create_app(runtime=runtime, execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post("/internal/v1/agent/runs/123/confirm-tool", json={"toolCode": "xiaohongshu_copywriting"})

    assert response.status_code == 200
    assert response.json() == {"runId": 123, "toolCode": "xiaohongshu_copywriting", "status": "accepted"}
    assert runtime.confirmed == [(123, "xiaohongshu_copywriting")]


def test_model_config_test_endpoint_invokes_tester():
    tester = FakeModelConfigTester()
    app = create_app(runtime=FakeRuntime(), model_config_tester=tester, execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post(
        "/internal/v1/agent/model-config/test",
        json={
            "provider": "mock",
            "modelName": "mock",
            "timeoutSeconds": 30,
            "enabled": True,
        },
    )

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["provider"] == "mock"
    assert response.json()["sample"] == "pong"
    assert tester.payloads[0].provider == "mock"


def test_parse_file_extracts_utf8_text_content():
    app = create_app(runtime=FakeRuntime(), execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post(
        "/internal/v1/files/parse",
        json={
            "filename": "product.txt",
            "contentType": "text/plain",
            "contentBase64": "5Lqn5ZOB6K+05piO77ya5qCH5YeG54mI5YyF5ZCrIDMg5Liq6aG555uu44CC",
        },
    )

    assert response.status_code == 200
    assert response.json()["filename"] == "product.txt"
    assert response.json()["text"] == "产品说明：标准版包含 3 个项目。"
