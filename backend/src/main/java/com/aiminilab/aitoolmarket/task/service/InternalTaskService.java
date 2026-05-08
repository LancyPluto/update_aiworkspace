package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;

public interface InternalTaskService {
    ExecutionContextResponse executionContext(Long taskId);

    TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request);

    TaskStatusResponse markSuccess(Long taskId, WorkerSuccessRequest request);

    TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request);
}
