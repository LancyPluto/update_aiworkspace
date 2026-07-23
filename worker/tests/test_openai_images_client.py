import httpx
import pytest
import requests
from openai import APIStatusError
from requests import Request
from typing import Any

from client.openai_images_client import OpenAIImagesClient, OpenAIImagesError, OpenAIImagesRequestNotSentError


def test_openai_images_timeout_has_long_generation_floor():
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
        timeout_seconds=120,
    )

    assert client.timeout == (10, 600)


def test_openai_images_does_not_use_environment_proxy_by_default():
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
    )

    assert client.session.trust_env is False


def test_openai_images_ignores_legacy_trust_env_override():
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
        extra_auth_json='{"trustEnv": true, "readTimeoutSeconds": 900}',
    )

    assert client.session.trust_env is False
    assert client.timeout == (10, 900)


def test_openai_images_ignores_http_proxy_env_when_configured(monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:7890")
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
    )

    assert client.session.trust_env is False
    assert client.session.proxies == {}


def test_openai_images_uses_project_gateway_and_ignores_execution_routing(monkeypatch):
    monkeypatch.setenv("PROJECT_MIHOMO_PROXY_URL", "http://mihomo:7890")
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
        extra_auth_json='{"proxyUrl":"http://legacy:7890"}',
        model_config={
                "proxyPolicy": {
                    "enabled": True,
                    "projectProxyUrl": "http://mihomo:7890",
                    "noProxyHosts": ["backend", "api.ofox.ai"],
                    "routingRules": [
                        {"id": "ofox", "patternType": "EXACT", "pattern": "api.ofox.ai", "strategy": "PROXY", "priority": 100, "enabled": True}
                    ],
                }
        },
    )

    assert client.session.trust_env is False
    assert client.session.proxies["http"] == "http://mihomo:7890"
    assert client.session.proxies["https"] == "http://mihomo:7890"
    assert "backend" in client.session.policy.no_proxy_hosts
    assert "api.ofox.ai" not in client.session.policy.no_proxy_hosts


def test_openai_images_multipart_request_uses_form_data_content_type() -> None:
    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    multipart = [
        ("model", (None, "openai/gpt-image-2")),
        ("image", ("ref.png", b"fake", "image/png")),
    ]
    prepared = client.session.prepare_request(
        Request(
            "POST",
            "https://api.ofox.ai/v1/images/edits",
            files=multipart,
            headers=client._multipart_headers(),
        )
    )

    content_type = prepared.headers.get("Content-Type", "")
    assert content_type.startswith("multipart/form-data"), content_type


def test_openai_images_with_reference_uses_requests_multipart_by_default() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"url": "https://example.com/openai-image.png"}]}

    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    posted: dict = {}

    def fake_post(url, json=None, data=None, files=None, timeout=None, headers=None):
        posted["url"] = url
        posted["files"] = dict(files or [])
        return FakeResponse()

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image_size="1536x1024",
        batch_size=1,
        quality="low",
        image="data:image/png;base64,ZmFrZQ==",
    )

    assert urls == ["https://example.com/openai-image.png"], urls
    assert posted["url"] == "https://api.ofox.ai/v1/images/edits", posted
    assert posted["files"]["model"] == (None, "openai/gpt-image-2"), posted
    assert posted["files"]["size"] == (None, "auto"), posted
    assert posted["files"]["image"][1] == b"fake", posted


def test_openai_images_edit_does_not_top_up_by_default_when_gateway_returns_fewer_than_requested() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def __init__(self, index: int) -> None:
            self.index = index

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {
                "data": [{"url": f"https://example.com/openai-image-{self.index}.png"}],
                "usage": {"input_tokens": 10, "output_tokens": 20, "total_tokens": 30},
            }

    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    posted_n: list[str] = []

    def fake_post(url, json=None, data=None, files=None, timeout=None, headers=None):
        files_map = dict(files or [])
        n_field = files_map.get("n")
        posted_n.append(n_field[1] if isinstance(n_field, tuple) else "")
        return FakeResponse(len(posted_n))

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image_size="1536x1024",
        batch_size=2,
        quality="low",
        image="data:image/png;base64,ZmFrZQ==",
    )

    assert urls == ["https://example.com/openai-image-1.png"], urls
    assert posted_n == ["2"], posted_n
    assert client.last_usage == {"promptTokens": 10, "completionTokens": 20, "totalTokens": 30}


