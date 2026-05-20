import os
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
    digital_human_video_provider: str = os.getenv('DIGITAL_HUMAN_VIDEO_PROVIDER', 'seedance')
    seedance_base_url: str = os.getenv('SEEDANCE_BASE_URL', 'https://ark.cn-beijing.volces.com')
    seedance_api_key: str = os.getenv('SEEDANCE_API_KEY', '')
    seedance_video_model: str = os.getenv('SEEDANCE_VIDEO_MODEL', 'doubao-seedance-1-5-pro-251215')
    seedance_video_create_path: str = os.getenv(
        'SEEDANCE_VIDEO_CREATE_PATH',
        '/api/v3/contents/generations/tasks',
    )
    seedance_video_poll_interval_seconds: float = float(os.getenv('SEEDANCE_VIDEO_POLL_INTERVAL_SECONDS', '5'))
    seedance_video_timeout_seconds: int = int(os.getenv('SEEDANCE_VIDEO_TIMEOUT_SECONDS', '900'))
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
