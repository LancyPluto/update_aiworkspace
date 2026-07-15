package com.aiminilab.aitoolmarket.credit.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

@Service
public class CreditEventStreamService {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final int maxConnectionsPerUser;
    private final long timeoutMillis;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public CreditEventStreamService(
            @Value("${app.credit-realtime.max-connections-per-user:10}") int maxConnectionsPerUser,
            @Value("${app.credit-realtime.timeout-ms:1800000}") long timeoutMillis) {
        this(maxConnectionsPerUser, timeoutMillis, SseEmitter::new);
    }

    public CreditEventStreamService(int maxConnectionsPerUser,
                                    long timeoutMillis,
                                    LongFunction<SseEmitter> emitterFactory) {
        this.maxConnectionsPerUser = Math.max(1, maxConnectionsPerUser);
        this.timeoutMillis = Math.max(1L, timeoutMillis);
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        CopyOnWriteArrayList<SseEmitter> userConnections = connections.computeIfAbsent(
                userId,
                ignored -> new CopyOnWriteArrayList<>()
        );
        userConnections.add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ignored -> remove(userId, emitter));

        while (userConnections.size() > maxConnectionsPerUser) {
            SseEmitter oldest = userConnections.remove(0);
            oldest.complete();
        }

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException | IllegalStateException exception) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void publishAccountChanged(Long userId) {
        CopyOnWriteArrayList<SseEmitter> userConnections = connections.get(userId);
        if (userConnections == null) {
            return;
        }
        CreditEvent event = CreditEvent.accountChanged();
        for (SseEmitter emitter : userConnections) {
            try {
                emitter.send(SseEmitter.event().name("credit-account-changed").data(event));
            } catch (IOException | IllegalStateException exception) {
                remove(userId, emitter);
            }
        }
    }

    public int connectionCount(Long userId) {
        CopyOnWriteArrayList<SseEmitter> userConnections = connections.get(userId);
        return userConnections == null ? 0 : userConnections.size();
    }

    private void remove(Long userId, SseEmitter emitter) {
        connections.computeIfPresent(userId, (ignored, userConnections) -> {
            userConnections.remove(emitter);
            return userConnections.isEmpty() ? null : userConnections;
        });
    }
}
