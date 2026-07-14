# Provider Connectivity Probe Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Separate vendor-account credential health from per-model capability health through a code-only connectivity probe registry, with /models as the account-first strategy and protocol-specific model probes.

**Architecture:** Spring discovers ordered ProviderConnectivityProbe beans through ConnectivityProbeRegistry. Account probes never receive a model name and prefer vendor-aware /models endpoints; model probes receive resolved execution credentials and perform protocol-specific dry-runs. Providers not migrated in this release keep the current service behavior as fallback, and no database schema change is introduced.

**Tech Stack:** Java 17, Spring Boot 3.3, Java HttpClient, Jackson, JUnit 5, AssertJ, Mockito, Spring MockMvc, JDK HttpServer, Maven.

---

## File Structure

- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/AccountProbeContext.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelProbeContext.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ConnectivityProbeResult.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ProviderConnectivityProbe.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ConnectivityProbeRegistry.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsEndpointResolver.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/AccountProbeErrorClassifier.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsAccountConnectivityProbe.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/OpenAiImagesModelConnectivityProbe.java.
- Create backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/DashScopeHappyHorseModelConnectivityProbe.java.
- Create focused tests under backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/.
- Modify ModelVendorAccountServiceImpl.java and AgentModelConfigServiceImpl.java.
- Modify ModelVendorAccountDiscoveryApiTest.java and AdminAgentApiTest.java.

## Task 1: Probe Contracts and Ordered Registry

**Files:**
- Create the five contract and registry files listed above.
- Test: backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/ConnectivityProbeRegistryTest.java

- [ ] **Step 1: Write the failing registry test**

Create two in-test strategies. The first returns Optional.empty() with order 10; the second returns success with order 20. Pass them to the registry in reverse order and assert the order-10 strategy executes first, is skipped, and the order-20 result is returned. Add equivalent model-context and no-match tests.

~~~java
ProviderConnectivityProbe selected = new ProviderConnectivityProbe() {
    @Override public int order() { return 20; }
    @Override public Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        return Optional.of(ConnectivityProbeResult.ok("ACCOUNT_MODELS", 200, 12, "ok"));
    }
};
assertThat(new ConnectivityProbeRegistry(List.of(selected, skipped)).probeAccount(context))
        .get()
        .extracting(ConnectivityProbeResult::success)
        .isEqualTo(true);
~~~

- [ ] **Step 2: Run the test and verify RED**

Run: cd backend; mvn -Dtest=ConnectivityProbeRegistryTest test

Expected: compilation failure because the connectivity package does not exist.

- [ ] **Step 3: Implement immutable contexts and result**

~~~java
public record AccountProbeContext(
        Long accountId, String vendorCode, String baseUrl, String apiKey,
        String extraAuthJson, String proxyMode, String proxyUrl
) {}

public record ModelProbeContext(
        Long modelConfigId, String provider, String modelName,
        List<String> capabilities, String baseUrl, String apiKey, String extraAuthJson
) {}

public record ConnectivityProbeResult(
        boolean success, String stage, Integer httpStatus, long latencyMs,
        String message, boolean fallbackUsed, String warning
) {
    public static ConnectivityProbeResult ok(String stage, Integer status, long latency, String message) {
        return new ConnectivityProbeResult(true, stage, status, latency, message, false, null);
    }
}
~~~

- [ ] **Step 4: Implement the optional strategy and ordered registry**

~~~java
public interface ProviderConnectivityProbe {
    int order();
    default Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        return Optional.empty();
    }
    default Optional<ConnectivityProbeResult> probeModel(ModelProbeContext context) {
        return Optional.empty();
    }
}

@Component
public final class ConnectivityProbeRegistry {
    private final List<ProviderConnectivityProbe> probes;

    public ConnectivityProbeRegistry(List<ProviderConnectivityProbe> probes) {
        this.probes = probes.stream()
                .sorted(Comparator.comparingInt(ProviderConnectivityProbe::order))
                .toList();
    }

    public Optional<ConnectivityProbeResult> probeAccount(AccountProbeContext context) {
        return probes.stream().map(probe -> probe.probeAccount(context))
                .flatMap(Optional::stream).findFirst();
    }

