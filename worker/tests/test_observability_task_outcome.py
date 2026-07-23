from observability.metrics import task_timer


def test_task_timer_records_failed_handler_result(monkeypatch):
    labels_seen = []

    class _Counter:
        def labels(self, *labels):
            labels_seen.append(labels)
            return self

        def inc(self):
            return None

        def dec(self):
            return None

        def observe(self, _value):
            return None

    monkeypatch.setattr("observability.metrics.TASKS_STARTED", _Counter())
    monkeypatch.setattr("observability.metrics.ACTIVE_TASKS", _Counter())
    monkeypatch.setattr("observability.metrics.TASKS_COMPLETED", _Counter())
    monkeypatch.setattr("observability.metrics.TASK_DURATION", _Counter())

    with task_timer({"toolCode": "demo", "toolType": "IMAGE_GENERATION"}) as outcome:
        outcome.update({"status": "FAILED", "errorCode": "MODEL_004"})

    assert ("demo", "IMAGE_GENERATION", "failed", "MODEL_004") in labels_seen