def test_openai_images_edit_can_top_up_when_gateway_returns_fewer_than_requested() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def __init__(self, index: int) -> None:
            self.index = index

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {
                "data": [{"url": f"https://example.com/openai-image-{self.index}.png"}],
                "usage": {"input_tokens": 10, "output_tokens": 20, "total_tokens": 30},
            }

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"topUpEditBatch": true}',
    )
    posted_n: list[str] = []

    def fake_post(url, json=None, data=None, files=None, timeout=None, headers=None):
        files_map = dict(files or [])
        n_field = files_map.get("n")
        posted_n.append(n_field[1] if isinstance(n_field, tuple) else "")
        return FakeResponse(len(posted_n))

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image_size="1536x1024",
        batch_size=2,
        quality="low",
        image="data:image/png;base64,ZmFrZQ==",
    )

    assert urls == [
        "https://example.com/openai-image-1.png",
        "https://example.com/openai-image-2.png",
    ], urls
    assert posted_n == ["2", "1"], posted_n
    assert client.last_usage == {"promptTokens": 20, "completionTokens": 40, "totalTokens": 60}


def test_openai_images_with_multiple_references_uses_edit_multipart() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"url": "https://example.com/openai-image.png"}]}

    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    posted: dict = {}

    def fake_post(url, json=None, data=None, files=None, timeout=None, headers=None):
        posted["url"] = url
        posted["files"] = files or []
        return FakeResponse()

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image=[
            "data:image/png;base64,ZmFrZTE=",
            "data:image/png;base64,ZmFrZTI=",
        ],
    )

    image_parts = [part for part in posted["files"] if part[0] == "image"]
    assert urls == ["https://example.com/openai-image.png"], urls
    assert posted["url"] == "https://api.ofox.ai/v1/images/edits", posted
    assert len(image_parts) == 2, posted
    assert image_parts[0][1][1] == b"fake1", posted
    assert image_parts[1][1][1] == b"fake2", posted


def test_openai_images_edit_retries_with_prefixed_model_when_gateway_requires_it() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"b64_json": "ZmFrZQ=="}]}

    client = OpenAIImagesClient(base_url="https://gateway.example.com/v1", api_key="fake-key")
    calls: list[str] = []

    def fake_post(url, json=None, data=None, files=None, timeout=None, headers=None):
        files_map = dict(files or [])
        model_field = files_map.get("model")
        model_name = model_field[1] if isinstance(model_field, tuple) else ""
        calls.append(model_name)
        if model_name == "openai/gpt-image-2":
            response = FakeResponse()
            response.status_code = 400
            response.text = '{"error":{"message":"You must provide a model parameter."}}'

            def raise_for_status():
                raise requests.HTTPError(response=response)

            response.raise_for_status = raise_for_status
            return response
        return FakeResponse()

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image="data:image/png;base64,ZmFrZQ==",
    )

    assert urls[0].startswith("data:image/png;base64,"), urls
    assert calls == ["openai/gpt-image-2", "gpt-image-2"], calls


def test_openai_images_edit_can_use_sdk_when_configured(monkeypatch) -> None:
    calls: list[dict] = []

    class FakeImages:
        def edit(self, **kwargs):
            calls.append(kwargs)

            class FakeResult:
                def model_dump(self) -> dict:
                    return {"data": [{"b64_json": "ZmFrZQ=="}]}

            return FakeResult()

    class FakeOpenAI:
        def __init__(self, **kwargs):
            self.images = FakeImages()

    monkeypatch.setattr("openai.OpenAI", FakeOpenAI)

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"preferSdkEdit": true}',
    )
    urls = client.generate_images(
        prompt="edit this",
        model="openai/gpt-image-2",
        image="data:image/png;base64,ZmFrZQ==",
    )

    assert urls[0].startswith("data:image/png;base64,"), urls
    assert calls[0]["model"] == "openai/gpt-image-2", calls


def test_openai_images_can_place_response_format_inside_extra_body(monkeypatch):
    client = OpenAIImagesClient(
        base_url="https://apihub.agnes-ai.com/v1",
        api_key="test-key",
        extra_auth_json='{"responseFormatLocation": "extra_body"}',
    )
    captured = {}

    def fake_post(path, payload):
        captured["path"] = path
        captured["payload"] = payload
        return {"data": [{"url": "https://cdn.example/out.png"}]}

    monkeypatch.setattr(client, "_post", fake_post)

    urls = client.generate_images(
        prompt="a tidy product photo",
        model="agnes-image-2.1-flash",
        image_size="1024x1024",
        response_format="url",
    )

    assert urls == ["https://cdn.example/out.png"]
    assert captured["path"] == "/images/generations"
    assert "response_format" not in captured["payload"]
    assert captured["payload"]["extra_body"] == {"response_format": "url"}


