package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.credit.support.CreditInsufficientSupport;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;

@Service
public class TaskCreditDispatchService {

    private final CreditService creditService;
    private final TaskCreditEstimateService taskCreditEstimateService;

    public TaskCreditDispatchService(CreditService creditService, TaskCreditEstimateService taskCreditEstimateService) {
        this.creditService = creditService;
        this.taskCreditEstimateService = taskCreditEstimateService;
    }

    public void ensureDispatchAllowed(Long userId, AiTool tool, AgentModelConfig modelConfig) {
        int estimatedCredits = taskCreditEstimateService.estimateTaskCredits(tool, modelConfig);
        CreditInsufficientSupport.ensureAvailable(
                creditService,
                userId,
                estimatedCredits,
                ErrorCode.CREDIT_NOT_ENOUGH,
                tool == null ? null : tool.getToolCode());
    }
}
