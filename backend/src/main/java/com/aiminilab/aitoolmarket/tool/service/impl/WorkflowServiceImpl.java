package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WorkflowServiceImpl implements WorkflowService {

    private final ToolWorkflowMapper workflowMapper;
    private final ToolWorkflowVersionMapper versionMapper;

    public WorkflowServiceImpl(ToolWorkflowMapper workflowMapper,
                               ToolWorkflowVersionMapper versionMapper) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public WorkflowResponse getWorkflow(Long toolId) {
        return workflowMapper.findByToolId(toolId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Override
    public WorkflowResponse getWorkflowById(Long workflowId) {
        return toResponse(workflowMapper.selectById(workflowId));
    }

    @Override
    @Transactional
    public WorkflowResponse saveWorkflow(Long toolId, UpsertWorkflowRequest request, Long operatorId) {
        ToolWorkflow existing = workflowMapper.selectByToolId(toolId);
        if (existing != null) {
            // Snapshot current version before overwriting
            ToolWorkflowVersion snapshot = new ToolWorkflowVersion();
            snapshot.setWorkflowId(existing.getId());
            snapshot.setVersion(existing.getVersion());
            snapshot.setNodesJson(existing.getNodesJson());
            snapshot.setEdgesJson(existing.getEdgesJson());
            snapshot.setGroupsJson(existing.getGroupsJson());
            snapshot.setConfigJson(existing.getConfigJson());
            snapshot.setCreatedBy(operatorId);
            versionMapper.insert(snapshot);

            int newVersion = existing.getVersion() + 1;
            workflowMapper.updateWorkflowContent(
                    existing.getId(),
                    request.nodesJson(),
                    request.edgesJson(),
                    request.groupsJson(),
                    request.configJson(),
                    newVersion,
                    operatorId
            );
            return toResponse(workflowMapper.selectById(existing.getId()));
        } else {
            ToolWorkflow workflow = new ToolWorkflow();
            workflow.setToolId(toolId);
            workflow.setWorkflowName(request.workflowName());
            workflow.setNodesJson(request.nodesJson());
            workflow.setEdgesJson(request.edgesJson());
            workflow.setGroupsJson(request.groupsJson());
            workflow.setConfigJson(request.configJson());
            if (request.status() != null && !request.status().isBlank()) {
                workflow.setStatus(request.status());
            }
            workflowMapper.insertWorkflow(workflow, operatorId);
            return toResponse(workflow);
        }
    }

    @Override
    public List<WorkflowVersionItemResponse> listVersions(Long workflowId, int pageNo, int pageSize) {
        int offset = Math.max(0, (pageNo - 1)) * Math.max(1, pageSize);
        return versionMapper.selectByWorkflowId(workflowId, pageSize, offset)
                .stream()
                .map(v -> new WorkflowVersionItemResponse(
                        v.getId(), v.getVersion(), v.getSnapshotLabel(),
                        v.getCreatedBy(), v.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public WorkflowResponse restoreVersion(Long workflowId, int targetVersion, Long operatorId) {
        ToolWorkflow current = workflowMapper.selectById(workflowId);
        if (current == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        ToolWorkflowVersion snapshot = versionMapper.selectByWorkflowIdAndVersion(workflowId, targetVersion);
        if (snapshot == null) {
            throw new IllegalArgumentException("Version not found: " + targetVersion);
        }

        // Snapshot current state before restore
        ToolWorkflowVersion newSnapshot = new ToolWorkflowVersion();
        newSnapshot.setWorkflowId(workflowId);
        newSnapshot.setVersion(current.getVersion());
        newSnapshot.setNodesJson(current.getNodesJson());
        newSnapshot.setEdgesJson(current.getEdgesJson());
        newSnapshot.setGroupsJson(current.getGroupsJson());
        newSnapshot.setConfigJson(current.getConfigJson());
        newSnapshot.setSnapshotLabel("Before restore v" + targetVersion);
        newSnapshot.setCreatedBy(operatorId);
        versionMapper.insert(newSnapshot);

        int nextVersion = current.getVersion() + 1;
        workflowMapper.updateWorkflowContent(
                workflowId,
                snapshot.getNodesJson(),
                snapshot.getEdgesJson(),
                snapshot.getGroupsJson(),
                snapshot.getConfigJson(),
                nextVersion,
                operatorId
        );
        return toResponse(workflowMapper.selectById(workflowId));
    }

    private WorkflowResponse toResponse(ToolWorkflow wf) {
        if (wf == null) return null;
        return new WorkflowResponse(
                wf.getId(), wf.getToolId(), wf.getWorkflowName(),
                wf.getNodesJson(), wf.getEdgesJson(),
                wf.getGroupsJson(), wf.getConfigJson(),
                wf.getVersion() != null ? wf.getVersion() : 1,
                wf.getStatus(),
                wf.getCreatedBy(), wf.getUpdatedBy(),
                wf.getCreatedAt(), wf.getUpdatedAt()
        );
    }
}
