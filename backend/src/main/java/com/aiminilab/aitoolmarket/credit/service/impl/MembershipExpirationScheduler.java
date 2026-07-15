package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.mapper.UserMembershipMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class MembershipExpirationScheduler {
    private static final Logger log = LoggerFactory.getLogger(MembershipExpirationScheduler.class);
    private static final int BATCH_SIZE = 200;

    private final UserMembershipMapper membershipMapper;
    private final CreditService creditService;

    public MembershipExpirationScheduler(UserMembershipMapper membershipMapper, CreditService creditService) {
        this.membershipMapper = membershipMapper;
        this.creditService = creditService;
    }

    @Scheduled(fixedDelayString = "${app.membership.expiration-scan-interval-ms:60000}")
    public int expireExpiredMemberships() {
        List<Long> userIds = membershipMapper.findExpiredActiveUserIds(LocalDateTime.now(), BATCH_SIZE);
        for (Long userId : userIds) {
            try {
                creditService.expireMembershipIfNeeded(userId);
            } catch (RuntimeException exception) {
                log.error("Failed to expire membership credits: userId={}", userId, exception);
            }
        }
        return userIds.size();
    }
}