    public Optional<ConnectivityProbeResult> probeModel(ModelProbeContext context) {
        return probes.stream().map(probe -> probe.probeModel(context))
                .flatMap(Optional::stream).findFirst();
    }
}
~~~

- [ ] **Step 5: Run the test and verify GREEN**

Run: cd backend; mvn -Dtest=ConnectivityProbeRegistryTest test

Expected: all registry tests pass.

- [ ] **Step 6: Commit Task 1**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/ConnectivityProbeRegistryTest.java
git commit -m "refactor: add connectivity probe registry contracts"
~~~

## Task 2: Endpoint Resolution and Account Error Classification

**Files:**
- Create ModelsEndpointResolver.java and AccountProbeErrorClassifier.java.
- Test ModelsEndpointResolverTest.java and AccountProbeErrorClassifierTest.java.

- [ ] **Step 1: Write failing endpoint tests**

Assert DashScope root and compatible paths, Ark /api/v3/models, GLM /api/paas/v4/models, ordinary /v1/models, and empty results for Suno and Kling.

~~~java
assertThat(resolver.resolve("qwen", "https://dashscope.aliyuncs.com"))
        .contains("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
assertThat(resolver.resolve("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
        .contains("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
assertThat(resolver.resolve("volcengine", "https://ark.cn-beijing.volces.com"))
        .contains("https://ark.cn-beijing.volces.com/api/v3/models");
assertThat(resolver.resolve("zhipu", "https://open.bigmodel.cn/api/paas/v4"))
        .contains("https://open.bigmodel.cn/api/paas/v4/models");
assertThat(resolver.resolve("suno", "https://api.sunoapi.org")).isEmpty();
~~~

- [ ] **Step 2: Write failing classifier tests**

Define decisions PASS, WARNING, FAIL, and FALLBACK. Test 200 pass; 401 fail; invalid-key 403 fail; balance/free-quota 402 or 403 warning; 429 warning; 404/405/501 fallback; unknown 403 fail; and 5xx fail.

~~~java
assertThat(classifier.classify(403, "The free quota has been exhausted").decision())
        .isEqualTo(AccountProbeErrorClassifier.Decision.WARNING);
assertThat(classifier.classify(403, "invalid api key").decision())
        .isEqualTo(AccountProbeErrorClassifier.Decision.FAIL);
~~~

- [ ] **Step 3: Run both tests and verify RED**

Run: cd backend; mvn -Dtest=ModelsEndpointResolverTest,AccountProbeErrorClassifierTest test

Expected: compilation failure because both classes are missing.

- [ ] **Step 4: Implement vendor-aware endpoint resolution**

Use URI parsing. Map DashScope standard and MaaS hosts, Ark, GLM, and known OpenAI-compatible vendor codes. Return empty for proprietary protocols. Apply OpenAiCompatibleEndpointSupport only after special cases.

~~~java
public Optional<String> resolve(String vendorCode, String baseUrl) {
    String vendor = normalize(vendorCode);
    URI uri = URI.create(OpenAiCompatibleEndpointSupport.normalizedBaseUrl(baseUrl));
    String origin = uri.getScheme() + "://" + uri.getAuthority();
    if (Set.of("suno", "kling").contains(vendor)) return Optional.empty();
    if (Set.of("qwen", "dashscope").contains(vendor)) {
        return Optional.of(origin + "/compatible-mode/v1/models");
    }
    if ("volcengine".equals(vendor)) return Optional.of(origin + "/api/v3/models");
    if (Set.of("zhipu", "glm").contains(vendor)) return Optional.of(origin + "/api/paas/v4/models");
    return Optional.of(OpenAiCompatibleModelsEndpoint.resolve(baseUrl));
}
~~~

- [ ] **Step 5: Implement conservative classification**

Credential phrases: invalid api key, unauthorized, authentication failed, signature invalid, invalid token. Billing phrases: insufficient balance, quota exhausted, free quota, payment required, billing, rate limit. Unknown 403 remains FAIL.

~~~java
public Classification classify(int status, String body) {
    String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
    if (status >= 200 && status < 300) return pass("账户网关与凭据有效");
    if (status == 404 || status == 405 || status == 501) return fallback("厂商不支持模型列表探活");
    if (status == 429 || status == 402 || (status == 403 && containsBillingPhrase(text))) {
        return warning("凭据有效，但上游返回余额、额度或限流告警");
    }
    return fail(status == 401 || containsCredentialPhrase(text)
            ? "API Key 无效或权限不足" : "账户网关探活失败（HTTP " + status + "）");
}
~~~

- [ ] **Step 6: Run both tests and verify GREEN**

Run: cd backend; mvn -Dtest=ModelsEndpointResolverTest,AccountProbeErrorClassifierTest test

Expected: all tests pass.

- [ ] **Step 7: Commit Task 2**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsEndpointResolver.java backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/AccountProbeErrorClassifier.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsEndpointResolverTest.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/AccountProbeErrorClassifierTest.java
git commit -m "feat: resolve vendor account models probes"
~~~

## Task 3: Generic /models Account Probe

**Files:**
- Create ModelsAccountConnectivityProbe.java.
- Test ModelsAccountConnectivityProbeTest.java.

- [ ] **Step 1: Write failing HTTP tests**

Use JDK HttpServer. Verify Authorization and that no model, query parameter, or request body is sent. Test 200 success, quota 403 warning-success, invalid-key 403 failure, 404 Optional.empty(), and 500 terminal failure.

- [ ] **Step 2: Run the test and verify RED**

Run: cd backend; mvn -Dtest=ModelsAccountConnectivityProbeTest test

Expected: compilation failure because the strategy is missing.

- [ ] **Step 3: Implement the account strategy**

Inject resolver and classifier. Send GET with Accept application/json and Bearer auth via OutboundHttpClientFactory. Convert only FALLBACK into Optional.empty().

~~~java
return switch (classification.decision()) {
    case PASS -> Optional.of(ConnectivityProbeResult.ok(
            "ACCOUNT_MODELS", status, latency, "账户网关与凭据有效"));
    case WARNING -> Optional.of(new ConnectivityProbeResult(
            true, "ACCOUNT_MODELS", status, latency,
            "账户凭据有效，但上游返回额度或限流告警",
            false, classification.message()));
    case FAIL -> Optional.of(new ConnectivityProbeResult(
            false, "ACCOUNT_MODELS", status, latency,
            classification.message(), false, null));
    case FALLBACK -> Optional.empty();
};
~~~

Network exceptions, missing credentials, and 5xx return a present failed result so fallback cannot hide them.

- [ ] **Step 4: Run the test and verify GREEN**

Run: cd backend; mvn -Dtest=ModelsAccountConnectivityProbeTest test

Expected: all tests pass.

- [ ] **Step 5: Commit Task 3**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsAccountConnectivityProbe.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/ModelsAccountConnectivityProbeTest.java
git commit -m "feat: add models-based vendor account probe"
~~~

## Task 4: Integrate Account Tests

**Files:**
- Modify ModelVendorAccountServiceImpl.java lines 65-225.
- Modify ModelVendorAccountDiscoveryApiTest.java.

- [ ] **Step 1: Write failing API tests**

Create Qwen accounts backed by local HttpServer. Assert 200 sets account OK without Agent Service; quota 403 keeps account OK and enabled; invalid-key and unknown 403 set ERROR; 404 invokes old fallback; and 5xx does not. Read enabled, health_status, and linked model last_test fields through JdbcTemplate.

~~~java
mockMvc.perform(post("/api/admin/v1/model-vendor-accounts/{id}/test", accountId)
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true))
        .andExpect(jsonPath("$.data.account.healthStatus").value("OK"));

Mockito.verify(agentServiceClient, Mockito.never()).testModelConfig(argThat(request ->
        accountId.equals(request.vendorAccountId())));
~~~

- [ ] **Step 2: Run tests and verify RED**

Run: cd backend; mvn -Dtest=ModelVendorAccountDiscoveryApiTest test

Expected: Qwen still calls a concrete model.

- [ ] **Step 3: Call the registry first**

Build AccountProbeContext directly from ModelVendorAccount. A present result updates only account health, message, and updated time; preserve enabled and model test fields. Return modelName as an empty string because no model ran. Empty registry result enters existing fallback.

~~~java
Optional<ConnectivityProbeResult> probe = connectivityProbeRegistry.probeAccount(
        new AccountProbeContext(account.getId(), account.getVendorCode(), account.getBaseUrl(),
                account.getApiKey(), account.getExtraAuthJson(), null, null));
if (probe.isPresent()) {
    ConnectivityProbeResult result = probe.get();
    account.setHealthStatus(result.success() ? "OK" : "ERROR");
    account.setBalanceErrorMessage(result.warning() != null ? result.warning()
            : result.success() ? null : result.message());
    account.setUpdatedAt(LocalDateTime.now());
    vendorAccountMapper.updateAccount(account);
    return new ModelVendorAccountTestResponse(result.success(), result.message(),
            result.latencyMs(), account.getVendorCode(), "", toResponse(account));
}
~~~

- [ ] **Step 4: Run account regression tests**

Run: cd backend; mvn -Dtest=ModelVendorAccountDiscoveryApiTest,AdminAgentApiTest test

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 4**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/ModelVendorAccountServiceImpl.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/ModelVendorAccountDiscoveryApiTest.java
git commit -m "refactor: separate vendor account connectivity checks"
~~~

## Task 5: Protocol-Specific Model Probes

**Files:**
- Create OpenAiImagesModelConnectivityProbe.java.
- Create DashScopeHappyHorseModelConnectivityProbe.java.
- Create their two focused test classes.

- [ ] **Step 1: Write failing OpenAI Images tests**

Verify exact model-list matching and dry-run JSON with the actual model rather than __probe__, without prompt. Missing-prompt 400 is success. Capability-disabled, model-not-found, 401, and 403 are failures.

~~~java
assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
assertThat(body.has("prompt")).isFalse();
~~~

- [ ] **Step 2: Write failing HappyHorse tests**

Verify the video synthesis path, Bearer auth, X-DashScope-Async enable, actual model, and empty input. Missing-input validation is success; invalid model, 401/403, 404, quota failure, and 5xx are failures.

~~~java
assertThat(exchange.getRequestURI().getPath())
        .isEqualTo("/api/v1/services/aigc/video-generation/video-synthesis");
assertThat(exchange.getRequestHeaders().getFirst("X-DashScope-Async")).isEqualTo("enable");
~~~

- [ ] **Step 3: Run both tests and verify RED**

Run: cd backend; mvn -Dtest=OpenAiImagesModelConnectivityProbeTest,DashScopeHappyHorseModelConnectivityProbeTest test

Expected: compilation failure because the strategies are missing.

- [ ] **Step 4: Implement OpenAI Images probing**

Accept ofox_openai_images, openai_images_gateway, and agnes_images. Require an exact model ID from /models, then POST a JSON object containing the real model to the image endpoint. Treat only explicit missing-input validation as reachable.

~~~java
JsonNode payload = objectMapper.createObjectNode().put("model", context.modelName());
HttpResponse<String> response = sendJson(imagesUrl(context), context.apiKey(), payload.toString());
boolean reachedValidation = response.statusCode() == 400
        && isMissingPromptValidation(response.body());
return Optional.of(modelResult(response.statusCode() < 300 || reachedValidation, response));
~~~

- [ ] **Step 5: Implement HappyHorse probing**

Normalize DashScope root, /api/v1, and /compatible-mode/v1 to the origin. POST this non-billable body:

~~~json
{
  "model": "happyhorse-1.1-t2v",
  "input": {},
  "parameters": {}
}
~~~

Only known missing prompt/image/media validation means reachable. Arbitrary 4xx is not success.

~~~java
String origin = dashScopeOrigin(context.baseUrl());
ObjectNode payload = objectMapper.createObjectNode();
payload.put("model", context.modelName());
payload.set("input", objectMapper.createObjectNode());
payload.set("parameters", objectMapper.createObjectNode());
HttpResponse<String> response = sendDashScope(
        origin + "/api/v1/services/aigc/video-generation/video-synthesis",
        context.apiKey(), payload.toString());
boolean reachedValidation = response.statusCode() == 400
        && isMissingMediaValidation(response.body());
return Optional.of(modelResult(response.statusCode() < 300 || reachedValidation, response));
~~~

- [ ] **Step 6: Run both tests and verify GREEN**

Run: cd backend; mvn -Dtest=OpenAiImagesModelConnectivityProbeTest,DashScopeHappyHorseModelConnectivityProbeTest test

Expected: all tests pass.

- [ ] **Step 7: Commit Task 5**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/OpenAiImagesModelConnectivityProbe.java backend/src/main/java/com/aiminilab/aitoolmarket/agent/connectivity/DashScopeHappyHorseModelConnectivityProbe.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/OpenAiImagesModelConnectivityProbeTest.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/connectivity/DashScopeHappyHorseModelConnectivityProbeTest.java
git commit -m "feat: add protocol-specific media model probes"
~~~

## Task 6: Integrate Model Tests and Verify

**Files:**
- Modify AgentModelConfigServiceImpl.java lines 273-575.
- Modify AdminAgentApiTest.java.
- Modify ModelVendorAccountDiscoveryApiTest.java.

- [ ] **Step 1: Write failing model integration tests**

Prove HappyHorse uses the video dry-run rather than /models; Qwen quota failure changes only model fields; OpenAI Images sends the real model; and unregistered providers retain fallback.

- [ ] **Step 2: Run tests and verify RED**

Run: cd backend; mvn -Dtest=AdminAgentApiTest,ModelVendorAccountDiscoveryApiTest test

Expected: HappyHorse still reaches the generic model-list probe or image probing still sends __probe__.

- [ ] **Step 3: Invoke registry after credential resolution**

Build ModelProbeContext from the resolved execution config and capabilities. Map a present result to AgentModelConfigTestResponse. Only empty enters current accept-only, media, or Agent Service fallback.

~~~java
Optional<ConnectivityProbeResult> probe = connectivityProbeRegistry.probeModel(
        new ModelProbeContext(executable.getId(), executable.getProvider(), executable.getModelName(),
                capabilitiesCodec.parse(executable.getCapabilities()), executable.getBaseUrl(),
                executable.getApiKey(), executable.getExtraAuthJson()));
if (probe.isPresent()) {
    ConnectivityProbeResult result = probe.get();
    return new AgentModelConfigTestResponse(result.success(), executable.getProvider(),
            executable.getModelName(), result.latencyMs(), result.message(), "");
}
~~~

- [ ] **Step 4: Remove duplicated image methods**

After strategy tests pass, remove image-specific list and dry-run methods from AgentModelConfigServiceImpl. Retain generic legacy media probing for unmigrated providers.

- [ ] **Step 5: Run focused connectivity tests**

Run:

~~~powershell
cd backend
mvn -Dtest=ConnectivityProbeRegistryTest,ModelsEndpointResolverTest,AccountProbeErrorClassifierTest,ModelsAccountConnectivityProbeTest,OpenAiImagesModelConnectivityProbeTest,DashScopeHappyHorseModelConnectivityProbeTest,ModelVendorAccountDiscoveryApiTest,AdminAgentApiTest test
~~~

Expected: zero failures and zero errors.

- [ ] **Step 6: Run full backend tests**

Run: cd backend; mvn test

Expected: BUILD SUCCESS.

- [ ] **Step 7: Verify schema and production stability**

Run:

~~~powershell
git diff --check
git diff --name-only
git diff -- sql backend/src/main/resources
~~~

Expected: no SQL migration, no new table, no production SSH mutation, and no restart.

- [ ] **Step 8: Commit Task 6**

~~~bash
git add backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentModelConfigServiceImpl.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/AdminAgentApiTest.java backend/src/test/java/com/aiminilab/aitoolmarket/agent/ModelVendorAccountDiscoveryApiTest.java
git commit -m "refactor: separate account and model connectivity health"
~~~

## Final Acceptance

- [ ] DashScope account probe uses /compatible-mode/v1/models without invoking Qwen.
- [ ] Balance exhaustion and rate limits keep account health OK with a warning.
- [ ] Invalid credentials, unknown 403, network errors, and 5xx fail without fallback.
- [ ] Only 404/405/501 use compatibility fallback.
- [ ] Qwen and HappyHorse model tests do not overwrite account health.
- [ ] OpenAI Images uses the real model in capability dry-run.
- [ ] Existing CD deploys reviewed commits; production is not patched manually.
