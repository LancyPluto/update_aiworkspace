package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MihomoNodeService {
    static final String AUTO_NODE_GROUP = "AUTO-NODE";
    static final String PROXY_GROUP = "PROXY";
    static final String SUBSCRIPTION_PROVIDER = "subscription";
    private static final String HEALTH_CHECK_URL = "https://www.gstatic.com/generate_204";
    private static final String SOURCE_TYPE_KEY = "outbound.proxy.sourceType";

    private final SystemSettingService systemSettingService;
    private final MihomoRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public MihomoNodeService(
            SystemSettingService systemSettingService,
            MihomoRuntimeProperties properties,
            ObjectMapper objectMapper
    ) {
        this(
                systemSettingService,
                properties,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        );
    }

    MihomoNodeService(
            SystemSettingService systemSettingService,
            MihomoRuntimeProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.systemSettingService = systemSettingService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public MihomoNodeListResponse list() {
        String sourceType = sourceType();
        if (!properties.isEnabled()) {
            return new MihomoNodeListResponse(
                    false, false, sourceType, "AUTO", "", "", List.of(), Instant.now(),
                    "Mihomo is not managed by this deployment"
            );
        }
        try {
            JsonNode proxyGroup = getJson("/proxies/" + encode(PROXY_GROUP));
            String selected = proxyGroup.path("now").asText("");
            boolean automatic = AUTO_NODE_GROUP.equals(selected);
            String active = selected;
            if (automatic) {
                active = getJson("/proxies/" + encode(AUTO_NODE_GROUP)).path("now").asText("");
            }

            List<MihomoNodeItem> nodes = ProxyConfigService.SOURCE_SUBSCRIPTION.equals(sourceType)
                    ? subscriptionNodes(active)
                    : manualNodes(proxyGroup, active);
            String message = nodes.isEmpty()
                    ? "No proxy nodes are available"
                    : nodes.stream().anyMatch(MihomoNodeItem::available)
                    ? "Proxy nodes are available"
                    : "All proxy nodes are unavailable";
            return new MihomoNodeListResponse(
                    true,
                    !nodes.isEmpty() && nodes.stream().anyMatch(MihomoNodeItem::available),
                    sourceType,
                    automatic ? "AUTO" : "MANUAL",
                    automatic ? "" : selected,
                    active,
                    nodes,
                    Instant.now(),
                    message
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable(sourceType, "Mihomo node request was interrupted");
        } catch (Exception exception) {
            return unavailable(sourceType, "Mihomo nodes are unavailable: " + conciseMessage(exception));
        }
    }

    public MihomoNodeActionResponse refreshSubscription() {
        requireSubscription();
        expectSuccess(request("/providers/proxies/" + encode(SUBSCRIPTION_PROVIDER))
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), "Subscription refresh failed");
        return new MihomoNodeActionResponse(true, "Subscription refresh started");
    }

    public MihomoNodeActionResponse testAll() {
        requireSubscription();
        expectSuccess(request("/providers/proxies/" + encode(SUBSCRIPTION_PROVIDER) + "/healthcheck")
                .GET().build(), "Node health check failed");
        return new MihomoNodeActionResponse(true, "Node health check completed");
    }

    public MihomoNodeTestResponse testNode(MihomoNodeTestRequest request) {
        requireSubscription();
        String nodeName = requiredNodeName(request == null ? null : request.nodeName());
        ensureSubscriptionNode(nodeName);
        String path = "/proxies/" + encode(nodeName) + "/delay?timeout=5000&url=" + encode(HEALTH_CHECK_URL);
        try {
            HttpResponse<String> response = send(this.request(path).GET().build());
            if (!isSuccess(response.statusCode())) {
                return new MihomoNodeTestResponse(
                        nodeName, false, 0, Instant.now(), "Node test returned HTTP " + response.statusCode()
                );
            }
            int delay = objectMapper.readTree(response.body()).path("delay").asInt(0);
            return new MihomoNodeTestResponse(
                    nodeName, delay > 0, delay, Instant.now(), delay > 0 ? "Node is available" : "Node did not report latency"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new MihomoNodeTestResponse(nodeName, false, 0, Instant.now(), "Node test was interrupted");
        } catch (Exception exception) {
            return new MihomoNodeTestResponse(
                    nodeName, false, 0, Instant.now(), "Node test failed: " + conciseMessage(exception)
            );
        }
    }

    public MihomoNodeActionResponse select(MihomoNodeSelectionRequest request) {
        requireSubscription();
        String mode = request == null || request.mode() == null
                ? ""
                : request.mode().trim().toUpperCase(Locale.ROOT);
        String target;
        if ("AUTO".equals(mode)) {
            target = AUTO_NODE_GROUP;
        } else if ("MANUAL".equals(mode)) {
            target = requiredNodeName(request.nodeName());
            ensureSubscriptionNode(target);
        } else {
            throw paramError("Node selection mode must be AUTO or MANUAL");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of("name", target));
            expectSuccess(request("/proxies/" + encode(PROXY_GROUP))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), "Node selection failed");
            return new MihomoNodeActionResponse(
                    true, "AUTO".equals(mode) ? "Automatic fastest-node selection enabled" : "Proxy node selected"
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw systemError("Node selection failed: " + conciseMessage(exception));
        }
    }

    private List<MihomoNodeItem> subscriptionNodes(String activeNode) throws Exception {
        JsonNode provider = getJson("/providers/proxies/" + encode(SUBSCRIPTION_PROVIDER));
        List<MihomoNodeItem> nodes = new ArrayList<>();
        for (JsonNode proxy : provider.path("proxies")) {
            String name = proxy.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            int latency = latestLatency(proxy.path("history"));
            boolean available = proxy.path("alive").asBoolean(latency > 0);
            nodes.add(new MihomoNodeItem(
                    name,
                    proxy.path("type").asText("UNKNOWN"),
                    available,
                    latency,
                    name.equals(activeNode)
            ));
        }
        return sortNodes(nodes);
    }

    private List<MihomoNodeItem> manualNodes(JsonNode group, String activeNode) {
        List<MihomoNodeItem> nodes = new ArrayList<>();
        for (JsonNode item : group.path("all")) {
            String name = item.asText("");
            if (name.isBlank() || AUTO_NODE_GROUP.equals(name) || "DIRECT".equals(name) || "REJECT".equals(name)) {
                continue;
            }
            nodes.add(new MihomoNodeItem(name, "MANUAL", true, 0, name.equals(activeNode)));
        }
        return sortNodes(nodes);
    }

    private List<MihomoNodeItem> sortNodes(List<MihomoNodeItem> nodes) {
        nodes.sort(Comparator
                .comparing(MihomoNodeItem::available).reversed()
                .thenComparingInt(node -> node.latencyMs() > 0 ? node.latencyMs() : Integer.MAX_VALUE)
                .thenComparing(MihomoNodeItem::name));
        return List.copyOf(nodes);
    }

    private int latestLatency(JsonNode history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            int delay = history.get(index).path("delay").asInt(0);
            if (delay > 0) {
                return delay;
            }
        }
        return 0;
    }

    private void ensureSubscriptionNode(String nodeName) {
        try {
            boolean exists = false;
            for (JsonNode proxy : getJson("/providers/proxies/" + encode(SUBSCRIPTION_PROVIDER)).path("proxies")) {
                if (nodeName.equals(proxy.path("name").asText(""))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw paramError("Selected node is not present in the current subscription");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw systemError("Subscription node lookup was interrupted");
        } catch (Exception exception) {
            throw systemError("Unable to read subscription nodes: " + conciseMessage(exception));
        }
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = send(request(path).GET().build());
        if (!isSuccess(response.statusCode())) {
            throw systemError("Mihomo controller returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private void expectSuccess(HttpRequest request, String message) {
        try {
            HttpResponse<String> response = send(request);
            if (!isSuccess(response.statusCode())) {
                throw systemError(message + ": HTTP " + response.statusCode());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw systemError(message + ": interrupted");
        } catch (Exception exception) {
            throw systemError(message + ": " + conciseMessage(exception));
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path) {
        if (!properties.isEnabled()) {
            throw systemError("Mihomo is not managed by this deployment");
        }
        String baseUrl = properties.getControllerUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(8));
        String secret = properties.getControllerSecret();
        if (secret != null && !secret.isBlank()) {
            builder.header("Authorization", "Bearer " + secret);
        }
        return builder;
    }

    private String sourceType() {
        return systemSettingService.settings().getOrDefault(
                SOURCE_TYPE_KEY, ProxyConfigService.SOURCE_SUBSCRIPTION
        ).trim().toUpperCase(Locale.ROOT);
    }

    private void requireSubscription() {
        if (!ProxyConfigService.SOURCE_SUBSCRIPTION.equals(sourceType())) {
            throw paramError("Node selection and subscription refresh require subscription source mode");
        }
    }

    private MihomoNodeListResponse unavailable(String sourceType, String message) {
        return new MihomoNodeListResponse(
                true, false, sourceType, "AUTO", "", "", List.of(), Instant.now(), message
        );
    }

    private String requiredNodeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 200 || name.contains("\r") || name.contains("\n")) {
            throw paramError("Please select a valid proxy node");
        }
        return name;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private BusinessException paramError(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    private BusinessException systemError(String message) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, message);
    }
}
