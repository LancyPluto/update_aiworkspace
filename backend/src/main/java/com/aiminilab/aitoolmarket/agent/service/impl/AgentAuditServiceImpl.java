package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.dto.*;
import com.aiminilab.aitoolmarket.agent.config.AgentRuntimeSettings;
import com.aiminilab.aitoolmarket.agent.entity.*;
import com.aiminilab.aitoolmarket.agent.mapper.*;
import com.aiminilab.aitoolmarket.agent.service.AgentAuditService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.support.AgentAuditRedactor;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentAuditServiceImpl implements AgentAuditService {
    public static final String RETENTION_KEY = AgentRuntimeSettings.AUDIT_PAYLOAD_RETENTION_DAYS_KEY;
    private static final Set<String> CATEGORIES = Set.of("CONTEXT_INCOMPLETE", "TOOL_NOT_VISIBLE", "DISCLOSURE_MISS",
            "SKILL_MISSING", "TOOL_SELECTION_MISMATCH", "ARGUMENT_SCHEMA_ERROR", "EXECUTION_FAILURE", "UNDETERMINED");

    private final AgentRunMapper runMapper;
    private final AgentContextSnapshotMapper contextMapper;
    private final AgentModelRequestSnapshotMapper requestMapper;
    private final AgentRunAuditReviewMapper reviewMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentSkillBundleMapper skillMapper;
    private final AgentAuditRedactor redactor;
    private final AgentToolDescriptorService toolDescriptorService;
    private final SystemSettingService settingService;
    private final ObjectMapper objectMapper;

    public AgentAuditServiceImpl(AgentRunMapper runMapper, AgentContextSnapshotMapper contextMapper,
                                 AgentModelRequestSnapshotMapper requestMapper, AgentRunAuditReviewMapper reviewMapper,
                                 AgentRunEventMapper eventMapper, AgentSkillBundleMapper skillMapper,
                                 AgentAuditRedactor redactor, AgentToolDescriptorService toolDescriptorService,
                                 SystemSettingService settingService, ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.contextMapper = contextMapper;
        this.requestMapper = requestMapper;
        this.reviewMapper = reviewMapper;
        this.eventMapper = eventMapper;
        this.skillMapper = skillMapper;
        this.redactor = redactor;
        this.toolDescriptorService = toolDescriptorService;
        this.settingService = settingService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AgentModelRequestSnapshotResponse recordModelRequest(Long runId, CreateModelRequestSnapshotRequest request) {
        AgentRun run = requireRun(runId);
        String raw = writeJson(request.payload());
        JsonNode sanitized = redactor.redact(request.payload());
        AgentModelRequestSnapshot item = new AgentModelRequestSnapshot();
        item.setRunId(runId);
        item.setUserId(run.getUserId());
        item.setRequestSequence(request.requestSequence());
        item.setRequestStage(request.requestStage().trim());
        item.setIterationNo(request.iterationNo());
        item.setModelProviderCode(request.modelProviderCode());
        item.setModelName(request.modelName());
        item.setMessageCount(orZero(request.messageCount()));
        item.setToolCount(orZero(request.toolCount()));
        item.setEstimatedInputTokens(orZero(request.estimatedInputTokens()));
        item.setSkillCodesJson(writeJson(request.skillCodes() == null ? List.of() : request.skillCodes()));
        item.setPayloadJson(writeJson(sanitized));
        item.setPayloadSha256(redactor.sha256(raw));
        item.setPayloadExpiresAt(LocalDateTime.now().plusDays(retentionDays()));
        item.setCreatedAt(LocalDateTime.now());
        requestMapper.insertIgnore(item);
        AgentModelRequestSnapshot stored = requestMapper.findByRunAndSequence(runId, request.requestSequence());
        return response(stored);
    }

    @Override
    public AgentRunAuditResponse audit(Long runId) {
        AgentRun run = requireRun(runId);
        AgentContextSnapshot snapshot = run.getContextSnapshotId() == null ? null : contextMapper.selectById(run.getContextSnapshotId());
        List<AgentRunEvent> events = eventMapper.findEventsForAdmin(runId, 500);
        AgentRunAuditReview review = reviewMapper.findByRunId(runId);
        JsonNode snapshotPayload = parse(snapshot == null ? null : snapshot.getSnapshotJson());
        var diagnosis = diagnose(run, snapshotPayload, events, review);
        return new AgentRunAuditResponse(runId, snapshot == null ? null : snapshot.getId(), snapshotPayload,
                snapshot == null || snapshot.getSnapshotJson() == null, diagnosis, AgentRunAuditReviewResponse.from(review),
                requestMapper.countByRun(runId), evidence(events, Set.of("tool.disclosure")), evidence(events, Set.of("skill.hydrated")));
    }

    @Override
    public List<AgentModelRequestSnapshotResponse> modelRequests(Long runId, Long afterId, Integer pageSize) {
        requireRun(runId);
        int limit = pageSize == null ? 50 : Math.max(1, Math.min(pageSize, 200));
        return requestMapper.findByRun(runId, afterId, limit).stream().map(this::response).toList();
    }

    @Override
    @Transactional
    public AgentRunAuditReviewResponse review(Long runId, Long reviewerId, UpdateAgentRunAuditReviewRequest request) {
        requireRun(runId);
        String category = blankToNull(request.finalCategory());
        if (category != null && !CATEGORIES.contains(category)) throw new BusinessException(ErrorCode.PARAM_ERROR, "Unknown audit category");
        AgentRunAuditReview item = new AgentRunAuditReview();
        item.setRunId(runId);
        item.setExpectedToolCode(blankToNull(request.expectedToolCode()));
        item.setFinalCategory(category);
        item.setReviewNote(blankToNull(request.reviewNote()));
        item.setReviewedBy(reviewerId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        reviewMapper.upsert(item);
        return AgentRunAuditReviewResponse.from(reviewMapper.findByRunId(runId));
    }

    @Override
    public List<AgentSkillCoverageResponse> skillCoverage() {
        Map<String, Long> hydrationCounts = new HashMap<>();
        for (AgentRunEvent event : eventMapper.findRecentByType("skill.hydrated", 10000)) {
            String code = parse(event.getEventJson()).path("skillCode").asText("");
            if (!code.isBlank()) hydrationCounts.merge(code, 1L, Long::sum);
        }
        List<AgentSkillCoverageResponse> coverage = new ArrayList<>(skillMapper.selectLatestAll().stream().map(skill -> new AgentSkillCoverageResponse(skill.getSkillCode(),
                skill.getDisplayName(), skill.getStatus(), skill.getVersion(), parseStrings(skill.getToolCodesJson()),
                hydrationCounts.getOrDefault(skill.getSkillCode(), 0L))).toList());
        Set<String> coveredTools = coverage.stream().flatMap(item -> item.toolCodes().stream()).collect(java.util.stream.Collectors.toSet());
        toolDescriptorService.listAdminToolAccess().stream()
                .filter(tool -> Boolean.TRUE.equals(tool.agentEnabled()))
                .filter(tool -> !coveredTools.contains(tool.toolCode()))
                .forEach(tool -> coverage.add(new AgentSkillCoverageResponse(
                        "__uncovered__:" + tool.toolCode(), tool.toolName(), "UNMAPPED", null, List.of(tool.toolCode()), 0L)));
        return coverage;
    }

    @Override public int expirePayloads() { return requestMapper.expirePayloads(LocalDateTime.now(), 1000); }

    private AgentRunAuditResponse.Diagnosis diagnose(AgentRun run, JsonNode snapshot, List<AgentRunEvent> events, AgentRunAuditReview review) {
        List<String> evidence = new ArrayList<>();
        String category = "UNDETERMINED";
        String expectedTool = review == null ? null : review.getExpectedToolCode();

        // Fixed priority: context -> visibility -> disclosure -> skill -> selection -> arguments -> execution.
        if (snapshot == null || snapshot.isNull() || snapshot.path("version").asInt(0) < 2
                || events.stream().anyMatch(this::isContextIncompleteEvent)) {
            category = "CONTEXT_INCOMPLETE";
            evidence.add(snapshot == null || snapshot.isNull()
                    ? "Run 缺少不可变初始上下文快照"
                    : snapshot.path("version").asInt(0) < 2
                    ? "旧版快照未保存工具、Skill 和完整上下文证据"
                    : "附件、引用或必要历史在运行时不可用");
        } else if (expectedTool != null && !snapshotToolCodes(snapshot).contains(expectedTool)) {
            category = "TOOL_NOT_VISIBLE";
            evidence.add("管理员预期工具不在 Run 创建时的可见工具中: " + expectedTool);
        } else if (expectedTool != null && disclosureMiss(events, expectedTool)) {
            category = "DISCLOSURE_MISS";
            evidence.add("工具可见，但未进入 shortlist 或展开集合: " + expectedTool);
        } else if (events.stream().anyMatch(this::isSkillFailureEvent)
                || (expectedTool != null && !snapshotSkillToolCodes(snapshot).contains(expectedTool))) {
            category = "SKILL_MISSING";
            evidence.add(events.stream().anyMatch(this::isSkillFailureEvent)
                    ? "Skill 匹配或水合事件明确失败"
                    : "预期工具没有已发布 Skill 覆盖: " + expectedTool);
        } else if (events.stream().anyMatch(this::isSelectionMismatchEvent) || preferredToolMismatch(snapshot, events)) {
            category = "TOOL_SELECTION_MISMATCH";
            evidence.add("模型选择的工具与输出模态或 preferred tool 不一致");
        } else if (events.stream().anyMatch(this::isArgumentErrorEvent)) {
            category = "ARGUMENT_SCHEMA_ERROR";
            evidence.add("工具参数未通过 Schema、附件指针或 Backend 参数校验");
        } else if (run.getErrorCode() != null) {
            category = "EXECUTION_FAILURE";
            evidence.add("选择与参数之后的执行链路返回错误码 " + run.getErrorCode());
        }
        String summary = switch (category) {
            case "CONTEXT_INCOMPLETE" -> "输入上下文不完整"; case "TOOL_NOT_VISIBLE" -> "目标工具对本次 Run 不可见";
            case "DISCLOSURE_MISS" -> "目标工具未被渐进披露"; case "SKILL_MISSING" -> "工具 Skill 未正确水合";
            case "TOOL_SELECTION_MISMATCH" -> "工具选择与请求能力不匹配"; case "ARGUMENT_SCHEMA_ERROR" -> "工具参数不符合 Schema";
            case "EXECUTION_FAILURE" -> "选择与参数之后的执行链路失败"; default -> "现有证据不足，需人工复核";
        };
        return new AgentRunAuditResponse.Diagnosis(category, summary, evidence);
    }

    private boolean isContextIncompleteEvent(AgentRunEvent event) {
        String value = (event.getEventType() + " " + parse(event.getEventJson())).toLowerCase(Locale.ROOT);
        return (value.contains("attachment") || value.contains("file") || value.contains("reference"))
                && (value.contains("missing") || value.contains("not_found") || value.contains("not ready"));
    }

    private boolean isSkillFailureEvent(AgentRunEvent event) {
        if (!"skill.hydrated".equals(event.getEventType())) return false;
        JsonNode payload = parse(event.getEventJson());
        return payload.has("hydrated") && !payload.path("hydrated").asBoolean(true)
                || !payload.path("failureReason").asText("").isBlank();
    }

    private boolean isSelectionMismatchEvent(AgentRunEvent event) {
        String value = parse(event.getEventJson()).toString().toLowerCase(Locale.ROOT);
        return value.contains("modality_mismatch") || value.contains("output modality") || value.contains("tool_selection_mismatch");
    }

    private boolean isArgumentErrorEvent(AgentRunEvent event) {
        JsonNode payload = parse(event.getEventJson());
        String value = payload.toString().toLowerCase(Locale.ROOT);
        return payload.has("missingFields") || value.contains("schema") || value.contains("invalid_argument")
                || value.contains("attachment_pointer") || value.contains("parameter validation");
    }

    private Set<String> snapshotToolCodes(JsonNode snapshot) {
        Set<String> codes = new HashSet<>();
        for (JsonNode tool : snapshot.path("visibleTools")) {
            String code = tool.path("toolCode").asText("");
            if (!code.isBlank()) codes.add(code);
        }
        return codes;
    }

    private Set<String> snapshotSkillToolCodes(JsonNode snapshot) {
        Set<String> codes = new HashSet<>();
        for (JsonNode skill : snapshot.path("availableSkills")) {
            for (JsonNode code : skill.path("toolCodes")) if (!code.asText("").isBlank()) codes.add(code.asText());
        }
        return codes;
    }

    private boolean disclosureMiss(List<AgentRunEvent> events, String expectedTool) {
        for (AgentRunEvent event : events) {
            if (!"tool.disclosure".equals(event.getEventType())) continue;
            JsonNode payload = parse(event.getEventJson());
            if (containsText(payload.path("shortlistedToolCodes"), expectedTool)
                    || containsText(payload.path("expandedToolCodes"), expectedTool)) return false;
            if (containsText(payload.path("candidateToolCodes"), expectedTool)) return true;
        }
        return false;
    }

    private boolean preferredToolMismatch(JsonNode snapshot, List<AgentRunEvent> events) {
        String preferred = snapshot.path("preferredToolCode").asText("");
        if (preferred.isBlank()) return false;
        return events.stream().anyMatch(event -> {
            String selected = parse(event.getEventJson()).path("selectedToolCode").asText("");
            return !selected.isBlank() && !preferred.equals(selected);
        });
    }

    private boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private boolean eventMentionsTool(AgentRunEvent event, String tool) {
        JsonNode payload = parse(event.getEventJson());
        return tool.equals(payload.path("toolCode").asText()) || tool.equals(payload.path("selectedToolCode").asText())
                || payload.toString().contains("\"" + tool + "\"");
    }

    private List<AgentRunAuditResponse.EvidenceEvent> evidence(List<AgentRunEvent> events, Set<String> types) {
        return events.stream().filter(e -> types.contains(e.getEventType())).map(e -> new AgentRunAuditResponse.EvidenceEvent(
                e.getId(), e.getEventType(), parse(e.getEventJson()), e.getCreatedAt() == null ? null : e.getCreatedAt().toString())).toList();
    }

    private AgentModelRequestSnapshotResponse response(AgentModelRequestSnapshot item) {
        return AgentModelRequestSnapshotResponse.from(item, parse(item.getPayloadJson()), parseStrings(item.getSkillCodesJson()));
    }
    private AgentRun requireRun(Long runId) { return runMapper.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found")); }
    private int retentionDays() { try { return Math.max(1, Math.min(365, Integer.parseInt(settingService.settings().getOrDefault(RETENTION_KEY, String.valueOf(AgentRuntimeSettings.DEFAULT_AUDIT_PAYLOAD_RETENTION_DAYS))))); } catch (Exception ignored) { return AgentRuntimeSettings.DEFAULT_AUDIT_PAYLOAD_RETENTION_DAYS; } }
    private JsonNode parse(String value) { try { return value == null || value.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(value); } catch (Exception ignored) { return objectMapper.nullNode(); } }
    private List<String> parseStrings(String value) { try { return value == null ? List.of() : objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid audit payload", e); } }
    private int orZero(Integer value) { return value == null ? 0 : value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
