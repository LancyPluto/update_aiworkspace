import json
import logging
from typing import Any

import redis

from config import settings
from handlers.text_task_handler import TextTaskHandler


LOGGER = logging.getLogger(__name__)


class RedisConsumer:
    def __init__(self, handler: TextTaskHandler | None = None) -> None:
        self.queue_name = settings.ai_task_queue
        self.handler = handler or TextTaskHandler()
        self.client = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_database,
            decode_responses=True,
        )

    def start(self) -> None:
        LOGGER.info("worker is listening queue=%s", self.queue_name)
        try:
            while True:
                item = self.client.brpop(self.queue_name, timeout=5)
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
