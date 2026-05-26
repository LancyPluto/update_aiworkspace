package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.ppt.entity.PptStepBillingLog;
import com.aiminilab.aitoolmarket.ppt.mapper.PptStepBillingLogMapper;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflowStep;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
public class PptBillingService {

    private final CreditService creditService;
    private final PptWorkflowService pptWorkflowService;
    private final PptStepBillingLogMapper billingLogMapper;

    public PptBillingService(CreditService creditService,
                             PptWorkflowService pptWorkflowService,
                             PptStepBillingLogMapper billingLogMapper) {
        this.creditService = creditService;
        this.pptWorkflowService = pptWorkflowService;
        this.billingLogMapper = billingLogMapper;
    }

    @Transactional
    public <T> T chargeStep(Long userId,
                            Long bindingId,
                            AiTool tool,
                            PptWorkflow workflow,
                            String stepCode,
                            String clientRequestId,
                            Supplier<T> action) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            var existing = billingLogMapper.findIdempotent(bindingId, stepCode, clientRequestId);
            if (existing.isPresent()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "该步骤请求已处理，请勿重复提交");
            }
        }

        PptWorkflowStep step = pptWorkflowService.requireEnabledStep(workflow, stepCode);
        int credits = step.getCredits() > 0 ? step.getCredits() : pptWorkflowService.resolveCredits(tool, workflow, stepCode);
        if (credits <= 0) {
            return action.get();
        }

        Long billingSourceId = bindingId == null ? 0L : bindingId;
        creditService.freeze(userId, CreditSourceType.PPT_STEP, billingSourceId, credits);
        try {
            T result = action.get();
            creditService.settle(userId, CreditSourceType.PPT_STEP, billingSourceId, credits);
            writeBillingLog(userId, bindingId, stepCode, credits, clientRequestId);
            return result;
        } catch (RuntimeException exception) {
            creditService.release(userId, CreditSourceType.PPT_STEP, billingSourceId, credits);
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, exception.getMessage());
        }
    }

    private void writeBillingLog(Long userId, Long bindingId, String stepCode, int credits, String clientRequestId) {
        if (bindingId == null) {
            return;
        }
        PptStepBillingLog log = new PptStepBillingLog();
        log.setUserId(userId);
        log.setBindingId(bindingId);
        log.setStepCode(stepCode);
        log.setCreditsCharged(credits);
        log.setClientRequestId(blankToNull(clientRequestId));
        log.setCreatedAt(LocalDateTime.now());
        billingLogMapper.insertLog(log);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
