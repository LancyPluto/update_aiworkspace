package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditStatementLogResponse;

public interface CreditStatementService {

    PageResponse<CreditStatementLogResponse> statementLogs(Long userId, Integer pageNo, Integer pageSize);
}
