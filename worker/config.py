import os
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover
    load_dotenv = None


if load_dotenv is not None:
    _project_root = Path(__file__).resolve().parent.parent
    load_dotenv(_project_root / ".env")
    load_dotenv()


@dataclass(slots=True)
class Settings:
    redis_host: str = os.getenv('REDIS_HOST', '127.0.0.1')
    redis_port: int = int(os.getenv('REDIS_PORT', '6379'))
    redis_password: str = os.getenv('REDIS_PASSWORD', '')
    redis_database: int = int(os.getenv('REDIS_DATABASE', '0'))
    redis_retry_interval_seconds: float = float(os.getenv('REDIS_RETRY_INTERVAL_SECONDS', '5'))
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
    worker_concurrency: int = int(os.getenv('WORKER_CONCURRENCY', '5'))
    rabbitmq_prefetch_count: int = int(os.getenv('RABBITMQ_PREFETCH_COUNT', os.getenv('WORKER_CONCURRENCY', '5')))
    rabbitmq_heartbeat_seconds: int = int(os.getenv('RABBITMQ_HEARTBEAT_SECONDS', '1800'))
    rabbitmq_blocked_connection_timeout_seconds: int = int(os.getenv('RABBITMQ_BLOCKED_CONNECTION_TIMEOUT_SECONDS', '1800'))
    backend_internal_base_url: str = os.getenv('BACKEND_INTERNAL_BASE_URL', 'http://localhost:8080')
    internal_api_token: str = os.getenv('INTERNAL_API_TOKEN', 'replace-with-internal-token')
    model_provider: str = os.getenv('MODEL_PROVIDER', 'deepseek')
    model_api_base_url: str = os.getenv('MODEL_API_BASE_URL', 'https://api.deepseek.com')
    model_api_key: str = os.getenv('MODEL_API_KEY', 'replace-with-model-key')
    model_name: str = os.getenv('MODEL_NAME', 'deepseek-chat')
    siliconflow_base_url: str = os.getenv('SILICONFLOW_BASE_URL', 'https://api.siliconflow.cn')
    siliconflow_api_key: str = os.getenv('SILICONFLOW_API_KEY', '')
    siliconflow_voice_model: str = os.getenv('SILICONFLOW_VOICE_MODEL', 'FunAudioLLM/CosyVoice2-0.5B')
    siliconflow_asr_model: str = os.getenv('SILICONFLOW_ASR_MODEL', 'TeleAI/TeleSpeechASR')
    siliconflow_image_model: str = os.getenv('SILICONFLOW_IMAGE_MODEL', 'Tongyi-MAI/Z-Image-Turbo')
    suno_base_url: str = os.getenv('SUNO_BASE_URL', 'https://api.sunoapi.org')
    suno_api_key: str = os.getenv('SUNO_API_KEY', '')
    suno_music_model: str = os.getenv('SUNO_MUSIC_MODEL', 'V5')
    suno_callback_url: str = os.getenv('SUNO_CALLBACK_URL', '')
    suno_file_upload_base_url: str = os.getenv('SUNO_FILE_UPLOAD_BASE_URL', 'https://sunoapiorg.redpandaai.co')
    suno_poll_interval_seconds: float = float(os.getenv('SUNO_POLL_INTERVAL_SECONDS', '8'))
    suno_timeout_seconds: int = int(os.getenv('SUNO_TIMEOUT_SECONDS', '900'))
    digital_human_video_provider: str = os.getenv('DIGITAL_HUMAN_VIDEO_PROVIDER', 'seedance')
    infinitetalk_base_url: str = os.getenv('INFINITETALK_BASE_URL', 'http://host.docker.internal:7860')
    infinitetalk_api_key: str = os.getenv('INFINITETALK_API_KEY', '')
    infinitetalk_create_path: str = os.getenv('INFINITETALK_CREATE_PATH', '/api/v1/generate')
    infinitetalk_result_path: str = os.getenv('INFINITETALK_RESULT_PATH', '/api/v1/tasks/{task_id}')
    infinitetalk_poll_interval_seconds: float = float(os.getenv('INFINITETALK_POLL_INTERVAL_SECONDS', '5'))
    infinitetalk_timeout_seconds: int = int(os.getenv('INFINITETALK_TIMEOUT_SECONDS', '1800'))
    seedance_base_url: str = os.getenv('SEEDANCE_BASE_URL', 'https://ark.cn-beijing.volces.com')
    seedance_api_key: str = os.getenv('SEEDANCE_API_KEY', '')
    seedance_video_model: str = os.getenv('SEEDANCE_VIDEO_MODEL', 'doubao-seedance-1-5-pro-251215')
    seedance_video_create_path: str = os.getenv(
        'SEEDANCE_VIDEO_CREATE_PATH',
        '/api/v3/contents/generations/tasks',
    )
    seedance_video_poll_interval_seconds: float = float(os.getenv('SEEDANCE_VIDEO_POLL_INTERVAL_SECONDS', '5'))
    seedance_video_timeout_seconds: int = int(os.getenv('SEEDANCE_VIDEO_TIMEOUT_SECONDS', '900'))
    kling_base_url: str = os.getenv('KLING_BASE_URL', 'https://api-beijing.klingai.com')
    kling_api_key: str = os.getenv('KLING_API_KEY', '')
    kling_access_key: str = os.getenv('KLING_ACCESS_KEY', '')
    kling_secret_key: str = os.getenv('KLING_SECRET_KEY', '')
    kling_video_model: str = os.getenv('KLING_VIDEO_MODEL', 'kling-v2-6')
    kling_image_model: str = os.getenv('KLING_IMAGE_MODEL', 'kling-v3')
    kling_video_text_path: str = os.getenv('KLING_VIDEO_TEXT_PATH', '/v1/videos/text2video')
    kling_video_image_path: str = os.getenv('KLING_VIDEO_IMAGE_PATH', '/v1/videos/image2video')
    kling_video_text_result_path: str = os.getenv('KLING_VIDEO_TEXT_RESULT_PATH', '/v1/videos/text2video/{task_id}')
    kling_video_image_result_path: str = os.getenv('KLING_VIDEO_IMAGE_RESULT_PATH', '/v1/videos/image2video/{task_id}')
    kling_image_generation_path: str = os.getenv('KLING_IMAGE_GENERATION_PATH', '/v1/images/generations')
    kling_image_result_path: str = os.getenv('KLING_IMAGE_RESULT_PATH', '/v1/images/generations/{task_id}')
    kling_poll_interval_seconds: float = float(os.getenv('KLING_POLL_INTERVAL_SECONDS', '5'))
    kling_timeout_seconds: int = int(os.getenv('KLING_TIMEOUT_SECONDS', '900'))
    generated_media_dir: str = os.getenv('GENERATED_MEDIA_DIR', '../data/generated-media')
    generated_media_public_base_url: str = os.getenv(
        'GENERATED_MEDIA_PUBLIC_BASE_URL',
        '/generated',
    )
    ffmpeg_binary: str = os.getenv('FFMPEG_BINARY', 'ffmpeg')
    ffprobe_binary: str = os.getenv('FFPROBE_BINARY', 'ffprobe')
    subtitle_font_name: str = os.getenv('SUBTITLE_FONT_NAME', 'Noto Sans CJK SC')
    subtitle_fonts_dir: str = os.getenv('SUBTITLE_FONTS_DIR', '/usr/share/fonts/opentype/noto')
    text_tool_streaming_enabled: bool = os.getenv('TEXT_TOOL_STREAMING_ENABLED', 'true').strip().lower() in {
        '1', 'true', 'yes', 'on',
    }


