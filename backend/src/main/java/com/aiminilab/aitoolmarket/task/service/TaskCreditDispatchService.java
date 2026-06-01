package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;

@Service
public class TaskCreditDispatchService {

    private final CreditService creditService;
    private final TaskCreditEstimateService taskCreditEstimateService;

    public TaskCreditDispatchService(
            CreditService creditService,
            TaskCreditEstimateService taskCreditEstimateService
    ) {
        this.creditService = creditService;
        this.taskCreditEstimateService = taskCreditEstimateService;
    }

    public void ensureDispatchAllowed(Long userId, AiTool tool, AgentModelConfig modelConfig) {
        int required = taskCreditEstimateService.estimateTaskCredits(tool, modelConfig);
        if (required <= 0) {
            return;
        }
        CreditAccountResponse account = creditService.account(userId);
        int available = account.available() == null ? 0 : account.available();
        if (available < required) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "可用算力不足，无法完成本次工具调用");
        }
    }
}
