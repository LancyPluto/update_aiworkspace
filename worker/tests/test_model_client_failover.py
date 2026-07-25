import pytest
import requests

from client.model_client import (
    ModelClient,
    ModelClientError,
    ModelOutputEmptyError,
    ModelRequestNotSentError,
    ModelTimeoutError,
)


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


def test_text_model_applies_contract_params_and_response_mapping(monkeypatch) -> None:
    captured: dict = {}

    class MappedResponse:
        status_code = 200
        text = ""
        headers = {}

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {
                "result": {"answer": "mapped response"},
                "meter": {"input_tokens": 7, "output_tokens": 3},
            }

    def fake_post(*_args, **kwargs):
        captured.update(kwargs["json"])
        return MappedResponse()

    monkeypatch.setattr(requests, "post", fake_post)
    result = ModelClient().generate_with_usage(
        "ping",
        provider="openai_compatible",
        model_name="mapped-model",
        base_url="https://gateway.example/v1",
        api_key="test-key",
        model_params={
            "temperature": 0.25,
            "topP": 0.8,
            "seed": 42,
            "prompt": "must not be copied into the provider payload",
        },
        response_mapping={
            "version": "1",
            "contentPath": "result.answer",
            "usagePath": "meter",
        },
    )

    assert result.content == "mapped response"
    assert result.prompt_tokens == 7
    assert result.completion_tokens == 3
    assert captured["temperature"] == 0.25
    assert captured["top_p"] == 0.8
    assert captured["seed"] == 42
    assert "prompt" not in captured


def test_text_model_declared_content_mapping_does_not_fallback(monkeypatch) -> None:
    class StandardResponse:
        status_code = 200
        text = ""
        headers = {}

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"choices": [{"message": {"content": "legacy content"}}]}

    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: StandardResponse())

    with pytest.raises(ModelOutputEmptyError, match="empty content"):
        ModelClient().generate_with_usage(
            "ping",
            provider="openai_compatible",
            model_name="mapped-model",
            base_url="https://gateway.example/v1",
            api_key="test-key",
            response_mapping={"version": "1", "contentPath": "result.answer"},
        )


def test_text_model_stream_uses_declared_delta_path(monkeypatch) -> None:
    class StreamResponse:
        status_code = 200
        text = ""
        headers = {}

        def raise_for_status(self) -> None:
            return None

        def iter_lines(self, decode_unicode: bool = False):
            assert decode_unicode is False
            return iter(
                [
                    b'data: {"event":{"delta":"mapped "}}',
                    b'data: {"event":{"delta":"stream"}}',
                    b"data: [DONE]",
                ]
            )

    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: StreamResponse())

    chunks = list(
        ModelClient().generate_stream_with_usage(
            "ping",
            provider="openai_compatible",
            model_name="mapped-model",
            base_url="https://gateway.example/v1",
            api_key="test-key",
            response_mapping={"version": "1", "streamContentPath": "event.delta"},
        )
    )

    assert chunks == ["mapped ", "stream"]


def test_text_model_stream_decodes_utf8_when_provider_omits_charset(monkeypatch) -> None:
    class StreamResponse:
        status_code = 200
        text = ""
        headers = {"Content-Type": "text/event-stream"}
        encoding = "ISO-8859-1"

        def raise_for_status(self) -> None:
            return None

        def iter_lines(self, decode_unicode: bool = False):
            assert decode_unicode is False
            return iter(
                [
                    'data: {"choices":[{"delta":{"content":"为什么"}}]}'.encode("utf-8"),
                    'data: {"choices":[{"delta":{"content":"报考大学"}}]}'.encode("utf-8"),
                    b"data: [DONE]",
                ]
            )

    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: StreamResponse())

    chunks = list(
        ModelClient().generate_stream_with_usage(
            "ping",
            provider="openai_compatible",
            model_name="doubao-seed-2-0-lite-260215",
            base_url="https://ark.cn-beijing.volces.com/api/v3",
            api_key="test-key",
        )
    )

    assert chunks == ["为什么", "报考大学"]
