package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    @Test
    void allowsOnlyExplicitTransitions() {
        assertThat(TaskStateMachine.canTransition(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.QUEUED.name(), TaskStatus.CANCELLED.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.QUEUED.name(), TaskStatus.TIMEOUT.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.PROCESSING.name(), TaskStatus.SUCCESS.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.PROCESSING.name(), TaskStatus.FAILED.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.PROCESSING.name(), TaskStatus.TIMEOUT.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.PROCESSING.name(), TaskStatus.CANCELLED.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.FAILED.name(), TaskStatus.RETRYING.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.TIMEOUT.name(), TaskStatus.RETRYING.name())).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.RETRYING.name(), TaskStatus.QUEUED.name())).isTrue();

        assertThat(TaskStateMachine.canTransition(TaskStatus.QUEUED.name(), TaskStatus.SUCCESS.name())).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskStatus.FAILED.name(), TaskStatus.SUCCESS.name())).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskStatus.SUCCESS.name(), TaskStatus.FAILED.name())).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskStatus.CANCELLED.name(), TaskStatus.PROCESSING.name())).isFalse();
    }

    @Test
    void treatsSuccessAndCancelledAsTerminal() {
        assertThat(TaskStateMachine.isTerminal(TaskStatus.SUCCESS.name())).isTrue();
        assertThat(TaskStateMachine.isTerminal(TaskStatus.CANCELLED.name())).isTrue();
        assertThat(TaskStateMachine.isTerminal(TaskStatus.TIMEOUT.name())).isTrue();
        assertThat(TaskStateMachine.isTerminal(TaskStatus.FAILED.name())).isTrue();
        assertThat(TaskStateMachine.isTerminal(TaskStatus.PROCESSING.name())).isFalse();
    }

    @Test
    void invalidTransitionReturnsBusinessError() {
        assertThatThrownBy(() -> TaskStateMachine.ensureTransition(TaskStatus.FAILED.name(), TaskStatus.SUCCESS.name()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("SUCCESS");
    }
}
