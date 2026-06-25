import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor

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
        self.retry_delays_ms = [
            int(v.strip()) for v in settings.rabbitmq_retry_delays_ms.split(",") if v.strip()
        ]
        self.max_retries = min(settings.rabbitmq_max_retries, len(self.retry_delays_ms))
        self.concurrency = max(1, settings.worker_concurrency)
        self.prefetch_count = max(1, settings.rabbitmq_prefetch_count)
        self.task_exchange = os.getenv("RABBITMQ_TASK_EXCHANGE", "ai.task.exchange")
        self.task_routing_key = os.getenv("RABBITMQ_TASK_ROUTING_KEY", "tool.normal")
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

    def _declare_topology(self, channel) -> None:
        dlx_name = self.task_exchange + ".dlx"
        channel.exchange_declare(exchange=self.task_exchange, exchange_type="direct", durable=True)
        channel.exchange_declare(exchange=dlx_name, exchange_type="direct", durable=True)
        channel.queue_declare(
            queue=self.queue_name,
            durable=True,
            arguments={
                "x-dead-letter-exchange": dlx_name,
                "x-dead-letter-routing-key": "dead",
            },
        )
        channel.queue_bind(queue=self.queue_name, exchange=self.task_exchange, routing_key=self.task_routing_key)
        channel.queue_declare(queue=self.dead_queue_name, durable=True)
        channel.queue_bind(queue=self.dead_queue_name, exchange=dlx_name, routing_key="dead")
        for index, delay_ms in enumerate(self.retry_delays_ms):
            retry_queue = f"{self.retry_queue_prefix}.{index + 1}"
            channel.queue_declare(
                queue=retry_queue,
                durable=True,
                arguments={
                    "x-message-ttl": delay_ms,
                    "x-dead-letter-exchange": self.task_exchange,
                    "x-dead-letter-routing-key": self.task_routing_key,
                },
            )
        LOGGER.info("rabbitmq topology declared: exchange=%s queue=%s", self.task_exchange, self.queue_name)

    def _consume(self, connection: pika.BlockingConnection) -> None:
        channel = connection.channel()
        channel.confirm_delivery()
        self._declare_topology(channel)
        channel.basic_qos(prefetch_count=self.prefetch_count)
        executor = ThreadPoolExecutor(max_workers=self.concurrency, thread_name_prefix="task-worker")
        LOGGER.info(
            "worker is listening rabbitmq queue=%s basic_qos prefetch_count=%s concurrency=%s",
            self.queue_name,
            self.prefetch_count,
            self.concurrency,
        )

        def on_message(ch, method, properties, body: bytes):
            # Dispatch to the thread pool so up to `concurrency` tasks run in parallel.
            # The blocking IO thread stays free to receive more deliveries and keep
            # heartbeats alive while handlers (which can block ~145s) run off-thread.
            executor.submit(self._process_message, connection, ch, method, properties, body)

        channel.basic_consume(queue=self.queue_name, on_message_callback=on_message)
        try:
            channel.start_consuming()
        except KeyboardInterrupt:
            LOGGER.info("worker stopped by keyboard interrupt")
            if channel.is_open:
                channel.stop_consuming()
        finally:
            executor.shutdown(wait=True)
            self._safe_close(connection)

    def _process_message(self, connection, channel, method, properties, body: bytes) -> None:
        try:
            message = json.loads(body.decode("utf-8"))
            message_type = str(message.get("messageType") or "").strip().lower()
            if message_type not in {"subject_sync", "subject_delete"} and "taskId" not in message:
                raise ValueError(f"message missing taskId: {message}")
            result = self.handler.handle(message)
            LOGGER.info("task handled result=%s", result)
        except Exception as exc:
            # pika is not thread-safe: schedule ack/nack/publish back onto the IO thread.
            connection.add_callback_threadsafe(
                lambda exc=exc: self._handle_task_exception(channel, method, properties, body, exc)
            )
            return
        connection.add_callback_threadsafe(lambda: self._ack(channel, method.delivery_tag))

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
                self._log_task_exception(
                    exc,
                    "rabbitmq task handling failed, retry=%s/%s queue=%s error=%s",
                    next_retry,
                    self.max_retries,
                    retry_queue,
                    exc,
                )
                self._publish(channel, retry_queue, body, headers)
            else:
                headers["x-dead-reason"] = "max-retries-exceeded"
                self._log_task_exception(
                    exc,
                    "rabbitmq task handling failed, moved to dead queue error=%s",
                    exc,
                )
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
    def _log_task_exception(exc: Exception, message: str, *args) -> None:
        LOGGER.error(message, *args, exc_info=(type(exc), exc, exc.__traceback__))

    @staticmethod
    def _retry_count(headers: dict) -> int:
        try:
            return int(headers.get("x-retry-count", 0))
        except (TypeError, ValueError):
            return 0
