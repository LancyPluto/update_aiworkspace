import logging

from task_queue.redis_consumer import RedisConsumer


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    consumer = RedisConsumer()
    consumer.start()


if __name__ == '__main__':
    main()
