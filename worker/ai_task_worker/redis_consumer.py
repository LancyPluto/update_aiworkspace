import json
import logging
from typing import Iterator, Optional

import redis

from ai_task_worker.config import Settings
from ai_task_worker.schemas import TaskQueueMessage

logger = logging.getLogger(__name__)


class RedisTaskConsumer:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._r = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_db,
            decode_responses=True,
        )
        self._queue = settings.ai_task_queue

    def ping(self) -> None:
        self._r.ping()

    def iter_messages(self) -> Iterator[Optional[TaskQueueMessage]]:
        """
        阻塞读取队列。超时返回 None（便于主循环打心跳日志）。
        解析失败会记录日志并跳过该条（不 ACK 时由业务决定是否 DLQ；V1 先记录）。
        """
        while True:
            item = self._r.blpop(self._queue, timeout=self._settings.worker_block_timeout_sec)
            if item is None:
                yield None
                continue
            _key, raw = item
            try:
                data = json.loads(raw)
                yield TaskQueueMessage.model_validate(data)
            except (json.JSONDecodeError, ValueError) as e:
                logger.exception("队列消息无效，已跳过 raw=%r err=%s", raw, e)
