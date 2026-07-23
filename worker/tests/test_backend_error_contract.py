import json

import pytest
import requests

from client.backend_client import BackendClient, BackendClientError


def _response(status: int, payload: dict, trace_id: str = "trace-header") -> requests.Response:
    response = requests.Response()
    response.status_code = status
    response._content = json.dumps(payload).encode("utf-8")
    response.headers["Content-Type"] = "application/json"
    response.headers["X-Request-Id"] = trace_id
    return response


def test_backend_client_parses_v2_admin_error_without_raw_body():
    response = _response(
        504,
        {
            "errorCode": "MODEL_004",
            "developerMessage": "upstream timed out after retry",
            "traceId": "trace-body",
        },
    )

    with pytest.raises(BackendClientError) as captured:
        BackendClient()._parse_response(response)

    assert captured.value.error_code == "MODEL_004"
    assert captured.value.trace_id == "trace-body"
    assert "upstream timed out after retry" in str(captured.value)


def test_backend_client_keeps_legacy_error_compatibility():
    response = _response(400, {"code": "PARAM_ERROR", "message": "legacy error"})

    with pytest.raises(BackendClientError) as captured:
        BackendClient()._parse_response(response)

    assert captured.value.error_code == "PARAM_ERROR"
    assert captured.value.trace_id == "trace-header"


def test_backend_client_rejects_success_code_on_failed_http_response():
    response = _response(500, {"code": "SUCCESS", "message": "ok"}, trace_id="trace-invalid-success")

    with pytest.raises(BackendClientError) as captured:
        BackendClient()._parse_response(response)

    assert captured.value.error_code == "SYSTEM_001"
    assert captured.value.trace_id == "trace-invalid-success"
    assert captured.value.status_code == 500
    assert "backend HTTP 500" in str(captured.value)
