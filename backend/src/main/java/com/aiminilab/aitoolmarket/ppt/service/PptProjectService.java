package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.ppt.dto.CreatePptProjectRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptExportResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectCreatedResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectSummaryResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptStepRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptTaskResponse;
import com.aiminilab.aitoolmarket.ppt.entity.PptProjectBinding;
import com.aiminilab.aitoolmarket.ppt.entity.PptStepBillingLog;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectBindingMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptStepBillingLogMapper;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PptProjectService {

    private static final String STEP_CREATE = "CREATE";
    private static final String STEP_OUTLINE = "OUTLINE";
    private static final String STEP_DESCRIPTIONS = "DESCRIPTIONS";
    private static final String STEP_IMAGES = "IMAGES";
    private static final String STEP_EXPORT_PPTX = "EXPORT_PPTX";
    private static final String STEP_EXPORT_PDF = "EXPORT_PDF";

    private final PptProjectBindingMapper bindingMapper;
    private final PptStepBillingLogMapper billingLogMapper;
    private final PptWorkflowService pptWorkflowService;
    private final PptBillingService pptBillingService;
    private final PptEngineClient pptEngineClient;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;

    public PptProjectService(PptProjectBindingMapper bindingMapper,
                             PptStepBillingLogMapper billingLogMapper,
                             PptWorkflowService pptWorkflowService,
                             PptBillingService pptBillingService,
                             PptEngineClient pptEngineClient,
                             CreditService creditService,
                             ObjectMapper objectMapper) {
        this.bindingMapper = bindingMapper;
        this.billingLogMapper = billingLogMapper;
        this.pptWorkflowService = pptWorkflowService;
        this.pptBillingService = pptBillingService;
        this.pptEngineClient = pptEngineClient;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PptProjectCreatedResponse createProject(Long userId, CreatePptProjectRequest request) {
        AiTool tool = pptWorkflowService.requireOnlinePptTool();
        PptWorkflow workflow = pptWorkflowService.requireWorkflow(tool);
        pptWorkflowService.validateCreationType(workflow, request.creationType());

        int createCredits = pptWorkflowService.resolveCredits(tool, workflow, STEP_CREATE);
        creditService.freeze(userId, CreditSourceType.PPT_STEP, userId, createCredits);

        String bananaProjectId = null;
        try {
            JsonNode engineData = pptEngineClient.createProject(buildCreateBody(request));
            bananaProjectId = textOrNull(engineData, "project_id");
            if (bananaProjectId == null || bananaProjectId.isBlank()) {
                throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "引擎未返回 project_id");
            }

            PptProjectBinding binding = new PptProjectBinding();
            binding.setUserId(userId);
            binding.setToolId(tool.getId());
            binding.setBananaProjectId(bananaProjectId);
            binding.setCreationType(request.creationType());
            binding.setTitle(resolveTitle(request));
            binding.setStatus(textOrDefault(engineData, "status", "DRAFT"));
            LocalDateTime now = LocalDateTime.now();
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            bindingMapper.insertBinding(binding);

            creditService.settle(userId, CreditSourceType.PPT_STEP, userId, createCredits);
            recordBillingLog(userId, binding.getId(), STEP_CREATE, createCredits, request.clientRequestId());

            return new PptProjectCreatedResponse(binding.getId(), bananaProjectId, binding.getStatus());
        } catch (RuntimeException exception) {
            creditService.release(userId, CreditSourceType.PPT_STEP, userId, createCredits);
            if (bananaProjectId != null) {
                try {
                    pptEngineClient.deleteProject(bananaProjectId);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            }
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, exception.getMessage());
        }
    }

    public List<PptProjectSummaryResponse> listProjects(Long userId) {
        return bindingMapper.findByUserId(userId).stream()
                .map(binding -> PptProjectSummaryResponse.from(binding, null))
                .toList();
    }

    public ObjectNode getProjectDetail(Long userId, Long bindingId) {
        PptProjectBinding binding = requireBinding(bindingId, userId);
        JsonNode engineProject = pptEngineClient.getProject(binding.getBananaProjectId());
        syncBinding(binding, engineProject);
        return mergeDetail(binding, engineProject);
    }

    @Transactional
    public void deleteProject(Long userId, Long bindingId) {
        PptProjectBinding binding = requireBinding(bindingId, userId);
        try {
            pptEngineClient.deleteProject(binding.getBananaProjectId());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.PPT_ENGINE_ERROR) {
                throw exception;
            }
        }
        bindingMapper.deleteByIdAndUser(bindingId, userId);
    }

    public JsonNode generateOutline(Long userId, Long bindingId, Map<String, Object> body, PptStepRequest stepRequest) {
        Context ctx = loadContext(userId, bindingId);
        return pptBillingService.chargeStep(
                userId,
                bindingId,
                ctx.tool(),
                ctx.workflow(),
                STEP_OUTLINE,
                clientRequestId(stepRequest),
                () -> {
                    JsonNode result = pptEngineClient.postProjectAction(
                            ctx.binding().getBananaProjectId(),
                            "/generate/outline",
                            body
                    );
                    syncBinding(ctx.binding(), pptEngineClient.getProject(ctx.binding().getBananaProjectId()));
                    return result;
                }
        );
    }

    public PptTaskResponse generateDescriptions(Long userId, Long bindingId, PptStepRequest stepRequest) {
        Context ctx = loadContext(userId, bindingId);
        JsonNode data = pptBillingService.chargeStep(
                userId,
                bindingId,
                ctx.tool(),
                ctx.workflow(),
                STEP_DESCRIPTIONS,
                clientRequestId(stepRequest),
                () -> pptEngineClient.postProjectAction(
                        ctx.binding().getBananaProjectId(),
                        "/generate/descriptions",
                        Map.of()
                )
        );
        syncBinding(ctx.binding(), pptEngineClient.getProject(ctx.binding().getBananaProjectId()));
        return toTaskResponse(data);
    }

    public PptTaskResponse generateImages(Long userId, Long bindingId, Map<String, Object> body, PptStepRequest stepRequest) {
        Context ctx = loadContext(userId, bindingId);
        JsonNode data = pptBillingService.chargeStep(
                userId,
                bindingId,
                ctx.tool(),
                ctx.workflow(),
                STEP_IMAGES,
                clientRequestId(stepRequest),
                () -> pptEngineClient.postProjectAction(
                        ctx.binding().getBananaProjectId(),
                        "/generate/images",
                        body == null ? Map.of() : body
                )
        );
        syncBinding(ctx.binding(), pptEngineClient.getProject(ctx.binding().getBananaProjectId()));
        return toTaskResponse(data);
    }

    public PptTaskResponse getTask(Long userId, Long bindingId, String taskId) {
        Context ctx = loadContext(userId, bindingId);
        JsonNode data = pptEngineClient.getProjectAction(ctx.binding().getBananaProjectId(), "/tasks/" + taskId);
        PptTaskResponse response = toTaskResponse(data);
        if ("FAILED".equalsIgnoreCase(response.status())) {
            throw new BusinessException(ErrorCode.PPT_TASK_FAILED, response.errorMessage() == null ? "任务失败" : response.errorMessage());
        }
        return response;
    }

    public PptExportResponse exportPptx(Long userId, Long bindingId, String filename, PptStepRequest stepRequest) {
        Context ctx = loadContext(userId, bindingId);
        JsonNode data = pptBillingService.chargeStep(
                userId,
                bindingId,
                ctx.tool(),
                ctx.workflow(),
                STEP_EXPORT_PPTX,
                clientRequestId(stepRequest),
                () -> {
                    String query = filename == null || filename.isBlank() ? "" : "?filename=" + filename;
                    return pptEngineClient.getProjectAction(ctx.binding().getBananaProjectId(), "/export/pptx" + query);
                }
        );
        return new PptExportResponse(rewriteDownloadUrl(bindingId, textOrNull(data, "download_url")));
    }

    public PptExportResponse exportImages(Long userId, Long bindingId, String pageIds) {
        Context ctx = loadContext(userId, bindingId);
        String query = pageIds == null || pageIds.isBlank() ? "" : "?page_ids=" + pageIds;
        JsonNode data = pptEngineClient.getProjectAction(ctx.binding().getBananaProjectId(), "/export/images" + query);
        return new PptExportResponse(rewriteDownloadUrl(bindingId, textOrNull(data, "download_url")));
    }

    public JsonNode uploadTemplate(Long userId, Long bindingId, MultipartFile file) {
        Context ctx = loadContext(userId, bindingId);
        MultiValueMap<String, Object> form = PptEngineClient.singleFilePart("template_image", file);
        JsonNode result = pptEngineClient.postProjectMultipart(
                ctx.binding().getBananaProjectId(),
                "/template",
                form
        );
        syncBinding(ctx.binding(), pptEngineClient.getProject(ctx.binding().getBananaProjectId()));
        return result;
    }

    public PptExportResponse exportPdf(Long userId, Long bindingId, String filename, PptStepRequest stepRequest) {
        Context ctx = loadContext(userId, bindingId);
        JsonNode data = pptBillingService.chargeStep(
                userId,
                bindingId,
                ctx.tool(),
                ctx.workflow(),
                STEP_EXPORT_PDF,
                clientRequestId(stepRequest),
                () -> {
                    String query = filename == null || filename.isBlank() ? "" : "?filename=" + filename;
                    return pptEngineClient.getProjectAction(ctx.binding().getBananaProjectId(), "/export/pdf" + query);
                }
        );
        return new PptExportResponse(rewriteDownloadUrl(bindingId, textOrNull(data, "download_url")));
    }

    public JsonNode proxyMutation(Long userId,
                                  Long bindingId,
                                  String subPath,
                                  String method,
                                  Map<String, Object> body) {
        Context ctx = loadContext(userId, bindingId);
        String projectId = ctx.binding().getBananaProjectId();
        return switch (method.toUpperCase()) {
            case "PUT" -> pptEngineClient.putProjectAction(projectId, subPath, body);
            case "POST" -> pptEngineClient.postProjectAction(projectId, subPath, body == null ? Map.of() : body);
            default -> throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的代理方法: " + method);
        };
    }

    @Transactional
    public PptProjectCreatedResponse createRenovation(Long userId, MultipartFile file, String clientRequestId) {
        AiTool tool = pptWorkflowService.requireOnlinePptTool();
        PptWorkflow workflow = pptWorkflowService.requireWorkflow(tool);
        pptWorkflowService.validateCreationType(workflow, "ppt_renovation");

        int createCredits = pptWorkflowService.resolveCredits(tool, workflow, STEP_CREATE);
        creditService.freeze(userId, CreditSourceType.PPT_STEP, userId, createCredits);

        String bananaProjectId = null;
        try {
            MultiValueMap<String, Object> form = PptEngineClient.singleFilePart("file", file);
            JsonNode data = pptEngineClient.postRenovation(form);

            bananaProjectId = textOrNull(data, "project_id");
            if (bananaProjectId == null) {
                throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "翻新项目创建失败");
            }

            PptProjectBinding binding = new PptProjectBinding();
            binding.setUserId(userId);
            binding.setToolId(tool.getId());
            binding.setBananaProjectId(bananaProjectId);
            binding.setCreationType("ppt_renovation");
            binding.setTitle(file == null ? "PPT 翻新" : file.getOriginalFilename());
            binding.setStatus(textOrDefault(data, "status", "DRAFT"));
            LocalDateTime now = LocalDateTime.now();
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            bindingMapper.insertBinding(binding);

            creditService.settle(userId, CreditSourceType.PPT_STEP, userId, createCredits);
            recordBillingLog(userId, binding.getId(), STEP_CREATE, createCredits, clientRequestId);

            return new PptProjectCreatedResponse(
                    binding.getId(),
                    bananaProjectId,
                    binding.getStatus()
            );
        } catch (RuntimeException exception) {
            creditService.release(userId, CreditSourceType.PPT_STEP, userId, createCredits);
            if (bananaProjectId != null) {
                try {
                    pptEngineClient.deleteProject(bananaProjectId);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            }
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, exception.getMessage());
        }
    }

    public String resolveEngineFilePath(Long userId, Long bindingId, String relativePath) {
        PptProjectBinding binding = requireBinding(bindingId, userId);
        String normalized = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        if (normalized.startsWith("files/")) {
            normalized = normalized.substring("files/".length());
        }
        if (normalized.startsWith(binding.getBananaProjectId() + "/")) {
            return "/files/" + normalized;
        }
        return "/files/" + binding.getBananaProjectId() + "/" + normalized;
    }

    private Context loadContext(Long userId, Long bindingId) {
        AiTool tool = pptWorkflowService.requireOnlinePptTool();
        PptWorkflow workflow = pptWorkflowService.requireWorkflow(tool);
        PptProjectBinding binding = requireBinding(bindingId, userId);
        return new Context(tool, workflow, binding);
    }

    private PptProjectBinding requireBinding(Long bindingId, Long userId) {
        return bindingMapper.findOptional(bindingId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT 项目不存在"));
    }

    private void syncBinding(PptProjectBinding binding, JsonNode engineProject) {
        if (engineProject == null || engineProject.isNull()) {
            return;
        }
        String status = textOrNull(engineProject, "status");
        String title = textOrNull(engineProject, "idea_prompt");
        bindingMapper.updateStatus(
                binding.getId(),
                binding.getUserId(),
                status == null ? binding.getStatus() : status,
                title == null ? binding.getTitle() : truncate(title, 255),
                LocalDateTime.now()
        );
        if (status != null) {
            binding.setStatus(status);
        }
    }

    private ObjectNode mergeDetail(PptProjectBinding binding, JsonNode engineProject) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("bindingId", binding.getId());
        node.put("projectId", binding.getBananaProjectId());
        node.put("creationType", binding.getCreationType());
        node.put("title", binding.getTitle());
        node.put("status", binding.getStatus());
        if (engineProject != null && engineProject.isObject()) {
            engineProject.fields().forEachRemaining(entry -> node.set(entry.getKey(), entry.getValue()));
        }
        return node;
    }

    private Map<String, Object> buildCreateBody(CreatePptProjectRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("creation_type", request.creationType());
        putIfPresent(body, "idea_prompt", request.ideaPrompt());
        putIfPresent(body, "outline_text", request.outlineText());
        putIfPresent(body, "description_text", request.descriptionText());
        putIfPresent(body, "template_style", request.templateStyle());
        putIfPresent(body, "image_aspect_ratio", request.imageAspectRatio() == null ? "16:9" : request.imageAspectRatio());
        return body;
    }

    private void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private PptTaskResponse toTaskResponse(JsonNode data) {
        if (data == null || data.isNull()) {
            return new PptTaskResponse(null, "UNKNOWN", null, null);
        }
        String taskId = textOrNull(data, "task_id");
        if (taskId == null) {
            taskId = textOrNull(data, "id");
        }
        String status = textOrNull(data, "status");
        JsonNode progress = data.get("progress");
        String errorMessage = textOrNull(data, "error_message");
        if (errorMessage == null && data.has("error")) {
            errorMessage = data.path("error").asText(null);
        }
        return new PptTaskResponse(taskId, status, progress, errorMessage);
    }

    private String rewriteDownloadUrl(Long bindingId, String engineUrl) {
        if (engineUrl == null || engineUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PPT_EXPORT_FAILED, "导出地址为空");
        }
        String path = engineUrl;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int idx = path.indexOf("/files/");
            path = idx >= 0 ? path.substring(idx + "/files/".length()) : path;
        } else if (path.startsWith("/files/")) {
            path = path.substring("/files/".length());
        }
        return "/api/v1/ppt/files/" + bindingId + "/" + path;
    }

    private String resolveTitle(CreatePptProjectRequest request) {
        if (request.title() != null && !request.title().isBlank()) {
            return request.title().trim();
        }
        if (request.ideaPrompt() != null && !request.ideaPrompt().isBlank()) {
            return truncate(request.ideaPrompt().trim(), 255);
        }
        return "未命名 PPT 项目";
    }

    private String clientRequestId(PptStepRequest request) {
        return request == null ? null : request.clientRequestId();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = textOrNull(node, field);
        return value == null ? defaultValue : value;
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void recordBillingLog(Long userId, Long bindingId, String stepCode, int credits, String clientRequestId) {
        if (credits <= 0 || bindingId == null) {
            return;
        }
        PptStepBillingLog log = new PptStepBillingLog();
        log.setUserId(userId);
        log.setBindingId(bindingId);
        log.setStepCode(stepCode);
        log.setCreditsCharged(credits);
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            log.setClientRequestId(clientRequestId.trim());
        }
        log.setCreatedAt(LocalDateTime.now());
        billingLogMapper.insertLog(log);
    }

    private record Context(AiTool tool, PptWorkflow workflow, PptProjectBinding binding) {
    }
}
