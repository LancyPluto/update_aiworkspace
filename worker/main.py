import logging

from config import settings
from observability.metrics import start_metrics_server
from observability.log_context import install_trace_id_log_record_factory
from task_queue.rabbitmq_consumer import RabbitMqConsumer
from task_queue.redis_consumer import RedisConsumer

LOGGER = logging.getLogger(__name__)


def main() -> None:
    install_trace_id_log_record_factory()
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] traceId=%(traceId)s %(message)s",
    )
    start_metrics_server(settings.metrics_enabled, settings.metrics_host, settings.metrics_port)
    queue_backend = settings.task_queue_backend.strip().lower()
    LOGGER.info("worker task queue backend=%s", queue_backend)
    consumer = RabbitMqConsumer() if queue_backend == "rabbitmq" else RedisConsumer()
    consumer.start()


if __name__ == '__main__':
    main()

