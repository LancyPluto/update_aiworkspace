package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslParser;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidationResult;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidator;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WorkflowDslService {

    private final ToolWorkflowMapper workflowMapper;
    private final WorkflowDslParser parser;
    private final WorkflowDslValidator validator;

    public WorkflowDslService(ToolWorkflowMapper workflowMapper,
                              WorkflowDslParser parser,
                              WorkflowDslValidator validator) {
        this.workflowMapper = workflowMapper;
        this.parser = parser;
        this.validator = validator;
    }

    public Optional<ToolWorkflow> findPublishedWorkflow(Long toolId) {
        return workflowMapper.findPublishedByToolId(toolId);
    }

    public WorkflowDsl parse(ToolWorkflow workflow) {
        return parser.parse(workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getConfigJson());
    }

    public WorkflowDsl parse(String nodesJson, String edgesJson, String configJson) {
        return parser.parse(nodesJson, edgesJson, configJson);
    }

    public WorkflowDslValidationResult validate(String nodesJson, String edgesJson, String configJson) {
        WorkflowDsl dsl = parse(nodesJson, edgesJson, configJson);
        return validator.validate(dsl);
    }

    public WorkflowDslValidationResult validate(ToolWorkflow workflow) {
        return validator.validate(parse(workflow));
    }
}
