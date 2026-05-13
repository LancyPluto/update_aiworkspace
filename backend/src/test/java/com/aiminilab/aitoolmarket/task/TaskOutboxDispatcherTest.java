package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskOutboxDispatcherTest {

    @Test
    void scheduledTickDispatchesPendingOutboxEvents() {
        TaskOutboxService taskOutboxService = mock(TaskOutboxService.class);
        TaskOutboxDispatcher dispatcher = new TaskOutboxDispatcher(taskOutboxService);

        dispatcher.dispatchPending();

        verify(taskOutboxService).dispatchPending(50);
    }
}
