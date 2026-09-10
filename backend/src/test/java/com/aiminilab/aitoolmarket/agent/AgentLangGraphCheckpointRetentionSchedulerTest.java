package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentLangGraphCheckpointRetentionScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AgentLangGraphCheckpointRetentionSchedulerTest {
    @Test
    void deletesWritesBeforeCheckpointsInBatchesAndIsIdempotentWhenEmpty() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.findExpiredLangGraphCheckpointRuns(any(), anyInt()))
                .thenReturn(List.of(10L, 11L), List.of());
        when(mapper.deleteExpiredLangGraphCheckpoints(List.of(10L, 11L))).thenReturn(2);
        AgentLangGraphCheckpointRetentionScheduler scheduler =
                new AgentLangGraphCheckpointRetentionScheduler(mapper, 2, 30);

        scheduler.purgeExpiredCheckpoints();
        scheduler.purgeExpiredCheckpoints();

        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteExpiredLangGraphCheckpointWrites(List.of(10L, 11L));
        order.verify(mapper).deleteExpiredLangGraphCheckpoints(List.of(10L, 11L));
        org.mockito.Mockito.verify(mapper, times(3)).findExpiredLangGraphCheckpointRuns(any(), org.mockito.ArgumentMatchers.eq(2));
    }
}