settings = Settings()


def resolve_siliconflow_api_key(model_config: dict[str, Any] | None = None) -> str:
    """Prefer model config apiKey from execution context, then SILICONFLOW_API_KEY env."""
    if model_config:
        configured = model_config.get("apiKey")
        if configured is not None:
            configured_text = str(configured).strip()
            if configured_text and not configured_text.startswith("replace-with-"):
                return configured_text
    env_key = (settings.siliconflow_api_key or "").strip()
    return env_key


def _kling_ak_sk_from_extra_auth(extra_auth: Any) -> tuple[str, str]:
    if extra_auth is None or not str(extra_auth).strip():
        return "", ""
    try:
        auth_data = json.loads(str(extra_auth))
    except json.JSONDecodeError:
        return "", ""
    if not isinstance(auth_data, dict):
        return "", ""
    access_key = auth_data.get("accessKey") or auth_data.get("access_key")
    secret_key = auth_data.get("secretKey") or auth_data.get("secret_key")
    resolved_access = str(access_key).strip() if access_key is not None else ""
    resolved_secret = str(secret_key).strip() if secret_key is not None else ""
    if resolved_access.startswith("replace-with-") or resolved_secret.startswith("replace-with-"):
        return "", ""
    return resolved_access, resolved_secret


def resolve_kling_api_key(model_config: dict[str, Any] | None = None) -> str:
    """Prefer backend model config apiKey, then KLING_API_KEY env.

    When extraAuthJson already contains AK/SK, ignore apiKey so Kling always uses JWT.
    """
    if model_config:
        access_key, secret_key = _kling_ak_sk_from_extra_auth(model_config.get("extraAuthJson"))
        if access_key and secret_key:
            return ""
        configured = model_config.get("apiKey")
        if configured is not None:
            configured_text = str(configured).strip()
            if configured_text and not configured_text.startswith("replace-with-"):
                return configured_text
    return (settings.kling_api_key or "").strip()


