import pytest

from app.clients.model_config_tester import ModelConfigTester, _settings_from_config
from app.core.schemas import AgentModelConfig


def test_settings_from_disabled_real_model_preserves_provider_for_connectivity_test():
    config = AgentModelConfig(
        provider="openai_compatible",
        modelName="gpt-5.5",
        baseUrl="https://api.openai.com/v1",
        apiKey="",
        timeoutSeconds=30,
        enabled=False,
    )

    settings = _settings_from_config(config)

    assert settings.model_provider == "openai_compatible"
    assert settings.model_name == "gpt-5.5"


@pytest.mark.asyncio
async def test_model_config_tester_fails_real_model_without_api_key():
    config = AgentModelConfig(
        provider="openai_compatible",
        modelName="gpt-5.5",
        baseUrl="https://api.openai.com/v1",
        apiKey="",
        timeoutSeconds=30,
        enabled=False,
    )

    result = await ModelConfigTester().test(config)

    assert result["success"] is False
    assert result["latencyMs"] >= 0
    assert "Credential is not configured" in result["message"]
