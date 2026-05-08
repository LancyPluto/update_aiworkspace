from queue.redis_consumer import RedisConsumer


def main() -> None:
    consumer = RedisConsumer()
    consumer.start()


if __name__ == '__main__':
    main()
