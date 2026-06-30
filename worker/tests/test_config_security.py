import pytest

from config import Settings, validate_startup_settings


def test_worker_defaults_to_rabbitmq_queue_backend(monkeypatch):
    monkeypatch.delenv("TASK_QUEUE_BACKEND", raising=False)

    settings = Settings()

    assert settings.task_queue_backend == "rabbitmq"


def test_local_worker_settings_allow_development_defaults(monkeypatch):
    monkeypatch.delenv("APP_PRODUCTION_MODE", raising=False)
    settings = Settings(
        app_env="local",
        task_queue_backend="redis",
        internal_api_token="local-internal-token",
        model_provider="mock",
        model_api_key="replace-with-model-key",
    )

    validate_startup_settings(settings)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("task_queue_backend", "redis", "TASK_QUEUE_BACKEND=rabbitmq"),
        ("internal_api_token", "local-internal-token", "INTERNAL_API_TOKEN"),
        ("internal_api_token", "replace-with-internal-token", "INTERNAL_API_TOKEN"),
        ("model_provider", "mock", "MODEL_PROVIDER=mock"),
        ("model_api_key", "replace-with-model-key", "MODEL_API_KEY"),
        ("model_api_key", "", "MODEL_API_KEY"),
    ],
)
def test_production_rejects_unsafe_worker_settings(monkeypatch, field, value, message):
    monkeypatch.delenv("APP_PRODUCTION_MODE", raising=False)
    settings = Settings(
        app_env="production",
        task_queue_backend="rabbitmq",
        internal_api_token="strong-internal-token",
        model_provider="deepseek",
        model_api_key="real-model-key",
    )
    setattr(settings, field, value)

    with pytest.raises(RuntimeError, match=message):
        validate_startup_settings(settings)


def test_app_production_mode_true_marks_worker_production(monkeypatch):
    monkeypatch.setenv("APP_PRODUCTION_MODE", "true")
    settings = Settings(
        app_env="local",
        task_queue_backend="redis",
        internal_api_token="strong-internal-token",
        model_provider="deepseek",
        model_api_key="real-model-key",
    )

    with pytest.raises(RuntimeError, match="TASK_QUEUE_BACKEND"):
        validate_startup_settings(settings)
