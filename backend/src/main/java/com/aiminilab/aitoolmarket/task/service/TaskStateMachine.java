package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

import java.util.Map;
import java.util.Set;

public final class TaskStateMachine {

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            TaskStatus.QUEUED.name(), Set.of(TaskStatus.PROCESSING.name(), TaskStatus.CANCELLED.name(), TaskStatus.TIMEOUT.name()),
            TaskStatus.PROCESSING.name(), Set.of(
                    TaskStatus.SUCCESS.name(),
                    TaskStatus.FAILED.name(),
                    TaskStatus.TIMEOUT.name(),
                    TaskStatus.CANCELLED.name(),
                    TaskStatus.AWAITING_USER.name(),
                    TaskStatus.AWAITING_FUNDS.name()
            ),
            TaskStatus.AWAITING_USER.name(), Set.of(TaskStatus.PROCESSING.name(), TaskStatus.CANCELLED.name()),
            TaskStatus.AWAITING_FUNDS.name(), Set.of(TaskStatus.PROCESSING.name(), TaskStatus.CANCELLED.name()),
            TaskStatus.FAILED.name(), Set.of(TaskStatus.RETRYING.name()),
            TaskStatus.TIMEOUT.name(), Set.of(TaskStatus.RETRYING.name()),
            TaskStatus.RETRYING.name(), Set.of(TaskStatus.QUEUED.name())
    );

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            TaskStatus.SUCCESS.name(),
            TaskStatus.FAILED.name(),
            TaskStatus.TIMEOUT.name(),
            TaskStatus.CANCELLED.name()
    );

    private TaskStateMachine() {
    }

    public static boolean canTransition(String currentStatus, String nextStatus) {
        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(nextStatus);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    public static void ensureTransition(String currentStatus, String nextStatus) {
        if (!canTransition(currentStatus, nextStatus)) {
            throw new BusinessException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "Invalid task status transition: %s -> %s".formatted(currentStatus, nextStatus)
            );
        }
    }
}