def test_openai_images_can_send_source_images_as_json_array(monkeypatch):
    client = OpenAIImagesClient(
        base_url="https://apihub.agnes-ai.com/v1",
        api_key="test-key",
        extra_auth_json='{"imageInputMode": "json_array", "responseFormatLocation": "extra_body"}',
    )
    captured = {}

    def fake_post(path, payload):
        captured["path"] = path
        captured["payload"] = payload
        return {"data": [{"url": "https://cdn.example/out.png"}]}

    def fail_multipart(*_args, **_kwargs):
        raise AssertionError("json_array image mode must not use multipart edit requests")

    monkeypatch.setattr(client, "_post", fake_post)
    monkeypatch.setattr(client, "_post_multipart", fail_multipart)

    urls = client.generate_images(
        prompt="keep the pose and change the outfit",
        model="agnes-image-2.1-flash",
        image_size="1024x1024",
        response_format="url",
        image="https://storage.example/input.png",
    )

    assert urls == ["https://cdn.example/out.png"]
    assert captured["path"] == "/images/generations"
    assert captured["payload"]["image"] == ["https://storage.example/input.png"]
    assert captured["payload"]["extra_body"] == {"response_format": "url"}


def test_volcengine_images_reference_defaults_to_generation_json(monkeypatch):
    client = OpenAIImagesClient(
        base_url="https://ark.cn-beijing.volces.com/api/v3",
        api_key="test-key",
    )
    captured = {}

    def fake_post(path, payload):
        captured["path"] = path
        captured["payload"] = payload
        return {"data": [{"url": "https://cdn.example/seedream.png"}]}

    def fail_multipart(*_args, **_kwargs):
        raise AssertionError("volcengine seedream references must use /images/generations json input")

    monkeypatch.setattr(client, "_post", fake_post)
    monkeypatch.setattr(client, "_post_multipart", fail_multipart)

    urls = client.generate_images(
        prompt="换成雪原背景",
        model="doubao-seedream-5-0-260128",
        image_size="2048x2048",
        image=["data:image/png;base64,ZmFrZQ=="],
    )

    assert urls == ["https://cdn.example/seedream.png"]
    assert captured["path"] == "/images/generations"
    assert captured["payload"]["image"] == ["data:image/png;base64,ZmFrZQ=="]


def test_openai_images_504_is_not_retried(monkeypatch) -> None:
    attempts = {"count": 0}

    class Fake504Response:
        status_code = 504
        text = "gateway timeout"

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        return Fake504Response()

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"noRetryHttpStatuses":[504],"connectionRetries":2}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(Exception):
        client._post_once("https://api.ofox.ai/v1/images/generations", {"model": "openai/gpt-image-2"})

    assert attempts["count"] == 1


def test_openai_images_multipart_incomplete_response_is_not_retried_by_default(monkeypatch) -> None:
    attempts = {"count": 0}

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        raise requests.exceptions.ChunkedEncodingError(
            "Connection broken: IncompleteRead(8185 bytes read, 2055 more expected)"
        )

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":2}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(Exception, match="not retrying this non-idempotent image request"):
        client._post_multipart_with_connect_retries(
            "https://api.ofox.ai/v1/images/edits",
            {"model": "openai/gpt-image-2", "prompt": "edit", "n": "1", "size": "auto", "quality": "low"},
            [("ref.png", b"fake", "image/png")],
        )

    assert attempts["count"] == 1


def test_openai_images_json_incomplete_response_is_not_retried_by_default(monkeypatch) -> None:
    attempts = {"count": 0}

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        raise requests.exceptions.ChunkedEncodingError("Response ended prematurely")

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":2}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(Exception, match="not retrying this non-idempotent image request"):
        client._post("/images/generations", {"model": "openai/gpt-image-2", "prompt": "test"})

    assert attempts["count"] == 1


def test_openai_images_incomplete_response_retry_cannot_replay_non_idempotent_create(monkeypatch) -> None:
    attempts = {"count": 0}

    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"url": "https://example.com/recovered.png"}]}

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise requests.exceptions.ChunkedEncodingError("Response ended prematurely")
        return FakeResponse()

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":1,"retryIncompleteResponses":true}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(Exception, match="not retrying this non-idempotent image request"):
        client._post("/images/generations", {"model": "openai/gpt-image-2", "prompt": "test"})

    assert attempts["count"] == 1


