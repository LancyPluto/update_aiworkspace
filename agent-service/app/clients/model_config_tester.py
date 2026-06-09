import time

from app.clients.model_client import ModelClient, ModelClientError
from app.config import Settings
from app.core.schemas import AgentModelConfig, ChatMessage


class ModelConfigTester:
    async def test(self, config: AgentModelConfig) -> dict:
        started = time.perf_counter()
        if _requires_api_key(config) and not _has_api_key(config.apiKey):
            return _result(False, config, started, "Credential is not configured for this model", "")
        try:
            answer = await ModelClient(_settings_from_config(config)).chat(
                [
                    ChatMessage(role="system", content="Reply with a short readiness confirmation."),
                    ChatMessage(role="user", content="ping"),
                ]
            )
        except (ModelClientError, ValueError) as exception:
            return _result(False, config, started, str(exception), "")
        return _result(True, config, started, "ok", answer[:240])


def _settings_from_config(config: AgentModelConfig) -> Settings:
    return Settings(
        model_provider=config.provider,
        model_name=config.modelName,
        model_api_base_url=config.baseUrl or "",
        model_api_key=config.apiKey,
        minimax_group_id=config.minimaxGroupId or "",
        model_timeout_seconds=config.timeoutSeconds,
    )


def _requires_api_key(config: AgentModelConfig) -> bool:
    return config.provider.strip().lower() != "mock"


def _has_api_key(value: str | None) -> bool:
    if value is None:
        return False
    stripped = value.strip()
    return bool(stripped) and not stripped.startswith("replace-with-")


def _result(success: bool, config: AgentModelConfig, started: float, message: str, sample: str) -> dict:
    return {
        "success": success,
        "provider": config.provider,
        "modelName": config.modelName,
        "latencyMs": max(0, round((time.perf_counter() - started) * 1000)),
        "message": message,
        "sample": sample,
    }
