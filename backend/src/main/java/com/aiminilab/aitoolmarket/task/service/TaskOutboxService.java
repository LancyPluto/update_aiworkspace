package com.aiminilab.aitoolmarket.task.service;

public interface TaskOutboxService {

    void enqueueTaskCreated(Long taskId);

    void enqueueTaskRetry(Long taskId);

    int dispatchPending(int limit);
}