def test_openai_images_connection_error_does_not_replay_non_idempotent_create(monkeypatch) -> None:
    attempts = {"count": 0}

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        raise requests.ConnectionError("connection reset after request may have been sent")

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":2,"retryBackoffSeconds":0}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(Exception, match="request may have been sent"):
        client._post("/images/generations", {"model": "openai/gpt-image-2", "prompt": "test"})

    assert attempts["count"] == 1


def test_openai_images_5xx_does_not_replay_non_idempotent_create(monkeypatch) -> None:
    attempts = {"count": 0}

    class Fake502Response:
        status_code = 502
        text = "upstream response lost"

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        return Fake502Response()

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":2,"retryBackoffSeconds":0}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    with pytest.raises(OpenAIImagesError, match="status=502") as raised:
        client._post("/images/generations", {"model": "openai/gpt-image-2", "prompt": "test"})

    assert attempts["count"] == 1
    assert raised.value.delivery_state == "UNKNOWN"
    assert raised.value.retry_scope == "NONE"


def test_openai_images_structured_model_unavailable_5xx_allows_account_failover(monkeypatch) -> None:
    class Fake503Response:
        status_code = 503
        text = '{"error":{"code":"model_unavailable"}}'
        headers = {}

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

        def json(self) -> dict:
            return {"error": {"code": "model_unavailable"}}

    client = OpenAIImagesClient(
        base_url="https://gateway.example/v1",
        api_key="test-key",
        extra_auth_json='{"connectionRetries":0}',
    )
    monkeypatch.setattr(client.session, "post", lambda *_args, **_kwargs: Fake503Response())

    with pytest.raises(OpenAIImagesError) as raised:
        client._post("/images/generations", {"model": "gpt-image-2", "prompt": "test"})

    assert raised.value.delivery_state == "REJECTED"
    assert raised.value.retry_scope == "ACCOUNT"
    assert raised.value.provider_error_code == "model_unavailable"


def test_openai_images_auth_rejection_is_safe_for_account_failover(monkeypatch) -> None:
    class Fake401Response:
        status_code = 401
        text = "invalid api key"
        headers = {}

        def raise_for_status(self) -> None:
            raise requests.HTTPError(response=self)  # type: ignore[arg-type]

    client = OpenAIImagesClient(base_url="https://gateway.example/v1", api_key="bad-key")
    monkeypatch.setattr(client.session, "post", lambda *_args, **_kwargs: Fake401Response())

    with pytest.raises(OpenAIImagesError) as raised:
        client._post("/images/generations", {"model": "gpt-image-2", "prompt": "test"})

    assert raised.value.delivery_state == "REJECTED"
    assert raised.value.retry_scope == "ACCOUNT"
    assert raised.value.provider_error_code is None
    assert raised.value.http_status == 401


def test_openai_images_exhausted_connect_timeout_is_marked_not_sent(monkeypatch) -> None:
    client = OpenAIImagesClient(
        base_url="https://gateway.example/v1",
        api_key="test-key",
        extra_auth_json='{"connectionRetries":0}',
    )
    monkeypatch.setattr(
        client.session,
        "post",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(requests.ConnectTimeout("not connected")),
    )

    with pytest.raises(OpenAIImagesRequestNotSentError) as raised:
        client._post("/images/generations", {"model": "gpt-image-2", "prompt": "test"})

    assert raised.value.delivery_state == "NOT_SENT"
    assert raised.value.retry_scope == "ACCOUNT"


def test_openai_images_connection_refused_is_marked_not_sent(monkeypatch) -> None:
    client = OpenAIImagesClient(
        base_url="https://gateway.example/v1",
        api_key="test-key",
        extra_auth_json='{"connectionRetries":0}',
    )
    monkeypatch.setattr(
        client.session,
        "post",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            requests.ConnectionError(ConnectionRefusedError("connection refused"))
        ),
    )

    with pytest.raises(OpenAIImagesRequestNotSentError) as raised:
        client._post("/images/generations", {"model": "gpt-image-2", "prompt": "test"})

    assert raised.value.delivery_state == "NOT_SENT"
    assert raised.value.retry_scope == "ACCOUNT"


def test_openai_images_connect_timeout_retries_before_request_is_sent(monkeypatch) -> None:
    attempts = {"count": 0}

    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"url": "https://example.com/recovered.png"}]}

    def fake_post(*_args, **_kwargs):
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise requests.ConnectTimeout("connection was not established")
        return FakeResponse()

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"connectionRetries":1,"retryBackoffSeconds":0}',
    )
    monkeypatch.setattr(client.session, "post", fake_post)

    response = client._post("/images/generations", {"model": "openai/gpt-image-2", "prompt": "test"})

    assert response["data"][0]["url"] == "https://example.com/recovered.png"
    assert attempts["count"] == 2


