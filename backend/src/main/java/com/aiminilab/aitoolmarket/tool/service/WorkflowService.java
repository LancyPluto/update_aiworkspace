package com.aiminilab.aitoolmarket.tool.service;

import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;

import java.util.List;

public interface WorkflowService {

    WorkflowResponse getWorkflow(Long toolId);

    WorkflowResponse getWorkflowById(Long workflowId);

    WorkflowResponse saveWorkflow(Long toolId, UpsertWorkflowRequest request, Long operatorId);

    List<WorkflowVersionItemResponse> listVersions(Long workflowId, int pageNo, int pageSize);

    WorkflowResponse restoreVersion(Long workflowId, int targetVersion, Long operatorId);
}
