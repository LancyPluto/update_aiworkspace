package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;

public interface CreditService {
    CreditAccountResponse account(Long userId);

    void deductForTask(Long userId, Long taskId, int amount);
}
