class BackendClient:
    def get_execution_context(self, task_id: int) -> dict:
        raise NotImplementedError('Implement backend internal API client.')

    def mark_processing(self, task_id: int) -> None:
        raise NotImplementedError('Implement processing callback.')

    def mark_success(self, task_id: int, payload: dict) -> None:
        raise NotImplementedError('Implement success callback.')

    def mark_failed(self, task_id: int, payload: dict) -> None:
        raise NotImplementedError('Implement failed callback.')
