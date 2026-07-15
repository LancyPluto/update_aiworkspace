package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.realtime.CreditChangeNotifier;
import com.aiminilab.aitoolmarket.credit.realtime.CreditEventStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreditChangeNotifierTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesImmediatelyWithoutTransactionSynchronization() {
        CreditEventStreamService streamService = mock(CreditEventStreamService.class);
        CreditChangeNotifier notifier = new CreditChangeNotifier(streamService);

        notifier.notifyAfterCommit(7L);

        verify(streamService).publishAccountChanged(7L);
    }

    @Test
    void waitsUntilTransactionCommit() {
        CreditEventStreamService streamService = mock(CreditEventStreamService.class);
        CreditChangeNotifier notifier = new CreditChangeNotifier(streamService);
        TransactionSynchronizationManager.initSynchronization();

        notifier.notifyAfterCommit(7L);
        verify(streamService, never()).publishAccountChanged(7L);

        TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations()
                .get(0);
        synchronization.afterCommit();

        verify(streamService).publishAccountChanged(7L);
    }

    @Test
    void doesNotPublishWhenTransactionRollsBack() {
        CreditEventStreamService streamService = mock(CreditEventStreamService.class);
        CreditChangeNotifier notifier = new CreditChangeNotifier(streamService);
        TransactionSynchronizationManager.initSynchronization();

        notifier.notifyAfterCommit(7L);
        TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations()
                .get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(streamService, never()).publishAccountChanged(7L);
    }
}
