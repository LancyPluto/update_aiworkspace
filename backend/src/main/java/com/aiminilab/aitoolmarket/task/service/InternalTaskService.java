package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointRequest;
import com.aiminilab.aitoolmarket.task.dto.ProviderCheckpointResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerProcessingRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;

public interface InternalTaskService {
    ExecutionContextResponse executionContext(Long taskId);

    ClaimTaskResponse claim(Long taskId, ClaimTaskRequest request);

    ClaimTaskResponse renewLease(Long taskId, ClaimTaskRequest request);

    ProviderCheckpointResponse saveProviderCheckpoint(Long taskId, ProviderCheckpointRequest request);

    TaskStatusResponse markProcessing(Long taskId, WorkerProcessingRequest request);

    TaskStatusResponse markSuccess(Long taskId, WorkerSuccessRequest request);

    TaskStatusResponse markFailed(Long taskId, WorkerFailedRequest request);
}