def resolve_kling_credentials(model_config: dict[str, Any] | None = None) -> tuple[str, str]:
    """Resolve Kling Access Key / Secret Key.

    Prefer backend extraAuthJson:
    {"accessKey":"...","secretKey":"..."}
    Then keep the previous apiKey/minimaxGroupId fallback for existing rows.
    """
    access_key = ""
    secret_key = ""
    if model_config:
        parsed_access, parsed_secret = _kling_ak_sk_from_extra_auth(model_config.get("extraAuthJson"))
        if parsed_access:
            access_key = parsed_access
        if parsed_secret:
            secret_key = parsed_secret
        configured_access = model_config.get("apiKey")
        configured_secret = model_config.get("minimaxGroupId")
        if configured_access is not None:
            fallback_access = str(configured_access).strip()
            if not access_key and fallback_access:
                access_key = fallback_access
        if configured_secret is not None and not secret_key:
            secret_key = str(configured_secret).strip()
    if not access_key or access_key.startswith("replace-with-"):
        access_key = (settings.kling_access_key or "").strip()
    if not secret_key or secret_key.startswith("replace-with-"):
        secret_key = (settings.kling_secret_key or "").strip()
    return access_key, secret_key


def resolve_kling_credentials_source(model_config: dict[str, Any] | None = None) -> str:
    if model_config:
        parsed_access, parsed_secret = _kling_ak_sk_from_extra_auth(model_config.get("extraAuthJson"))
        if parsed_access and parsed_secret:
            return "model_config.extraAuthJson"
        configured_access = str(model_config.get("apiKey") or "").strip()
        configured_secret = str(model_config.get("minimaxGroupId") or "").strip()
        if configured_access and configured_secret:
            return "model_config.apiKey+minimaxGroupId"
        if configured_access:
            return "model_config.apiKey"
    if (settings.kling_access_key or "").strip() and (settings.kling_secret_key or "").strip():
        return "env.KLING_ACCESS_KEY+KLING_SECRET_KEY"
    if (settings.kling_api_key or "").strip():
        return "env.KLING_API_KEY"
    return "missing"


def resolve_infinitetalk_api_key(model_config: dict[str, Any] | None = None) -> str:
    if model_config:
        configured = model_config.get("apiKey")
        if configured is not None:
            configured_text = str(configured).strip()
            if configured_text and not configured_text.startswith("replace-with-"):
                return configured_text
    return (settings.infinitetalk_api_key or "").strip()
