package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;

import java.math.BigDecimal;

public record WorkflowRuntimeAdmission(
        ToolWorkflow workflow,
        ToolWorkflowVersion version,
        WorkflowDsl dsl,
        boolean paidRun,
        long estimatedRunCredits,
        BigDecimal providerCostReservedCny
) {
}
