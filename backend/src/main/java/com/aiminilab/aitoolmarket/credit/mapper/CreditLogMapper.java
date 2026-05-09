package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CreditLogMapper extends BaseMapper<CreditLog> {

    default List<CreditLog> findLogs(Long userId, String logType, int limit, int offset) {
        LambdaQueryWrapper<CreditLog> wrapper = new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId)
                .orderByDesc(CreditLog::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        if (logType != null && !logType.isBlank()) {
            wrapper.eq(CreditLog::getLogType, logType.trim());
        }
        return selectList(wrapper);
    }

    default long countLogs(Long userId, String logType) {
        LambdaQueryWrapper<CreditLog> wrapper = new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId);
        if (logType != null && !logType.isBlank()) {
            wrapper.eq(CreditLog::getLogType, logType.trim());
        }
        return selectCount(wrapper);
    }
}
