import socket
import ssl
from unittest.mock import MagicMock, patch

import pytest
import requests

from client.agnes_video_client import AgnesVideoClient, AgnesVideoError
from client.dashscope_video_client import DashScopeVideoClient, DashScopeVideoError
from client.kling_video_client import KlingVideoClient, KlingVideoError
from client.model_client import ModelClient, ModelClientError
from client.openai_images_client import OpenAIImagesClient, OpenAIImagesError
from client.provider_error import (
    rejected_response_metadata,
    request_was_not_sent,
    structured_failure_payload,
    transport_failure_metadata,
)
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError
from client.siliconflow_video_client import SiliconFlowVideoClient, SiliconFlowVideoError


def _connection_refused() -> requests.ConnectionError:
    return requests.ConnectionError(ConnectionRefusedError("connection refused"))


def _assert_safe_account_failover(error: BaseException) -> None:
    assert structured_failure_payload(error) == {
        "deliveryState": "NOT_SENT",
        "retryScope": "ACCOUNT",
        "failureStage": "BEFORE_PROVIDER",
    }


class ErrorResponse:
    def __init__(self, status_code: int, payload: dict, headers: dict | None = None) -> None:
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}
        self.text = str(payload)

    def raise_for_status(self) -> None:
        raise requests.HTTPError(response=self)

    def json(self) -> dict:
        return self._payload


def test_plain_ssl_error_does_not_prove_request_was_not_sent() -> None:
    assert request_was_not_sent(requests.exceptions.SSLError("TLS delivery state unknown")) is False


def test_certificate_verification_error_proves_tls_handshake_failed() -> None:
    error = requests.exceptions.SSLError(
        ssl.SSLCertVerificationError(1, "certificate verify failed")
    )

    assert request_was_not_sent(error) is True


@pytest.mark.parametrize(
    "error",
    [
        requests.ConnectTimeout("connect timed out"),
        requests.ConnectionError(socket.gaierror(11001, "name resolution failed")),
    ],
)
def test_typed_pre_delivery_transport_failures_are_safe_for_account_failover(error) -> None:
    assert transport_failure_metadata(error) == {
        "delivery_state": "NOT_SENT",
        "retry_scope": "ACCOUNT",
        "failure_stage": "BEFORE_PROVIDER",
    }


@pytest.mark.parametrize(
    "error",
    [
        requests.ReadTimeout("response may have been received"),
        requests.ConnectionError(ConnectionResetError("connection reset")),
    ],
)
def test_post_delivery_transport_failures_keep_delivery_unknown(error) -> None:
    assert transport_failure_metadata(error) == {
        "delivery_state": "UNKNOWN",
        "retry_scope": "NONE",
        "failure_stage": "PROVIDER_SUBMITTED",
    }


@pytest.mark.parametrize(
    ("client", "invoke", "error_type"),
    [
        (
            KlingVideoClient(api_key="test.jwt.token"),
            lambda client: client._request("POST", "/v1/videos/text2video", {}),
            KlingVideoError,
        ),
        (
            SeedanceVideoClient(api_key="test-key"),
            lambda client: client._request("POST", "/contents/generations/tasks", {}),
            SeedanceVideoError,
        ),
        (
            DashScopeVideoClient(api_key="test-key"),
            lambda client: client._request("POST", "/api/v1/tasks", {}),
            DashScopeVideoError,
        ),
        (
            SiliconFlowVideoClient(api_key="test-key"),
            lambda client: client._post("/v1/video/submit", {}),
            SiliconFlowVideoError,
        ),
    ],
)
def test_provider_clients_preserve_http_auth_rejection_metadata(
    client,
    invoke,
    error_type,
) -> None:
    response = ErrorResponse(401, {"error": {"message": "invalid credentials"}})
    client.session.request = MagicMock(return_value=response)
    client.session.post = MagicMock(return_value=response)

    with pytest.raises(error_type) as raised:
        invoke(client)

    assert structured_failure_payload(raised.value) == {
        "deliveryState": "REJECTED",
        "retryScope": "ACCOUNT",
        "failureStage": "BEFORE_PROVIDER",
        "providerErrorCode": "HTTP_401",
    }


