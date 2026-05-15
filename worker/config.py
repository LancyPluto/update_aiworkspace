import os
from dataclasses import dataclass

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover
    load_dotenv = None


if load_dotenv is not None:
    load_dotenv()


@dataclass(slots=True)
class Settings:
    redis_host: str = os.getenv('REDIS_HOST', '127.0.0.1')
    redis_port: int = int(os.getenv('REDIS_PORT', '6379'))
    redis_password: str = os.getenv('REDIS_PASSWORD', '')
    redis_database: int = int(os.getenv('REDIS_DATABASE', '0'))
    ai_task_queue: str = os.getenv('AI_TASK_QUEUE', 'ai:task:queue')
    task_queue_backend: str = os.getenv('TASK_QUEUE_BACKEND', 'redis')
    rabbitmq_host: str = os.getenv('RABBITMQ_HOST', '127.0.0.1')
    rabbitmq_port: int = int(os.getenv('RABBITMQ_PORT', '5672'))
    rabbitmq_username: str = os.getenv('RABBITMQ_USERNAME', 'guest')
    rabbitmq_password: str = os.getenv('RABBITMQ_PASSWORD', 'guest')
    rabbitmq_task_queue: str = os.getenv('RABBITMQ_TASK_QUEUE', 'ai.tool.normal')
    rabbitmq_dead_queue: str = os.getenv('RABBITMQ_DEAD_QUEUE', 'ai.tool.normal.dead')
    rabbitmq_retry_queue_prefix: str = os.getenv('RABBITMQ_RETRY_QUEUE_PREFIX', 'ai.tool.normal.retry')
    rabbitmq_retry_delays_ms: str = os.getenv('RABBITMQ_RETRY_DELAYS_MS', '5000,30000,120000')
    rabbitmq_max_retries: int = int(os.getenv('RABBITMQ_MAX_RETRIES', '3'))
    backend_internal_base_url: str = os.getenv('BACKEND_INTERNAL_BASE_URL', 'http://localhost:8080')
    internal_api_token: str = os.getenv('INTERNAL_API_TOKEN', 'replace-with-internal-token')
    model_provider: str = os.getenv('MODEL_PROVIDER', 'deepseek')
    model_api_base_url: str = os.getenv('MODEL_API_BASE_URL', 'https://api.deepseek.com')
    model_api_key: str = os.getenv('MODEL_API_KEY', 'replace-with-model-key')
    model_name: str = os.getenv('MODEL_NAME', 'deepseek-chat')


settings = Settings()
