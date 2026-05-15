package com.aiminilab.aitoolmarket.commerce.service;

import com.aiminilab.aitoolmarket.commerce.dto.CreateChatSessionRequest;
import com.aiminilab.aitoolmarket.commerce.dto.CreatePaymentOrderRequest;
import com.aiminilab.aitoolmarket.commerce.dto.ReportModelIssueRequest;
import com.aiminilab.aitoolmarket.commerce.dto.SendModelMessageRequest;
import com.aiminilab.aitoolmarket.commerce.dto.UpsertNodeRequest;
import com.aiminilab.aitoolmarket.commerce.dto.UpsertPlanRequest;
import com.aiminilab.aitoolmarket.commerce.dto.UpsertPoolRequest;
import com.aiminilab.aitoolmarket.commerce.payment.PaymentCallbackAck;
import com.aiminilab.aitoolmarket.commerce.payment.PaymentProviderRegistry;
import com.aiminilab.aitoolmarket.commerce.payment.PaymentRequest;
import com.aiminilab.aitoolmarket.commerce.payment.PaymentVerification;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CommercePlatformService {

    private final JdbcTemplate jdbcTemplate;
    private final CreditService creditService;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final ModelGatewayService modelGatewayService;
    private final ModelNodeConcurrencyService modelNodeConcurrencyService;
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);

    public CommercePlatformService(JdbcTemplate jdbcTemplate, CreditService creditService,
                                   PaymentProviderRegistry paymentProviderRegistry,
                                   ModelGatewayService modelGatewayService,
                                   ModelNodeConcurrencyService modelNodeConcurrencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.creditService = creditService;
        this.paymentProviderRegistry = paymentProviderRegistry;
        this.modelGatewayService = modelGatewayService;
        this.modelNodeConcurrencyService = modelNodeConcurrencyService;
    }

    public List<Map<String, Object>> listPlans(String planType) {
        if (planType == null || planType.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT p.*,
                           GROUP_CONCAT(CASE WHEN e.id IS NULL THEN NULL WHEN e.pool_id IS NULL THEN 'All model pools' ELSE mp.pool_name END ORDER BY mp.sort_order, mp.id SEPARATOR ', ') AS entitled_pools,
                           MIN(e.hourly_limit) AS hourly_limit,
                           MIN(e.daily_limit) AS daily_limit
                    FROM subscription_plans p
                    LEFT JOIN plan_entitlements e ON e.plan_id = p.id
                    LEFT JOIN model_channel_pools mp ON mp.id = e.pool_id
                    WHERE p.status <> 'DELETED'
                    GROUP BY p.id
                    ORDER BY p.plan_type, p.price_cents, p.id
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT p.*,
                       GROUP_CONCAT(CASE WHEN e.id IS NULL THEN NULL WHEN e.pool_id IS NULL THEN 'All model pools' ELSE mp.pool_name END ORDER BY mp.sort_order, mp.id SEPARATOR ', ') AS entitled_pools,
                       MIN(e.hourly_limit) AS hourly_limit,
                       MIN(e.daily_limit) AS daily_limit
                FROM subscription_plans p
                LEFT JOIN plan_entitlements e ON e.plan_id = p.id
                LEFT JOIN model_channel_pools mp ON mp.id = e.pool_id
                WHERE p.plan_type = ? AND p.status <> 'DELETED'
                GROUP BY p.id
                ORDER BY p.price_cents, p.id
                """, planType);
    }

    public List<Map<String, Object>> listUserSubscriptions(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT s.*, p.plan_code, p.plan_name, p.plan_type, p.description,
                       GROUP_CONCAT(CASE WHEN e.id IS NULL THEN NULL WHEN e.pool_id IS NULL THEN 'All model pools' ELSE mp.pool_name END ORDER BY mp.sort_order, mp.id SEPARATOR ', ') AS entitled_pools,
                       MIN(e.hourly_limit) AS hourly_limit,
                       MIN(e.daily_limit) AS daily_limit
                FROM user_subscriptions s
                JOIN subscription_plans p ON p.id = s.plan_id
                LEFT JOIN plan_entitlements e ON e.plan_id = p.id
                LEFT JOIN model_channel_pools mp ON mp.id = e.pool_id
                WHERE s.user_id = ?
                GROUP BY s.id
                ORDER BY s.expires_at DESC, s.id DESC
                """, userId);
    }

    public List<Map<String, Object>> listPoolsForUser(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT p.*,
                       (SELECT COUNT(*) FROM model_channel_nodes n WHERE n.pool_id = p.id) AS node_count,
                       (SELECT COUNT(*) FROM model_channel_nodes n WHERE n.pool_id = p.id AND n.status = 'AVAILABLE') AS available_nodes,
                       (SELECT COUNT(*) FROM model_channel_nodes n WHERE n.pool_id = p.id AND n.status = 'BUSY') AS busy_nodes
                FROM model_channel_pools p
                WHERE p.status = 'ACTIVE'
                  AND EXISTS (
                    SELECT 1
                    FROM user_subscriptions s
                    JOIN plan_entitlements e ON e.plan_id = s.plan_id
                    WHERE s.user_id = ?
                      AND s.status = 'ACTIVE'
                      AND s.expires_at > CURRENT_TIMESTAMP
                      AND (e.pool_id = p.id OR e.pool_id IS NULL)
                  )
                ORDER BY p.sort_order, p.id
                """, userId);
    }

    public List<Map<String, Object>> listNodesForUser(Long userId, Long poolId) {
        ensurePoolEntitled(userId, poolId);
        return jdbcTemplate.queryForList("""
                SELECT id, pool_id, node_code, display_label, provider_protocol, model_name,
                       max_concurrency, current_concurrency, hourly_limit, daily_limit,
                       today_used, status, health_status, updated_at
                FROM model_channel_nodes
                WHERE pool_id = ?
                ORDER BY status, current_concurrency, id
                """, poolId);
    }

    @Transactional
    public Map<String, Object> createPaymentOrder(Long userId, CreatePaymentOrderRequest request) {
        Map<String, Object> product = findProduct(request.productType(), request.productId());
        int amountCents = ((Number) product.getOrDefault("price_cents", 0)).intValue();
        String subject = String.valueOf(product.getOrDefault("plan_name", request.productType()));
        String orderNo = "P" + System.currentTimeMillis() + Math.abs(UUID.randomUUID().hashCode());
        PaymentRequest payment = paymentProviderRegistry
                .require(request.channel())
                .createPayment(orderNo, amountCents, subject);
        Long orderId = insertAndReturnId("""
                INSERT INTO payment_orders
                  (order_no, user_id, product_type, product_id, channel, amount_cents, status, provider_trade_no)
                VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?)
                """, orderNo, userId, request.productType(), request.productId(), request.channel(), amountCents,
                payment.providerTradeNo());
        return jdbcTemplate.queryForMap("""
                SELECT *, ? AS payment_url
                FROM payment_orders
                WHERE id = ?
                """, payment.paymentUrl(), orderId);
    }

    @Transactional
    public Map<String, Object> mockPay(Long userId, Long orderId) {
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_orders WHERE id = ? AND user_id = ?", orderId, userId);
        if (!"MOCK".equalsIgnoreCase(String.valueOf(order.get("channel")))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "only mock orders can use mock-pay endpoint");
        }
        markOrderPaidOnce(order, "MOCK", String.valueOf(order.get("provider_trade_no")), "PAY_SUCCESS", "{}");
        return jdbcTemplate.queryForMap("SELECT * FROM payment_orders WHERE id = ?", orderId);
    }

    @Transactional
    public Map<String, Object> handlePaymentCallback(String channel, String rawPayload, Map<String, String> headers) {
        PaymentVerification verification = paymentProviderRegistry.require(channel).verifyCallback(rawPayload, headers);
        if (!verification.verified()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment callback verification failed");
        }
        if (verification.orderNo() == null || verification.orderNo().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment callback missing order number");
        }
        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT * FROM payment_orders WHERE order_no = ?", verification.orderNo());
        int amountCents = ((Number) order.get("amount_cents")).intValue();
        if (verification.amountCents() > 0 && verification.amountCents() != amountCents) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment amount mismatch");
        }
        if (!channel.equalsIgnoreCase(String.valueOf(order.get("channel")))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment channel mismatch");
        }
        markOrderPaidOnce(order, channel.toUpperCase(java.util.Locale.ROOT), verification.providerTradeNo(),
                value(verification.eventType(), "PAY_SUCCESS"), verification.rawPayload());
        return jdbcTemplate.queryForMap("SELECT * FROM payment_orders WHERE id = ?", order.get("id"));
    }

    public PaymentCallbackAck paymentCallbackAck(String channel, boolean success, String message) {
        return paymentProviderRegistry.require(channel).callbackAck(success, message);
    }

    public List<Map<String, Object>> listOrders(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM payment_orders
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT 50
                """, userId);
    }

    public void reportModelIssue(Long userId, Long sessionId, ReportModelIssueRequest request) {
        Map<String, Object> session = session(userId, sessionId);
        Long poolId = request.poolId() == null ? ((Number) session.get("pool_id")).longValue() : request.poolId();
        Long nodeId = request.nodeId() == null ? ((Number) session.get("node_id")).longValue() : request.nodeId();
        ensurePoolEntitled(userId, poolId);
        recordAccess(userId, poolId, nodeId, "FEEDBACK", request.message());
    }

    @Transactional
    public Map<String, Object> createChatSession(Long userId, CreateChatSessionRequest request) {
        Long poolId = request.poolId();
        if (poolId == null) {
            poolId = firstEntitledPool(userId);
        }
        Long nodeId = request.nodeId();
        if (nodeId == null) {
            nodeId = firstAvailableNode(poolId);
        }
        ensurePoolEntitled(userId, poolId);
        String title = request.title() == null || request.title().isBlank() ? "New chat" : request.title().trim();
        Long sessionId = insertAndReturnId("""
                INSERT INTO model_chat_sessions (user_id, title, pool_id, node_id)
                VALUES (?, ?, ?, ?)
                """, userId, title, poolId, nodeId);
        return session(userId, sessionId);
    }

    public List<Map<String, Object>> listChatSessions(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT s.*, p.pool_name, n.node_code
                FROM model_chat_sessions s
                LEFT JOIN model_channel_pools p ON p.id = s.pool_id
                LEFT JOIN model_channel_nodes n ON n.id = s.node_id
                WHERE s.user_id = ? AND s.status <> 'DELETED'
                ORDER BY s.updated_at DESC, s.id DESC
                LIMIT 100
                """, userId);
    }

    public List<Map<String, Object>> listMessages(Long userId, Long sessionId) {
        session(userId, sessionId);
        return jdbcTemplate.queryForList("""
                SELECT * FROM model_chat_messages
                WHERE user_id = ? AND session_id = ?
                ORDER BY id ASC
                """, userId, sessionId);
    }

    public Map<String, Object> sendMessage(Long userId, Long sessionId, SendModelMessageRequest request) {
        Map<String, Object> session = session(userId, sessionId);
        Long poolId = ((Number) session.get("pool_id")).longValue();
        Long nodeId = request.nodeId() == null
                ? ((Number) session.get("node_id")).longValue()
                : request.nodeId();
        ensurePoolEntitled(userId, poolId);
        ensureEntitlementUsageAvailable(userId, poolId);
        insertMessage(sessionId, userId, "USER", request.content(), poolId, nodeId);

        RuntimeException lastException = null;
        int attemptNo = 0;
        for (Long candidateNodeId : fallbackCandidates(poolId, nodeId)) {
            attemptNo++;
            LocalDateTime started = LocalDateTime.now();
            ModelNodeConcurrencyService.Reservation reservation = null;
            try {
                reservation = modelNodeConcurrencyService.reserve(candidateNodeId, poolId);
                recordAccess(userId, poolId, candidateNodeId, "CHAT_REQUEST", "accepted");
                ModelGatewayResult gatewayResult = modelGatewayService.chat(sessionId, poolId, candidateNodeId, request.content());
                Long assistantId = insertMessage(sessionId, userId, "ASSISTANT", gatewayResult.content(), poolId, candidateNodeId);
                jdbcTemplate.update("""
                        UPDATE model_chat_sessions
                        SET node_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, candidateNodeId, sessionId);
                markNodeHealthy(candidateNodeId);
                insertModelCallLog(userId, sessionId, poolId, candidateNodeId, "SUCCESS", null, null,
                        null, started, LocalDateTime.now(), true, attemptNo, fallbackFrom(nodeId, candidateNodeId),
                        gatewayResult);
                return jdbcTemplate.queryForMap("SELECT * FROM model_chat_messages WHERE id = ?", assistantId);
            } catch (BusinessException exception) {
                lastException = exception;
                insertModelCallLog(userId, sessionId, poolId, candidateNodeId, "FAILED", modelErrorCode(exception),
                        exception.getMessage(), errorCategory(exception), started, LocalDateTime.now(), true,
                        attemptNo, fallbackFrom(nodeId, candidateNodeId), null);
                markNodeUnhealthy(candidateNodeId, exception.getMessage());
                if (exception instanceof ModelGatewayException gatewayException && !gatewayException.isFallbackable()) {
                    break;
                }
            } catch (RuntimeException exception) {
                lastException = exception;
                insertModelCallLog(userId, sessionId, poolId, candidateNodeId, "FAILED", "MODEL_CALL_FAILED",
                        exception.getMessage(), "SYSTEM", started, LocalDateTime.now(), true,
                        attemptNo, fallbackFrom(nodeId, candidateNodeId), null);
                markNodeUnhealthy(candidateNodeId, exception.getMessage());
            } finally {
                modelNodeConcurrencyService.release(reservation);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "No available model node");
    }

    public SseEmitter streamMessage(Long userId, Long sessionId, SendModelMessageRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        new Thread(() -> {
            try {
                Map<String, Object> response = sendMessage(userId, sessionId, request);
                String content = String.valueOf(response.get("content_text"));
                emitter.send(SseEmitter.event().name("message.started").data(Map.of("sessionId", sessionId)));
                for (String chunk : chunks(content, 32)) {
                    emitter.send(SseEmitter.event().name("message.delta").data(Map.of("text", chunk)));
                    Thread.sleep(35L);
                }
                emitter.send(SseEmitter.event().name("message.completed").data(response));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", exception.getMessage())));
                } catch (IOException ignored) {
                    // ignored
                }
                emitter.completeWithError(exception);
            }
        }, "model-chat-stream-" + sessionId).start();
        return emitter;
    }

    public List<Map<String, Object>> adminListPlans() {
        return jdbcTemplate.queryForList("SELECT * FROM subscription_plans ORDER BY id DESC");
    }

    public Map<String, Object> adminUpsertPlan(UpsertPlanRequest request) {
        if (request.planCode() == null || request.planCode().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "planCode is required");
        }
        jdbcTemplate.update("""
                INSERT INTO subscription_plans (plan_code, plan_name, plan_type, duration_days, price_cents, credit_amount, status, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name), plan_type = VALUES(plan_type),
                    duration_days = VALUES(duration_days), price_cents = VALUES(price_cents),
                    credit_amount = VALUES(credit_amount), status = VALUES(status), description = VALUES(description)
                """,
                request.planCode(), value(request.planName(), request.planCode()),
                value(request.planType(), "MODEL_CHANNEL"), defaultInt(request.durationDays(), 30),
                defaultInt(request.priceCents(), 0), defaultInt(request.creditAmount(), 0),
                value(request.status(), "ACTIVE"), request.description());
        return jdbcTemplate.queryForMap("SELECT * FROM subscription_plans WHERE plan_code = ?", request.planCode());
    }

    public List<Map<String, Object>> adminListPools() {
        return jdbcTemplate.queryForList("""
                SELECT p.*,
                       (SELECT COUNT(*) FROM model_channel_nodes n WHERE n.pool_id = p.id) AS node_count,
                       (SELECT COUNT(*) FROM model_channel_nodes n WHERE n.pool_id = p.id AND n.status = 'AVAILABLE') AS available_nodes
                FROM model_channel_pools p
                ORDER BY p.sort_order, p.id
                """);
    }

    public Map<String, Object> adminUpsertPool(UpsertPoolRequest request) {
        if (request.poolCode() == null || request.poolCode().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "poolCode is required");
        }
        jdbcTemplate.update("""
                INSERT INTO model_channel_pools (pool_code, pool_name, provider, model_name, spec_label, description, status, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE pool_name = VALUES(pool_name), provider = VALUES(provider),
                    model_name = VALUES(model_name), spec_label = VALUES(spec_label),
                    description = VALUES(description), status = VALUES(status), sort_order = VALUES(sort_order)
                """,
                request.poolCode(), value(request.poolName(), request.poolCode()), value(request.provider(), "openai"),
                value(request.modelName(), request.poolCode()), value(request.specLabel(), "PRO"),
                request.description(), value(request.status(), "ACTIVE"), defaultInt(request.sortOrder(), 0));
        return jdbcTemplate.queryForMap("SELECT * FROM model_channel_pools WHERE pool_code = ?", request.poolCode());
    }

    public List<Map<String, Object>> adminListNodes(Long poolId) {
        List<Map<String, Object>> rows;
        if (poolId == null) {
            rows = jdbcTemplate.queryForList("""
                    SELECT id, pool_id, node_code, display_label, provider_protocol, base_url,
                           CASE WHEN api_key IS NULL OR api_key = '' THEN NULL ELSE '***' END AS api_key_masked,
                           model_name, timeout_seconds, max_concurrency, current_concurrency, hourly_limit, daily_limit,
                           today_used, status, health_status, last_error, note, created_at, updated_at
                    FROM model_channel_nodes
                    ORDER BY id DESC
                    """);
            return decorateNodeRows(rows);
        }
        rows = jdbcTemplate.queryForList("""
                SELECT id, pool_id, node_code, display_label, provider_protocol, base_url,
                       CASE WHEN api_key IS NULL OR api_key = '' THEN NULL ELSE '***' END AS api_key_masked,
                       model_name, timeout_seconds, max_concurrency, current_concurrency, hourly_limit, daily_limit,
                       today_used, status, health_status, last_error, note, created_at, updated_at
                FROM model_channel_nodes
                WHERE pool_id = ?
                ORDER BY id DESC
                """, poolId);
        return decorateNodeRows(rows);
    }

    public Map<String, Object> adminUpsertNode(UpsertNodeRequest request) {
        if (request.nodeCode() == null || request.nodeCode().isBlank() || request.poolId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "poolId and nodeCode are required");
        }
        jdbcTemplate.update("""
                INSERT INTO model_channel_nodes
                  (pool_id, node_code, display_label, provider_protocol, base_url, api_key, model_name,
                   timeout_seconds, max_concurrency, hourly_limit, daily_limit, status, health_status, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE pool_id = VALUES(pool_id), display_label = VALUES(display_label),
                   provider_protocol = VALUES(provider_protocol), base_url = VALUES(base_url),
                   api_key = CASE
                       WHEN VALUES(api_key) IS NULL OR VALUES(api_key) = '' THEN api_key
                       ELSE VALUES(api_key)
                   END,
                   model_name = VALUES(model_name),
                   timeout_seconds = VALUES(timeout_seconds),
                   max_concurrency = VALUES(max_concurrency), hourly_limit = VALUES(hourly_limit),
                   daily_limit = VALUES(daily_limit), status = VALUES(status),
                   health_status = VALUES(health_status), note = VALUES(note)
                """,
                request.poolId(), request.nodeCode(), request.displayLabel(), value(request.providerProtocol(), "openai_compatible"),
                request.baseUrl(), request.apiKey(), request.modelName(), defaultInt(request.timeoutSeconds(), 90),
                defaultInt(request.maxConcurrency(), 1),
                request.hourlyLimit(), request.dailyLimit(), value(request.status(), "AVAILABLE"),
                value(request.healthStatus(), "UNKNOWN"), request.note());
        return jdbcTemplate.queryForMap("SELECT * FROM model_channel_nodes WHERE node_code = ?", request.nodeCode());
    }

    public Map<String, Object> adminUpdateNodeStatus(Long nodeId, String status) {
        String normalized = value(status, "AVAILABLE").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("AVAILABLE", "DISABLED", "BUSY").contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported node status");
        }
        int updated = jdbcTemplate.update("""
                UPDATE model_channel_nodes
                SET status = ?,
                    current_concurrency = CASE WHEN ? = 'DISABLED' THEN 0 ELSE current_concurrency END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, normalized, normalized, nodeId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "node not found");
        }
        return decorateNodeRow(jdbcTemplate.queryForMap("""
                SELECT id, pool_id, node_code, display_label, provider_protocol, base_url,
                       CASE WHEN api_key IS NULL OR api_key = '' THEN NULL ELSE '***' END AS api_key_masked,
                       model_name, timeout_seconds, max_concurrency, current_concurrency, hourly_limit, daily_limit,
                       today_used, status, health_status, last_error, note, created_at, updated_at
                FROM model_channel_nodes
                WHERE id = ?
                """, nodeId));
    }

    public Map<String, Object> adminHealthCheckNode(Long nodeId) {
        return healthCheckNode(nodeId);
    }

    public Map<String, Object> adminHealthCheckNodes(Long poolId) {
        List<Long> nodeIds;
        if (poolId == null) {
            nodeIds = jdbcTemplate.queryForList("""
                    SELECT id FROM model_channel_nodes
                    WHERE status <> 'DISABLED' OR health_status <> 'HEALTHY'
                    ORDER BY id ASC
                    """, Long.class);
        } else {
            nodeIds = jdbcTemplate.queryForList("""
                    SELECT id FROM model_channel_nodes
                    WHERE pool_id = ?
                      AND (status <> 'DISABLED' OR health_status <> 'HEALTHY')
                    ORDER BY id ASC
                    """, Long.class, poolId);
        }
        return runHealthChecks(nodeIds, "MANUAL");
    }

    public Map<String, Object> adminCapacityStats() {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total_nodes,
                       SUM(CASE WHEN status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_nodes,
                       SUM(CASE WHEN status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_nodes,
                       SUM(CASE WHEN health_status = 'HEALTHY' THEN 1 ELSE 0 END) AS healthy_nodes,
                       SUM(CASE WHEN health_status = 'UNHEALTHY' THEN 1 ELSE 0 END) AS unhealthy_nodes,
                       COALESCE(SUM(max_concurrency), 0) AS max_concurrency,
                       COALESCE(SUM(current_concurrency), 0) AS current_concurrency,
                       COALESCE(SUM(today_used), 0) AS today_used,
                       COALESCE(SUM(daily_limit), 0) AS daily_limit
                FROM model_channel_nodes
                """);
        List<Map<String, Object>> pools = jdbcTemplate.queryForList("""
                SELECT p.id AS pool_id, p.pool_code, p.pool_name,
                       COUNT(n.id) AS total_nodes,
                       SUM(CASE WHEN n.status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_nodes,
                       SUM(CASE WHEN n.status = 'DISABLED' THEN 1 ELSE 0 END) AS disabled_nodes,
                       SUM(CASE WHEN n.health_status = 'UNHEALTHY' THEN 1 ELSE 0 END) AS unhealthy_nodes,
                       COALESCE(SUM(n.max_concurrency), 0) AS max_concurrency,
                       COALESCE(SUM(n.current_concurrency), 0) AS current_concurrency,
                       COALESCE(SUM(n.today_used), 0) AS today_used,
                       COALESCE(SUM(n.daily_limit), 0) AS daily_limit
                FROM model_channel_pools p
                LEFT JOIN model_channel_nodes n ON n.pool_id = p.id
                GROUP BY p.id, p.pool_code, p.pool_name
                ORDER BY p.sort_order, p.id
                """);
        Map<String, Object> result = new java.util.LinkedHashMap<>(totals);
        result.put("concurrency_usage_percent", percent(totals.get("current_concurrency"), totals.get("max_concurrency")));
        result.put("daily_usage_percent", percent(totals.get("today_used"), totals.get("daily_limit")));
        result.put("pools", pools);
        return result;
    }

    @Scheduled(fixedDelayString = "${app.model-channel.health-check-interval-ms:300000}",
            initialDelayString = "${app.model-channel.health-check-initial-delay-ms:60000}")
    public void scheduledModelNodeHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Long> nodeIds = jdbcTemplate.queryForList("""
                    SELECT id FROM model_channel_nodes
                    WHERE status = 'AVAILABLE'
                    ORDER BY id ASC
                    LIMIT 50
                    """, Long.class);
            runHealthChecks(nodeIds, "SCHEDULED");
        } finally {
            healthCheckRunning.set(false);
        }
    }

    public List<Map<String, Object>> adminListOrders() {
        return jdbcTemplate.queryForList("""
                SELECT o.*, u.username
                FROM payment_orders o
                LEFT JOIN users u ON u.id = o.user_id
                ORDER BY o.id DESC
                LIMIT 100
                """);
    }

    public List<Map<String, Object>> adminListModelCallLogs() {
        return jdbcTemplate.queryForList("""
                SELECT l.*, p.pool_name, n.node_code
                FROM model_call_logs l
                LEFT JOIN model_channel_pools p ON p.id = l.pool_id
                LEFT JOIN model_channel_nodes n ON n.id = l.node_id
                ORDER BY l.id DESC
                LIMIT 100
                """);
    }

    public List<Map<String, Object>> adminListModelFeedback() {
        return jdbcTemplate.queryForList("""
                SELECT a.*, u.username, p.pool_name, n.node_code
                FROM model_access_logs a
                LEFT JOIN users u ON u.id = a.user_id
                LEFT JOIN model_channel_pools p ON p.id = a.pool_id
                LEFT JOIN model_channel_nodes n ON n.id = a.node_id
                WHERE a.event_type = 'FEEDBACK'
                ORDER BY a.id DESC
                LIMIT 100
                """);
    }

    private Map<String, Object> findProduct(String productType, Long productId) {
        if (!"PLAN".equalsIgnoreCase(productType)
                && !"COMPUTE_CREDIT".equalsIgnoreCase(productType)
                && !"MODEL_CHANNEL".equalsIgnoreCase(productType)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported product type");
        }
        return jdbcTemplate.queryForMap("SELECT * FROM subscription_plans WHERE id = ?", productId);
    }

    private void grantProduct(Long orderId, Long userId, String productType, Long productId) {
        Map<String, Object> plan = jdbcTemplate.queryForMap("SELECT * FROM subscription_plans WHERE id = ?", productId);
        String planType = String.valueOf(plan.get("plan_type"));
        if ("COMPUTE_CREDIT".equals(planType)) {
            int credits = ((Number) plan.get("credit_amount")).intValue();
            if (credits > 0) {
                creditService.manualAdd(userId, credits, "Payment order " + orderId, null);
            }
            return;
        }
        int durationDays = ((Number) plan.get("duration_days")).intValue();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO user_subscriptions (user_id, plan_id, status, starts_at, expires_at, source_order_id)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?)
                """, userId, productId, Timestamp.valueOf(now), Timestamp.valueOf(now.plusDays(Math.max(1, durationDays))), orderId);
    }

    private void markOrderPaidOnce(Map<String, Object> order, String provider, String providerTradeNo,
                                   String eventType, String rawPayload) {
        Long orderId = ((Number) order.get("id")).longValue();
        int updated = jdbcTemplate.update("""
                UPDATE payment_orders
                SET status = 'PAID',
                    provider_trade_no = COALESCE(?, provider_trade_no),
                    paid_at = COALESCE(paid_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status <> 'PAID'
                """, blankToNull(providerTradeNo), orderId);
        if (updated != 1) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO payment_fulfillments
                      (order_id, product_type, product_id, user_id, status)
                    VALUES (?, ?, ?, ?, 'FULFILLED')
                    """, orderId, String.valueOf(order.get("product_type")),
                    ((Number) order.get("product_id")).longValue(),
                    ((Number) order.get("user_id")).longValue());
        } catch (DuplicateKeyException ignored) {
            return;
        }
        grantProduct(orderId, ((Number) order.get("user_id")).longValue(), String.valueOf(order.get("product_type")),
                ((Number) order.get("product_id")).longValue());
        jdbcTemplate.update("""
                INSERT INTO payment_transactions
                  (order_id, provider, provider_trade_no, event_type, raw_payload_json, verified)
                VALUES (?, ?, ?, ?, ?, 1)
                """, orderId, provider, blankToNull(providerTradeNo), eventType, value(rawPayload, "{}"));
    }

    private void ensurePoolEntitled(Long userId, Long poolId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM user_subscriptions s
                JOIN plan_entitlements e ON e.plan_id = s.plan_id
                WHERE s.user_id = ?
                  AND s.status = 'ACTIVE'
                  AND s.expires_at > CURRENT_TIMESTAMP
                  AND (e.pool_id = ? OR e.pool_id IS NULL)
                """, Integer.class, userId, poolId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_REQUIRED, "Model pool subscription is required");
        }
    }

    private void ensureEntitlementUsageAvailable(Long userId, Long poolId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.hourly_limit, e.daily_limit
                FROM user_subscriptions s
                JOIN plan_entitlements e ON e.plan_id = s.plan_id
                WHERE s.user_id = ?
                  AND s.status = 'ACTIVE'
                  AND s.expires_at > CURRENT_TIMESTAMP
                  AND (e.pool_id = ? OR e.pool_id IS NULL)
                ORDER BY CASE WHEN e.pool_id = ? THEN 0 ELSE 1 END
                LIMIT 1
                """, userId, poolId, poolId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_REQUIRED, "Model pool subscription is required");
        }
        Map<String, Object> entitlement = rows.get(0);
        Integer hourlyLimit = nullableInt(entitlement.get("hourly_limit"));
        Integer dailyLimit = nullableInt(entitlement.get("daily_limit"));
        if (hourlyLimit != null) {
            Integer hourlyUsed = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM model_access_logs
                    WHERE user_id = ? AND pool_id = ? AND event_type = 'CHAT_REQUEST'
                      AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 HOUR)
                    """, Integer.class, userId, poolId);
            if (hourlyUsed != null && hourlyUsed >= hourlyLimit) {
                throw new BusinessException(ErrorCode.AGENT_RATE_LIMITED, "Hourly model channel quota exceeded");
            }
        }
        if (dailyLimit != null) {
            Integer dailyUsed = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM model_access_logs
                    WHERE user_id = ? AND pool_id = ? AND event_type = 'CHAT_REQUEST'
                      AND created_at >= CURRENT_DATE
                    """, Integer.class, userId, poolId);
            if (dailyUsed != null && dailyUsed >= dailyLimit) {
                throw new BusinessException(ErrorCode.AGENT_RATE_LIMITED, "Daily model channel quota exceeded");
            }
        }
    }

    private Long firstEntitledPool(Long userId) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT p.id
                FROM model_channel_pools p
                WHERE p.status = 'ACTIVE'
                  AND EXISTS (
                    SELECT 1 FROM user_subscriptions s
                    JOIN plan_entitlements e ON e.plan_id = s.plan_id
                    WHERE s.user_id = ? AND s.status = 'ACTIVE' AND s.expires_at > CURRENT_TIMESTAMP
                      AND (e.pool_id = p.id OR e.pool_id IS NULL)
                  )
                ORDER BY p.sort_order, p.id
                LIMIT 1
                """, Long.class, userId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_REQUIRED, "No model pool subscription available");
        }
        return ids.get(0);
    }

    private Long firstAvailableNode(Long poolId) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT id FROM model_channel_nodes
                WHERE pool_id = ?
                  AND status = 'AVAILABLE'
                  AND current_concurrency < max_concurrency
                  AND (daily_limit IS NULL OR today_used < daily_limit)
                ORDER BY current_concurrency ASC, id ASC
                LIMIT 1
                """, Long.class, poolId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "No available model node");
        }
        return ids.get(0);
    }

    private List<Long> fallbackCandidates(Long poolId, Long preferredNodeId) {
        List<Long> candidates = new java.util.ArrayList<>();
        candidates.add(preferredNodeId);
        List<Long> fallbackIds = jdbcTemplate.queryForList("""
                SELECT id FROM model_channel_nodes
                WHERE pool_id = ?
                  AND id <> ?
                  AND status = 'AVAILABLE'
                  AND current_concurrency < max_concurrency
                  AND (daily_limit IS NULL OR today_used < daily_limit)
                ORDER BY CASE WHEN health_status = 'HEALTHY' THEN 0 ELSE 1 END,
                         current_concurrency ASC, id ASC
                LIMIT 3
                """, Long.class, poolId, preferredNodeId);
        candidates.addAll(fallbackIds);
        return candidates;
    }

    private void markNodeHealthy(Long nodeId) {
        jdbcTemplate.update("""
                UPDATE model_channel_nodes
                SET status = CASE WHEN status = 'DISABLED' THEN status ELSE 'AVAILABLE' END,
                    health_status = 'HEALTHY',
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, nodeId);
    }

    private void markNodeUnhealthy(Long nodeId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE model_channel_nodes
                SET status = 'DISABLED',
                    current_concurrency = 0,
                    health_status = 'UNHEALTHY',
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, abbreviate(errorMessage, 500), nodeId);
    }

    private Map<String, Object> healthCheckNode(Long nodeId) {
        try {
            modelGatewayService.healthCheck(nodeId);
            markNodeHealthy(nodeId);
        } catch (RuntimeException exception) {
            markNodeUnhealthy(nodeId, exception.getMessage());
        }
        return adminNode(nodeId);
    }

    private Map<String, Object> runHealthChecks(List<Long> nodeIds, String trigger) {
        int healthy = 0;
        int unhealthy = 0;
        java.util.ArrayList<Map<String, Object>> checkedNodes = new java.util.ArrayList<>();
        for (Long nodeId : nodeIds) {
            Map<String, Object> node = healthCheckNode(nodeId);
            checkedNodes.add(node);
            if ("HEALTHY".equals(node.get("health_status"))) {
                healthy++;
            } else if ("UNHEALTHY".equals(node.get("health_status"))) {
                unhealthy++;
            }
        }
        return Map.of(
                "trigger", trigger,
                "checked", nodeIds.size(),
                "healthy", healthy,
                "unhealthy", unhealthy,
                "auto_offline", unhealthy,
                "nodes", checkedNodes
        );
    }

    private Map<String, Object> adminNode(Long nodeId) {
        return decorateNodeRow(jdbcTemplate.queryForMap("""
                SELECT id, pool_id, node_code, display_label, provider_protocol, base_url,
                       CASE WHEN api_key IS NULL OR api_key = '' THEN NULL ELSE '***' END AS api_key_masked,
                       model_name, timeout_seconds, max_concurrency, current_concurrency, hourly_limit, daily_limit,
                       today_used, status, health_status, last_error, note, created_at, updated_at
                FROM model_channel_nodes
                WHERE id = ?
                """, nodeId));
    }

    private List<Map<String, Object>> decorateNodeRows(List<Map<String, Object>> rows) {
        java.util.ArrayList<Map<String, Object>> decorated = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            decorated.add(decorateNodeRow(row));
        }
        return decorated;
    }

    private Map<String, Object> decorateNodeRow(Map<String, Object> row) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(row);
        int maxConcurrency = Math.max(0, defaultInt(nullableInt(row.get("max_concurrency")), 0));
        int currentConcurrency = Math.max(0, defaultInt(nullableInt(row.get("current_concurrency")), 0));
        Integer dailyLimit = nullableInt(row.get("daily_limit"));
        int todayUsed = Math.max(0, defaultInt(nullableInt(row.get("today_used")), 0));
        int concurrencyPercent = maxConcurrency <= 0 ? 0 : Math.min(100, currentConcurrency * 100 / maxConcurrency);
        Integer dailyPercent = dailyLimit == null || dailyLimit <= 0 ? null : Math.min(100, todayUsed * 100 / dailyLimit);
        String warningLevel = "OK";
        String warningReason = "capacity normal";
        if ("DISABLED".equals(row.get("status"))) {
            warningLevel = "CRITICAL";
            warningReason = "node disabled";
        } else if ("UNHEALTHY".equals(row.get("health_status"))) {
            warningLevel = "CRITICAL";
            warningReason = "health check failed";
        } else if (concurrencyPercent >= 90 || (dailyPercent != null && dailyPercent >= 90)) {
            warningLevel = "CRITICAL";
            warningReason = "capacity usage >= 90%";
        } else if (concurrencyPercent >= 70 || (dailyPercent != null && dailyPercent >= 70)) {
            warningLevel = "WARN";
            warningReason = "capacity usage >= 70%";
        }
        result.put("concurrency_usage_percent", concurrencyPercent);
        result.put("daily_usage_percent", dailyPercent);
        result.put("warning_level", warningLevel);
        result.put("warning_reason", warningReason);
        return result;
    }

    private void recordAccess(Long userId, Long poolId, Long nodeId, String eventType, String message) {
        jdbcTemplate.update("""
                INSERT INTO model_access_logs (user_id, pool_id, node_id, event_type, event_message)
                VALUES (?, ?, ?, ?, ?)
                """, userId, poolId, nodeId, eventType, message);
    }

    private Map<String, Object> session(Long userId, Long sessionId) {
        return jdbcTemplate.queryForMap("SELECT * FROM model_chat_sessions WHERE id = ? AND user_id = ?", sessionId, userId);
    }

    private Long insertMessage(Long sessionId, Long userId, String role, String content, Long poolId, Long nodeId) {
        return insertAndReturnId("""
                INSERT INTO model_chat_messages (session_id, user_id, role, content_text, pool_id, node_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, userId, role, content, poolId, nodeId);
    }

    private void insertModelCallLog(Long userId, Long sessionId, Long poolId, Long nodeId, String status,
                                    String errorCode, String errorMessage,
                                    LocalDateTime started, LocalDateTime finished, boolean streaming) {
        insertModelCallLog(userId, sessionId, poolId, nodeId, status, errorCode, errorMessage, null,
                started, finished, streaming, null, null, null);
    }

    private void insertModelCallLog(Long userId, Long sessionId, Long poolId, Long nodeId, String status,
                                    String errorCode, String errorMessage, String errorCategory,
                                    LocalDateTime started, LocalDateTime finished, boolean streaming,
                                    Integer attemptNo, Long fallbackFromNodeId,
                                    ModelGatewayResult gatewayResult) {
        jdbcTemplate.update("""
                INSERT INTO model_call_logs
                  (trace_id, user_id, chat_session_id, pool_id, node_id, provider, model_name,
                   request_type, streaming, status, error_code, error_message, error_category, latency_ms,
                   prompt_tokens, completion_tokens, total_tokens, estimated_cost_cents,
                   attempt_no, fallback_from_node_id, response_metadata_json, started_at, finished_at)
                SELECT ?, ?, ?, ?, ?, p.provider, COALESCE(n.model_name, p.model_name),
                       'CHAT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM model_channel_pools p
                LEFT JOIN model_channel_nodes n ON n.id = ?
                WHERE p.id = ?
                """,
                UUID.randomUUID().toString(), userId, sessionId, poolId, nodeId,
                streaming ? 1 : 0, status, errorCode, errorMessage, errorCategory,
                (int) java.time.Duration.between(started, finished).toMillis(),
                gatewayResult == null ? null : gatewayResult.promptTokens(),
                gatewayResult == null ? null : gatewayResult.completionTokens(),
                gatewayResult == null ? null : gatewayResult.totalTokens(),
                gatewayResult == null ? null : gatewayResult.estimatedCostCents(),
                attemptNo, fallbackFromNodeId,
                gatewayResult == null ? null : gatewayResult.responseMetadataJson(),
                Timestamp.valueOf(started), Timestamp.valueOf(finished), nodeId, poolId);
    }

    private static String modelErrorCode(BusinessException exception) {
        if (exception instanceof ModelGatewayException gatewayException) {
            return "MODEL_" + gatewayException.getErrorCategory();
        }
        return exception.getErrorCode().name();
    }

    private static String errorCategory(BusinessException exception) {
        if (exception instanceof ModelGatewayException gatewayException) {
            return gatewayException.getErrorCategory();
        }
        return "BUSINESS";
    }

    private static Long fallbackFrom(Long preferredNodeId, Long candidateNodeId) {
        if (preferredNodeId == null || preferredNodeId.equals(candidateNodeId)) {
            return null;
        }
        return preferredNodeId;
    }

    private String nodeCode(Long nodeId) {
        return jdbcTemplate.queryForObject("SELECT node_code FROM model_channel_nodes WHERE id = ?", String.class, nodeId);
    }

    private Long insertAndReturnId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("insert did not return generated key");
        }
        return key.longValue();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static int percent(Object numerator, Object denominator) {
        int top = Math.max(0, defaultInt(nullableInt(numerator), 0));
        int bottom = Math.max(0, defaultInt(nullableInt(denominator), 0));
        return bottom <= 0 ? 0 : Math.min(100, top * 100 / bottom);
    }

    private static List<String> chunks(String value, int size) {
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < value.length(); index += size) {
            chunks.add(value.substring(index, Math.min(value.length(), index + size)));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
