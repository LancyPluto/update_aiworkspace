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
