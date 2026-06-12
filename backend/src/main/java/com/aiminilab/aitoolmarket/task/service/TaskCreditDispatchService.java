package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.support.CreditInsufficientSupport;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class TaskCreditDispatchService {

    /** Interactive workflow tools charge per model step; only require a minimal balance to start. */
    static final int WORKFLOW_MIN_DISPATCH_CREDITS = 1;

    private final CreditService creditService;
    private final WorkflowExecutionService workflowExecutionService;

    public TaskCreditDispatchService(CreditService creditService,
                                     @Lazy WorkflowExecutionService workflowExecutionService) {
        this.creditService = creditService;
        this.workflowExecutionService = workflowExecutionService;
    }

    public void ensureDispatchAllowed(Long userId, AiTool tool, AgentModelConfig modelConfig) {
        int requiredCredits;
        if (tool != null && workflowExecutionService.shouldUseWorkflow(tool)) {
            requiredCredits = WORKFLOW_MIN_DISPATCH_CREDITS;
        } else {
            requiredCredits = tool == null || tool.getEstimatedCreditCost() == null
                    ? 0
                    : Math.max(0, tool.getEstimatedCreditCost());
        }
        CreditInsufficientSupport.ensureAvailable(
                creditService,
                userId,
                requiredCredits,
                ErrorCode.CREDIT_NOT_ENOUGH,
                tool == null ? null : tool.getToolCode());
    }
}
