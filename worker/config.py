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
    siliconflow_video_model: str = os.getenv('SILICONFLOW_VIDEO_MODEL', 'Wan-AI/Wan2.2-T2V-A14B')
    siliconflow_image_to_video_model: str = os.getenv(
        'SILICONFLOW_IMAGE_TO_VIDEO_MODEL',
        'Wan-AI/Wan2.2-I2V-A14B',
    )
    siliconflow_voice_model: str = os.getenv('SILICONFLOW_VOICE_MODEL', 'FunAudioLLM/CosyVoice2-0.5B')
    siliconflow_asr_model: str = os.getenv('SILICONFLOW_ASR_MODEL', 'TeleAI/TeleSpeechASR')
    siliconflow_image_model: str = os.getenv('SILICONFLOW_IMAGE_MODEL', 'Tongyi-MAI/Z-Image-Turbo')
    siliconflow_video_poll_interval_seconds: float = float(
        os.getenv('SILICONFLOW_VIDEO_POLL_INTERVAL_SECONDS', '5')
    )
    siliconflow_video_timeout_seconds: int = int(
        os.getenv('SILICONFLOW_VIDEO_TIMEOUT_SECONDS', '600')
    )
    digital_human_video_provider: str = os.getenv('DIGITAL_HUMAN_VIDEO_PROVIDER', 'siliconflow')
    skywork_base_url: str = os.getenv('SKYWORK_BASE_URL', 'https://api-tools.skywork.ai/theme-gateway')
    skywork_api_key: str = os.getenv('SKYWORK_API_KEY', '')
    skywork_video_model: str = os.getenv('SKYWORK_VIDEO_MODEL', 'seedance/seedance-2.0')
    skywork_video_endpoint: str = os.getenv('SKYWORK_VIDEO_ENDPOINT', '/api/sse/video/create')
    skywork_video_timeout_seconds: int = int(os.getenv('SKYWORK_VIDEO_TIMEOUT_SECONDS', '900'))
    generated_media_dir: str = os.getenv('GENERATED_MEDIA_DIR', '../data/generated-media')
    generated_media_public_base_url: str = os.getenv(
        'GENERATED_MEDIA_PUBLIC_BASE_URL',
        '/generated',
    )
    ffmpeg_binary: str = os.getenv('FFMPEG_BINARY', 'ffmpeg')
    ffprobe_binary: str = os.getenv('FFPROBE_BINARY', 'ffprobe')


settings = Settings()