def test_response_rejection_metadata_uses_structured_code_and_keeps_unknown_5xx_unsafe() -> None:
    unavailable = rejected_response_metadata(
        ErrorResponse(503, {"error": {"code": "model_unavailable"}})
    )
    unknown = rejected_response_metadata(
        ErrorResponse(503, {"error": {"message": "upstream failed"}})
    )
    rate_limited = rejected_response_metadata(
        ErrorResponse(429, {}, headers={"Retry-After": "12"})
    )

    assert unavailable["delivery_state"] == "REJECTED"
    assert unavailable["retry_scope"] == "ACCOUNT"
    assert unavailable["provider_error_code"] == "model_unavailable"
    assert unknown["delivery_state"] == "UNKNOWN"
    assert unknown["retry_scope"] == "NONE"
    assert rate_limited["delivery_state"] == "REJECTED"
    assert rate_limited["retry_scope"] == "ACCOUNT"
    assert rate_limited["retry_after_seconds"] == 12


@pytest.mark.parametrize(
    ("client", "invoke", "error_type"),
    [
        (
            SeedanceVideoClient(api_key="test-key"),
            lambda client: client._request("POST", "/contents/generations/tasks", {}),
            SeedanceVideoError,
        ),
        (
            DashScopeVideoClient(api_key="test-key"),
            lambda client: client._request("POST", "/api/v1/tasks", {}),
            DashScopeVideoError,
        ),
        (
            SiliconFlowVideoClient(api_key="test-key"),
            lambda client: client._post("/v1/video/submit", {}),
            SiliconFlowVideoError,
        ),
    ],
)
def test_provider_clients_preserve_connection_refused_metadata(client, invoke, error_type) -> None:
    client.session.request = MagicMock(side_effect=_connection_refused())
    client.session.post = MagicMock(side_effect=_connection_refused())

    with pytest.raises(error_type) as raised:
        invoke(client)

    _assert_safe_account_failover(raised.value)


def test_kling_preserves_connection_refused_metadata_after_transport_retries() -> None:
    client = KlingVideoClient(api_key="test.jwt.token")
    client.session.request = MagicMock(side_effect=[_connection_refused()] * 3)

    with patch("client.kling_video_client.time.sleep"):
        with pytest.raises(KlingVideoError) as raised:
            client._request("POST", "/v1/videos/text2video", {"prompt": "test"})

    _assert_safe_account_failover(raised.value)


def test_existing_clients_keep_plain_ssl_delivery_unknown(monkeypatch) -> None:
    model_client = ModelClient()
    monkeypatch.setattr(
        requests,
        "post",
        MagicMock(side_effect=requests.exceptions.SSLError("TLS delivery state unknown")),
    )

    with pytest.raises(ModelClientError) as model_raised:
        model_client._post_with_timeout_retry(
            "https://gateway.example/v1/chat/completions",
            headers={},
            payload={"model": "test"},
            timeout=(1, 2),
        )

    agnes_client = AgnesVideoClient(base_url="https://agnes.example", api_key="test-key")
    agnes_client.session.request = MagicMock(
        side_effect=requests.exceptions.SSLError("TLS delivery state unknown")
    )
    with pytest.raises(AgnesVideoError) as agnes_raised:
        agnes_client._request("POST", "/v1/videos", json_payload={"model": "test"})

    openai_images_client = OpenAIImagesClient(
        base_url="https://images.example/v1",
        api_key="test-key",
    )
    monkeypatch.setattr(openai_images_client, "_connection_diagnostics", lambda _url: "test")
    openai_images_client.session.post = MagicMock(
        side_effect=requests.exceptions.SSLError("TLS delivery state unknown")
    )
    with pytest.raises(OpenAIImagesError) as openai_raised:
        openai_images_client._post_once(
            "https://images.example/v1/images/generations",
            {"model": "gpt-image-2"},
        )

    assert structured_failure_payload(model_raised.value)["deliveryState"] == "UNKNOWN"
    assert structured_failure_payload(agnes_raised.value)["deliveryState"] == "UNKNOWN"
    assert structured_failure_payload(openai_raised.value)["deliveryState"] == "UNKNOWN"
