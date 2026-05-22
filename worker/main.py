import logging

from config import settings
from task_queue.rabbitmq_consumer import RabbitMqConsumer
from task_queue.redis_consumer import RedisConsumer


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    consumer = RabbitMqConsumer() if settings.task_queue_backend.lower() == "rabbitmq" else RedisConsumer()
    consumer.start()


if __name__ == '__main__':
    main()