def test_openai_images_sdk_connection_error_does_not_fallback_to_second_create(monkeypatch) -> None:
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"preferSdkEdit":true}',
    )

    def fail_sdk(*_args, **_kwargs):
        raise RuntimeError("SDK connection failed after request may have been sent")

    def fail_multipart(*_args, **_kwargs):
        raise AssertionError("must not replay the edit through another transport")

    monkeypatch.setattr(client, "_edit_via_openai_sdk", fail_sdk)
    monkeypatch.setattr(client, "_post_multipart", fail_multipart)

    with pytest.raises(RuntimeError, match="request may have been sent"):
        client._edit_reference_image(
            "/images/edits",
            {"model": "openai/gpt-image-2", "prompt": "edit"},
            [("ref.png", b"fake", "image/png")],
            source_model="openai/gpt-image-2",
        )


def test_openai_images_force_quality_overrides_params() -> None:
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"forceQuality":"low"}',
    )
    payload = client._build_generation_payload(
        prompt="test",
        model="openai/gpt-image-2",
        image_size="1024x1024",
        batch_size=1,
        quality="medium",
        style=None,
        output_format=None,
        response_format=None,
    )
    assert payload["quality"] == "low"


def test_openai_images_gpt_image_2_ignores_output_format() -> None:
    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    payload = client._build_generation_payload(
        prompt="test",
        model="openai/gpt-image-2",
        image_size="1024x1024",
        batch_size=1,
        quality=None,
        style=None,
        output_format="jpeg",
        response_format=None,
    )
    assert "output_format" not in payload


def test_volcengine_images_watermark_param_overrides_extra_auth() -> None:
    client = OpenAIImagesClient(
        base_url="https://ark.cn-beijing.volces.com/api/v3",
        api_key="fake-key",
        extra_auth_json='{"watermark":true}',
    )

    payload = client._build_generation_payload(
        prompt="test",
        model="doubao-seedream-4-5-251128",
        image_size="1024x1024",
        batch_size=1,
        quality=None,
        style=None,
        output_format="png",
        response_format=None,
        watermark="false",
    )

    assert payload["watermark"] is False


def test_volcengine_images_payload_includes_sequential_generation_options() -> None:
    client = OpenAIImagesClient(
        base_url="https://ark.cn-beijing.volces.com/api/v3",
        api_key="fake-key",
    )

    payload = client._build_generation_payload(
        prompt="test",
        model="doubao-seedream-5-0-260128",
        image_size="2848x1600",
        batch_size=1,
        quality=None,
        style=None,
        output_format="jpeg",
        response_format="url",
        watermark=False,
        sequential_image_generation="auto",
        max_images="8",
        optimize_prompt_mode="standard",
    )

    assert payload["size"] == "2848x1600"
    assert payload["sequential_image_generation"] == "auto"
    assert payload["sequential_image_generation_options"] == {"max_images": 8}
    assert payload["optimize_prompt_options"] == {"mode": "standard"}


def test_openai_images_standard_quality_maps_to_low() -> None:
    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    assert client._resolve_quality("standard", required=True) == "low"


def test_openai_images_max_reference_images_truncates(monkeypatch) -> None:
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"maxReferenceImages":1}',
    )
    captured: dict[str, Any] = {}

    def fake_edit(endpoint, form_fields, image_files, *, source_model):
        captured["image_count"] = len(image_files)
        return {"data": [{"url": "https://example.com/1.png"}]}

    monkeypatch.setattr(client, "_edit_reference_image", fake_edit)

    client.generate_images(
        prompt="blend",
        model="openai/gpt-image-2",
        image=["data:image/png;base64,ZmFrZQ==", "data:image/png;base64,ZmFrZQ=="],
    )

    assert captured["image_count"] == 1


def test_openai_images_rejects_oversized_reference_images() -> None:
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"maxInputImageBytes":8}',
    )
    huge = "data:image/png;base64," + ("A" * 32)
    with pytest.raises(Exception, match="maxInputImageBytes"):
        client._build_edit_multipart(
            prompt="edit",
            model="openai/gpt-image-2",
            image_size="auto",
            batch_size=1,
            quality="low",
            output_format=None,
            reference_images=[huge],
        )
