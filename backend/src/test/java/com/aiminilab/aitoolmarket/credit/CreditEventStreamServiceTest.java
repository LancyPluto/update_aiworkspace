package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.realtime.CreditEventStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CreditEventStreamServiceTest {

    @Test
    void keepsMultipleConnectionsPerUserAndIsolatesUsers() {
        CreditEventStreamService service = new CreditEventStreamService(10, 60_000L, SseEmitter::new);

        service.subscribe(7L);
        service.subscribe(7L);
        service.subscribe(8L);

        assertThat(service.connectionCount(7L)).isEqualTo(2);
        assertThat(service.connectionCount(8L)).isEqualTo(1);
        assertThat(service.connectionCount(9L)).isZero();
    }

    @Test
    void evictsOldestConnectionWhenPerUserLimitIsExceeded() {
        AtomicInteger completed = new AtomicInteger();
        CreditEventStreamService service = new CreditEventStreamService(
                2,
                60_000L,
                timeout -> new TrackingEmitter(timeout, completed)
        );

        service.subscribe(7L);
        service.subscribe(7L);
        service.subscribe(7L);

        assertThat(service.connectionCount(7L)).isEqualTo(2);
        assertThat(completed).hasValue(1);
    }

    @Test
    void removesConnectionThatFailsDuringBroadcast() {
        CreditEventStreamService service = new CreditEventStreamService(
                10,
                60_000L,
                timeout -> new FailingEmitter(timeout)
        );
        service.subscribe(7L);

        service.publishAccountChanged(7L);

        assertThat(service.connectionCount(7L)).isZero();
    }

    private static final class TrackingEmitter extends SseEmitter {
        private final AtomicInteger completed;

        private TrackingEmitter(Long timeout, AtomicInteger completed) {
            super(timeout);
            this.completed = completed;
        }

        @Override
        public synchronized void complete() {
            completed.incrementAndGet();
            super.complete();
        }
    }

    private static final class FailingEmitter extends SseEmitter {
        private int sendCount;

        private FailingEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCount++;
            if (sendCount > 1) {
                throw new IOException("connection closed");
            }
            super.send(builder);
        }
    }
}
