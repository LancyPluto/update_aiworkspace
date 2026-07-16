package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class TaskIdempotencyRecoveryService {

    private final TaskMapper taskMapper;

    public TaskIdempotencyRecoveryService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public TaskStatusResponse recover(Long userId,
                                      String idempotencyKey,
                                      Long requestedToolId,
                                      DuplicateKeyException duplicate) {
        AiTask existing = taskMapper.findRawByUserIdAndIdempotencyKeyIncludingDeleted(userId, idempotencyKey)
                .orElseThrow(() -> duplicate);
        if (!Objects.equals(existing.getToolId(), requestedToolId)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "clientRequestId 已用于其他工具"
            );
        }
        AiTask hydrated = taskMapper.findById(existing.getId()).orElse(existing);
        return TaskStatusResponse.from(hydrated);
    }
}
