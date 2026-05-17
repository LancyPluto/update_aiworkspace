import json
import logging
import time
from typing import Any

import redis
from redis.exceptions import RedisError

from client.backend_client import BackendClient
from config import settings
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from handlers.image_generation_handler import ImageGenerationHandler
from handlers.text_task_handler import TextTaskHandler
from handlers.text_to_speech_handler import TextToSpeechHandler
from handlers.video_generation_handler import VideoGenerationHandler


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class RedisConsumer:
    def __init__(self, handler: Any | None = None) -> None:
        self.queue_name = settings.ai_task_queue
        self.handler = handler or TaskHandlerRouter()
        self.client = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_database,
            decode_responses=True,
        )

    def start(self) -> None:
        LOGGER.info(
            "worker is listening queue=%s redis=%s:%s db=%s",
            self.queue_name,
            settings.redis_host,
            settings.redis_port,
            settings.redis_database,
        )
        try:
            while True:
                try:
                    item = self.client.brpop(self.queue_name, timeout=5)
                except RedisError as exc:
                    LOGGER.warning(
                        "Redis 连接失败，worker 将在 %.1f 秒后重试。请确认 Redis 已启动且 REDIS_HOST/REDIS_PORT 配置正确。redis=%s:%s db=%s error=%s",
                        settings.redis_retry_interval_seconds,
                        settings.redis_host,
                        settings.redis_port,
                        settings.redis_database,
                        exc,
                    )
                    time.sleep(settings.redis_retry_interval_seconds)
                    continue
                if item is None:
                    continue

                _, raw_message = item
                self._process_raw_message(raw_message)
        except KeyboardInterrupt:
            LOGGER.info("worker stopped by keyboard interrupt")

    def _process_raw_message(self, raw_message: str) -> None:
        try:
            message = json.loads(raw_message)
        except json.JSONDecodeError:
            LOGGER.exception("invalid redis message: %s", raw_message)
            return

        if "taskId" not in message:
            LOGGER.error("redis message missing taskId: %s", message)
            return

        try:
            LOGGER.info("processing redis message taskId=%s traceId=%s", message.get("taskId"), message.get("traceId", "-"))
            result = self.handler.handle(message)
            LOGGER.info("task handled result=%s", result)
        except Exception:
            LOGGER.exception("task handling crashed, message=%s", message)


class TaskHandlerRouter:
    def __init__(
        self,
        text_handler: TextTaskHandler | None = None,
        digital_human_handler: DigitalHumanVideoHandler | None = None,
        image_generation_handler: ImageGenerationHandler | None = None,
        text_to_speech_handler: TextToSpeechHandler | None = None,
        video_generation_handler: VideoGenerationHandler | None = None,
        backend_client: BackendClient | None = None,
    ) -> None:
        self.text_handler = text_handler or TextTaskHandler()
        self.digital_human_handler = digital_human_handler or DigitalHumanVideoHandler()
        self.image_generation_handler = image_generation_handler or ImageGenerationHandler()
        self.text_to_speech_handler = text_to_speech_handler or TextToSpeechHandler()
        self.video_generation_handler = video_generation_handler or VideoGenerationHandler()
        self.backend_client = backend_client or BackendClient()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        context = self.backend_client.get_execution_context(int(message["taskId"]))
        status = str(context.get("status") or "").upper()
        if status in TERMINAL_TASK_STATUSES:
            LOGGER.info("skip terminal task taskId=%s status=%s", message.get("taskId"), status)
            return {"status": "SKIPPED", "taskId": int(message["taskId"]), "taskStatus": status}
        routed_message = {**message, "__executionContext": context}
        handler = str(context.get("executionHandler") or "").upper()
        if handler == "DIGITAL_HUMAN":
            return self.digital_human_handler.handle(routed_message)
        if handler == "IMAGE_GENERATION":
            return self.image_generation_handler.handle(routed_message)
        if handler == "TEXT_TO_SPEECH":
            return self.text_to_speech_handler.handle(routed_message)
        if handler == "VIDEO_GENERATION":
            return self.video_generation_handler.handle(routed_message)
        if context.get("toolCode") == "digital_human_agent":
            return self.digital_human_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "IMAGE_GENERATION":
            return self.image_generation_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "TEXT_TO_SPEECH":
            return self.text_to_speech_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "VIDEO_GENERATION":
            return self.video_generation_handler.handle(routed_message)
        return self.text_handler.handle(routed_message)
