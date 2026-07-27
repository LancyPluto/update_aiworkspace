package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelInvocationRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelInvocationView;
import com.aiminilab.aitoolmarket.ppt.entity.PptJob;
import com.aiminilab.aitoolmarket.ppt.entity.PptModelInvocation;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptModelInvocationMapper;
import com.aiminilab.aitoolmarket.ppt.security.PptExecutionTokenService;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class PptModelInvocationService {
    private static final String TEXT = "TEXT_GENERATION";
    private static final String IMAGE = "IMAGE_GENERATION";

    private final PptWorkspaceService workspaceService;
    private final PptJobMapper jobMapper;
    private final PptModelInvocationMapper invocationMapper;
    private final PptPlatformModelBindingService modelBindingService;
    private final PptExecutionTokenService tokenService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public PptModelInvocationService(PptWorkspaceService workspaceService,
                                     PptJobMapper jobMapper,
                                     PptModelInvocationMapper invocationMapper,
                                     PptPlatformModelBindingService modelBindingService,
                                     PptExecutionTokenService tokenService,
                                     TaskService taskService,
                                     ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.jobMapper = jobMapper;
        this.invocationMapper = invocationMapper;
        this.modelBindingService = modelBindingService;
        this.tokenService = tokenService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    public PptExecutionTokenService.IssuedToken issue(Long projectId, Long jobId,
                                                      java.util.List<String> capabilities) {
        PptJob job = requireJob(projectId, jobId);
        workspaceService.requireProject(job.getUserId(), projectId);
        java.util.List<String> normalized = capabilities.stream()
                .map(this::normalizeCapability)
                .distinct()
                .toList();
        return tokenService.issue(projectId, jobId, normalized, 900);
    }

    @Transactional
    public PptModelInvocationView create(String authorization, PptModelInvocationRequest request) {
        String capability = normalizeCapability(request.capability());
        PptExecutionTokenService.Claims claims = tokenService.require(authorization);
        claims.requireScope(request.projectId(), request.pptJobId(), capability);
        PptJob job = requireJob(request.projectId(), request.pptJobId());
        PptProject project = workspaceService.requireProject(job.getUserId(), request.projectId());

        PptModelInvocation existing = invocationMapper.findIdempotent(
                request.pptJobId(), request.idempotencyKey().trim());
        if (existing != null) {
            return view(existing);
        }

        var binding = modelBindingService.resolve(project);
        Long modelConfigId = TEXT.equals(capability)
                ? binding.textModel().getId()
                : binding.imageModel().getId();
        ObjectNode params = request.input().isObject()
                ? ((ObjectNode) request.input()).deepCopy()
                : objectMapper.createObjectNode().set("input", request.input());
        params.put("_billingOwner", "PPT_JOB");
        params.put("_pptProjectId", project.getId());
        params.put("_pptJobId", job.getId());
        params.put("_pptCapability", capability);
        params.put("_pptInvocationKey", request.idempotencyKey().trim());

        String toolCode = TEXT.equals(capability)
                ? "ppt_platform_text_invocation"
                : "ppt_platform_image_invocation";
        TaskStatusResponse task = taskService.createForPptInvocation(
                job.getUserId(),
                new CreateTaskRequest(
                        toolCode,
                        params,
                        "ppt-model:" + job.getId() + ":" + request.idempotencyKey().trim(),
                        null,
                        modelConfigId
                )
        );

        LocalDateTime now = LocalDateTime.now();
        PptModelInvocation invocation = new PptModelInvocation();
        invocation.setUserId(job.getUserId());
        invocation.setProjectId(project.getId());
        invocation.setPptJobId(job.getId());
        invocation.setAiTaskId(task.taskId());
        invocation.setCapability(capability);
        invocation.setIdempotencyKey(request.idempotencyKey().trim());
        invocation.setCreatedAt(now);
        invocation.setUpdatedAt(now);
        try {
            invocationMapper.insert(invocation);
        } catch (DuplicateKeyException duplicate) {
            invocation = invocationMapper.findIdempotent(job.getId(), request.idempotencyKey().trim());
        }
        return view(invocation);
    }

    public PptModelInvocationView get(String authorization, Long invocationId) {
        PptExecutionTokenService.Claims claims = tokenService.require(authorization);
        PptModelInvocation invocation = invocationMapper.findScoped(
                invocationId, claims.projectId(), claims.jobId());
        if (invocation == null) {
            throw new BusinessException(ErrorCode.PPT_TASK_FAILED, "PPT 模型调用不存在");
        }
        claims.requireScope(invocation.getProjectId(), invocation.getPptJobId(), invocation.getCapability());
        return view(invocation);
    }

    private PptModelInvocationView view(PptModelInvocation invocation) {
        TaskDetailResponse detail = taskService.detail(invocation.getUserId(), invocation.getAiTaskId());
        JsonNode result = null;
        if (detail.result() != null && detail.result().contentText() != null) {
            result = normalizeResult(invocation.getCapability(), detail.result().contentText());
        }
        return new PptModelInvocationView(
                invocation.getId(),
                detail.status(),
                detail.progress(),
                detail.progressMessage(),
                result,
                detail.errorCode(),
                sanitize(detail.errorMessage()),
                "/api/internal/v1/ppt/model-invocations/" + invocation.getId()
        );
    }

    private JsonNode parseResult(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(value);
        }
    }

    JsonNode normalizeResult(String capability, String value) {
        if (TEXT.equals(capability)) {
            // The platform task result is model-generated text. It may itself be
            // valid JSON (for example a PPT outline), but the engine provider
            // contract still expects text rather than a parsed array/object.
            return objectMapper.createObjectNode().put("text", value);
        }
        return parseResult(value);
    }

    private PptJob requireJob(Long projectId, Long jobId) {
        PptJob job = jobMapper.selectById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new BusinessException(ErrorCode.PPT_TASK_FAILED, "PPT 父任务不存在");
        }
        return job;
    }

    private String normalizeCapability(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TEXT.equals(value) && !IMAGE.equals(value)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "PPT 模型能力仅支持文本或生图");
        }
        return value;
    }

    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
