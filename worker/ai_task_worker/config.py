from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    backend_base_url: str = Field(
        default="http://127.0.0.1:8080",
        validation_alias="BACKEND_BASE_URL",
    )
    internal_api_token: str = Field(
        default="local-internal-token",
        validation_alias="INTERNAL_API_TOKEN",
    )

    redis_host: str = Field(default="127.0.0.1", validation_alias="REDIS_HOST")
    redis_port: int = Field(default=6379, validation_alias="REDIS_PORT")
    redis_password: Optional[str] = Field(default=None, validation_alias="REDIS_PASSWORD")
    redis_db: int = Field(default=0, validation_alias="REDIS_DB")
    ai_task_queue: str = Field(default="ai:task:queue", validation_alias="AI_TASK_QUEUE")

    model_api_base_url: str = Field(
        default="https://api.deepseek.com",
        validation_alias="MODEL_API_BASE_URL",
    )
    model_api_key: str = Field(default="", validation_alias="MODEL_API_KEY")
    model_default_name: str = Field(default="deepseek-chat", validation_alias="MODEL_DEFAULT_NAME")

    worker_block_timeout_sec: int = Field(default=5, validation_alias="WORKER_BLOCK_TIMEOUT_SEC")
    http_timeout_sec: float = Field(default=120.0, validation_alias="HTTP_TIMEOUT_SEC")

    worker_dry_run: bool = Field(default=False, validation_alias="WORKER_DRY_RUN")


def get_settings() -> Settings:
    return Settings()
