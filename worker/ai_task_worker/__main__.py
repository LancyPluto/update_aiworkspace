from ai_task_worker.worker import configure_logging, run_worker


def main() -> None:
    configure_logging()
    run_worker()


if __name__ == "__main__":
    main()
