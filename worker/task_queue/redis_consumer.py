import json
import logging
import time
from typing import Any

import redis
from redis.exceptions import RedisError

from config import settings
from task_queue.task_handler_router import TaskHandlerRouter


LOGGER = logging.getLogger(__name__)


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
