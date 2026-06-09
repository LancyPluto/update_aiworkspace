import httpx
import requests
from openai import APIStatusError
from requests import Request

from client.openai_images_client import OpenAIImagesClient


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


def test_openai_images_allows_explicit_trust_env_override():
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
        extra_auth_json='{"trustEnv": true, "readTimeoutSeconds": 900}',
    )

    assert client.session.trust_env is True
    assert client.timeout == (10, 900)


def test_openai_images_uses_http_proxy_env_when_configured(monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:7890")
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="test-key",
    )

    assert client.session.trust_env is False
    assert client.session.proxies["http"] == "http://127.0.0.1:7890"
    assert client.session.proxies["https"] == "http://127.0.0.1:7890"


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

