package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditStatementLogResponse;
import com.aiminilab.aitoolmarket.credit.mapper.CreditStatementLogMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditStatementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CreditStatementServiceImpl implements CreditStatementService {

    private final CreditStatementLogMapper creditStatementLogMapper;

    public CreditStatementServiceImpl(CreditStatementLogMapper creditStatementLogMapper) {
        this.creditStatementLogMapper = creditStatementLogMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CreditStatementLogResponse> statementLogs(Long userId,
                                                                   Integer pageNo,
                                                                   Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        long total = creditStatementLogMapper.countStatementLogs(userId);
        List<CreditStatementLogResponse> list = total == 0
                ? List.of()
                : creditStatementLogMapper.findStatementLogs(userId, normalizedPageSize, offset);
        return PageResponse.of(list, total, pageNo, pageSize);
    }
}
