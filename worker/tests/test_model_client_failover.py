import pytest
import requests

from client.model_client import ModelClient, ModelClientError, ModelRequestNotSentError, ModelTimeoutError


def test_text_model_connect_timeout_is_safe_for_account_failover(monkeypatch) -> None:
    monkeypatch.setattr(
        requests,
        "post",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(requests.ConnectTimeout("not connected")),
    )

    with pytest.raises(ModelRequestNotSentError) as raised:
        ModelClient()._post_with_timeout_retry(
            "https://gateway.example/v1/chat/completions",
            headers={},
            payload={"model": "gpt-4o-mini"},
            timeout=(1, 2),
        )

    assert raised.value.delivery_state == "NOT_SENT"
    assert raised.value.retry_scope == "ACCOUNT"


def test_text_model_read_timeout_keeps_delivery_unknown(monkeypatch) -> None:
    monkeypatch.setattr(
        requests,
        "post",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(requests.ReadTimeout("response lost")),
    )

    with pytest.raises(ModelTimeoutError) as raised:
        ModelClient()._post_with_timeout_retry(
            "https://gateway.example/v1/chat/completions",
            headers={},
            payload={"model": "gpt-4o-mini"},
            timeout=(1, 2),
        )

    assert raised.value.delivery_state == "UNKNOWN"
    assert raised.value.retry_scope == "NONE"


def test_text_model_auth_rejection_is_safe_for_account_failover(monkeypatch) -> None:
    class UnauthorizedResponse:
        status_code = 401
        text = "invalid api key"
        headers = {}

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: UnauthorizedResponse())

    with pytest.raises(ModelClientError) as raised:
        ModelClient().generate_with_usage(
            "ping",
            provider="openai_compatible",
            model_name="gpt-4o-mini",
            base_url="https://gateway.example/v1",
            api_key="test-key",
        )

    assert raised.value.delivery_state == "REJECTED"
    assert raised.value.retry_scope == "ACCOUNT"
    assert raised.value.http_status == 401


def test_text_model_structured_unavailable_code_is_safe_even_on_5xx(monkeypatch) -> None:
    class UnavailableResponse:
        status_code = 503
        text = '{"error":{"code":"no_available_channel"}}'
        headers = {}

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

        def json(self) -> dict:
            return {"error": {"code": "no_available_channel"}}

    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: UnavailableResponse())

    with pytest.raises(ModelClientError) as raised:
        ModelClient().generate_with_usage(
            "ping",
            provider="openai_compatible",
            model_name="gpt-4o-mini",
            base_url="https://gateway.example/v1",
            api_key="test-key",
        )

    assert raised.value.delivery_state == "REJECTED"
    assert raised.value.retry_scope == "ACCOUNT"
    assert raised.value.provider_error_code == "no_available_channel"
