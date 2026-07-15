package com.aiminilab.aitoolmarket.credit.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CreditChangeNotifier {

    private final CreditEventStreamService creditEventStreamService;

    public CreditChangeNotifier(CreditEventStreamService creditEventStreamService) {
        this.creditEventStreamService = creditEventStreamService;
    }

    public void notifyAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            creditEventStreamService.publishAccountChanged(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                creditEventStreamService.publishAccountChanged(userId);
            }
        });
    }
}
