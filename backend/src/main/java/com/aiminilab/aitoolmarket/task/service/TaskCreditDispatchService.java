package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.support.CreditInsufficientSupport;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;

@Service
public class TaskCreditDispatchService {

    private final CreditService creditService;

    public TaskCreditDispatchService(CreditService creditService) {
        this.creditService = creditService;
    }

    public void ensureDispatchAllowed(Long userId, AiTool tool, AgentModelConfig modelConfig) {
        int estimatedCredits = tool == null || tool.getEstimatedCreditCost() == null
                ? 0
                : Math.max(0, tool.getEstimatedCreditCost());
        CreditInsufficientSupport.ensureAvailable(
                creditService,
                userId,
                estimatedCredits,
                ErrorCode.CREDIT_NOT_ENOUGH,
                tool == null ? null : tool.getToolCode());
    }
}
