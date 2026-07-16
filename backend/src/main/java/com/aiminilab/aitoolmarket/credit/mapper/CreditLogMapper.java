package com.aiminilab.aitoolmarket.credit.mapper;

import com.aiminilab.aitoolmarket.credit.entity.CreditLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

public interface CreditLogMapper extends BaseMapper<CreditLog> {

    Set<String> USER_HIDDEN_LOG_TYPES = Set.of("FREEZE", "RELEASE");

    default List<CreditLog> findLogs(Long userId, String logType, int limit, int offset, boolean includeInternal) {
        LambdaQueryWrapper<CreditLog> wrapper = new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId)
                .orderByDesc(CreditLog::getCreatedAt)
                .orderByDesc(CreditLog::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        if (logType != null && !logType.isBlank()) {
            wrapper.eq(CreditLog::getLogType, logType.trim());
        } else if (!includeInternal) {
            wrapper.notIn(CreditLog::getLogType, USER_HIDDEN_LOG_TYPES);
        }
        return selectList(wrapper);
    }

    default long countLogs(Long userId, String logType, boolean includeInternal) {
        LambdaQueryWrapper<CreditLog> wrapper = new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId);
        if (logType != null && !logType.isBlank()) {
            wrapper.eq(CreditLog::getLogType, logType.trim());
        } else if (!includeInternal) {
            wrapper.notIn(CreditLog::getLogType, USER_HIDDEN_LOG_TYPES);
        }
        return selectCount(wrapper);
    }

    default boolean existsByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        Long count = selectCount(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        return count != null && count > 0;
    }

    default CreditLog selectByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return selectOne(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
    }

    @Select("SELECT * FROM credit_logs WHERE idempotency_key = #{idempotencyKey} LIMIT 1 FOR UPDATE")
    CreditLog selectByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);
}
