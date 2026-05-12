package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskOutboxDispatcher {

    private final TaskOutboxService taskOutboxService;

    public TaskOutboxDispatcher(TaskOutboxService taskOutboxService) {
        this.taskOutboxService = taskOutboxService;
    }

    @Scheduled(fixedDelayString = "${app.task-outbox.dispatch-interval-ms:5000}")
    public void dispatchPending() {
        taskOutboxService.dispatchPending(50);
    }
}
