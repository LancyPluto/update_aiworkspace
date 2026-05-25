import json
import logging
import time

import pika
from pika.exceptions import AMQPError, ConnectionWrongStateError, UnroutableError

from config import settings
from task_queue.task_handler_router import TaskHandlerRouter


LOGGER = logging.getLogger(__name__)


class RabbitMqConsumer:
    def __init__(self, handler: TaskHandlerRouter | None = None) -> None:
        self.queue_name = settings.rabbitmq_task_queue
        self.dead_queue_name = settings.rabbitmq_dead_queue
        self.retry_queue_prefix = settings.rabbitmq_retry_queue_prefix
        retry_queue_count = len([value for value in settings.rabbitmq_retry_delays_ms.split(",") if value.strip()])
        self.max_retries = min(settings.rabbitmq_max_retries, retry_queue_count)
        self.handler = handler or TaskHandlerRouter()

    def start(self) -> None:
        credentials = pika.PlainCredentials(settings.rabbitmq_username, settings.rabbitmq_password)
        parameters = pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            credentials=credentials,
            heartbeat=settings.rabbitmq_heartbeat_seconds,
            blocked_connection_timeout=settings.rabbitmq_blocked_connection_timeout_seconds,
        )
        backoff_seconds = 1
        while True:
            try:
                connection = pika.BlockingConnection(parameters)
                self._consume(connection)
                return
            except AMQPError:
                LOGGER.exception("rabbitmq connection/consume failed, retrying in %ss", backoff_seconds)
                time.sleep(backoff_seconds)
                backoff_seconds = min(backoff_seconds * 2, 30)

    def _consume(self, connection: pika.BlockingConnection) -> None:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_qos(prefetch_count=1)
        LOGGER.info("worker is listening rabbitmq queue=%s", self.queue_name)

        def callback(ch, method, properties, body: bytes):
            try:
                message = json.loads(body.decode("utf-8"))
                if "taskId" not in message:
                    raise ValueError(f"message missing taskId: {message}")
                result = self.handler.handle(message)
                LOGGER.info("task handled result=%s", result)
            except Exception as exc:
                self._handle_task_exception(ch, method, properties, body, exc)
                return
            self._ack(ch, method.delivery_tag)

        channel.basic_consume(queue=self.queue_name, on_message_callback=callback)
        try:
            channel.start_consuming()
        except KeyboardInterrupt:
            LOGGER.info("worker stopped by keyboard interrupt")
            if channel.is_open:
                channel.stop_consuming()
        finally:
            self._safe_close(connection)

    def _publish(self, channel, routing_key: str, body: bytes, headers: dict) -> None:
        self._ensure_channel_open(channel)
        channel.basic_publish(
            exchange="",
            routing_key=routing_key,
            body=body,
            mandatory=True,
            properties=pika.BasicProperties(
                delivery_mode=pika.DeliveryMode.Persistent,
                content_type="application/json",
                headers=headers,
            ),
        )

    def _handle_task_exception(self, channel, method, properties, body: bytes, exc: Exception) -> None:
        self._ensure_channel_open(channel)
        retry_count = self._retry_count(properties.headers or {})
        headers = dict(properties.headers or {})
        headers["x-last-error"] = str(exc)[:500]
        try:
            if retry_count < self.max_retries:
                next_retry = retry_count + 1
                headers["x-retry-count"] = next_retry
                retry_queue = f"{self.retry_queue_prefix}.{next_retry}"
                LOGGER.exception(
                    "rabbitmq task handling failed, retry=%s/%s queue=%s",
                    next_retry,
                    self.max_retries,
                    retry_queue,
                )
                self._publish(channel, retry_queue, body, headers)
            else:
                headers["x-dead-reason"] = "max-retries-exceeded"
                LOGGER.exception("rabbitmq task handling failed, moved to dead queue")
                self._publish(channel, self.dead_queue_name, body, headers)
            self._ack(channel, method.delivery_tag)
        except UnroutableError:
            LOGGER.exception("rabbitmq retry/dead queue is missing, original message will be requeued")
            self._nack_requeue(channel, method.delivery_tag)

    def _ack(self, channel, delivery_tag) -> None:
        self._ensure_channel_open(channel)
        channel.basic_ack(delivery_tag=delivery_tag)

    def _nack_requeue(self, channel, delivery_tag) -> None:
        self._ensure_channel_open(channel)
        channel.basic_nack(delivery_tag=delivery_tag, requeue=True)

    @staticmethod
    def _ensure_channel_open(channel) -> None:
        if not channel.is_open:
            raise ConnectionWrongStateError("rabbitmq channel is closed")

    @staticmethod
    def _safe_close(connection: pika.BlockingConnection) -> None:
        if not connection.is_open:
            return
        try:
            connection.close()
        except ConnectionWrongStateError:
            LOGGER.debug("rabbitmq connection already closed before close()")

    @staticmethod
    def _retry_count(headers: dict) -> int:
        try:
            return int(headers.get("x-retry-count", 0))
        except (TypeError, ValueError):
            return 0
