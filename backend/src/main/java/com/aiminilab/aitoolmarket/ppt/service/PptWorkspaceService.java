package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.dto.CreatePptWorkspaceProjectRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptExportView;
import com.aiminilab.aitoolmarket.ppt.dto.PptJobView;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectView;
import com.aiminilab.aitoolmarket.ppt.engine.EngineCapabilities;
import com.aiminilab.aitoolmarket.ppt.engine.PptEngineRegistry;
import com.aiminilab.aitoolmarket.ppt.entity.PptExport;
import com.aiminilab.aitoolmarket.ppt.entity.PptJob;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptExportMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PptWorkspaceService {
    public static final String DEFAULT_TOOL_CODE = "banana_ppt_generator";

    private final PptProjectMapper projectMapper;
    private final PptJobMapper jobMapper;
    private final PptExportMapper exportMapper;
    private final ToolMapper toolMapper;
    private final PptEngineRegistry engineRegistry;
    private final ObjectMapper objectMapper;
    private final PptPlatformModelBindingService modelBindingService;

    public PptWorkspaceService(PptProjectMapper projectMapper,
                               PptJobMapper jobMapper,
                               PptExportMapper exportMapper,
                               ToolMapper toolMapper,
                               PptEngineRegistry engineRegistry,
                               ObjectMapper objectMapper,
                               PptPlatformModelBindingService modelBindingService) {
        this.projectMapper = projectMapper;
        this.jobMapper = jobMapper;
        this.exportMapper = exportMapper;
        this.toolMapper = toolMapper;
        this.engineRegistry = engineRegistry;
        this.objectMapper = objectMapper;
        this.modelBindingService = modelBindingService;
    }

    @Transactional
    public PptProjectView create(Long userId, CreatePptWorkspaceProjectRequest request) {
        modelBindingService.validateSelection(
                request.textModelConfigId(),
                request.imageModelConfigId()
        );
        AiTool tool = toolMapper.findOnlineByCode(defaultValue(request.toolCode(), DEFAULT_TOOL_CODE))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "PPT 工具未上线"));
        LocalDateTime now = LocalDateTime.now();
        PptProject project = new PptProject();
        project.setUserId(userId);
        project.setToolId(tool.getId());
        project.setTitle(request.title().trim());
        project.setTopic(request.topic().trim());
        project.setCreationType(defaultValue(request.creationType(), "idea"));
        project.setLanguage(defaultValue(request.language(), "zh-CN"));
        project.setAspectRatio(defaultValue(request.aspectRatio(), "16:9"));
        project.setPageCount(request.pageCount());
        project.setStatus("DRAFT");
        project.setEngineStrategy("VISUAL");
        project.setTextModelConfigId(request.textModelConfigId());
        project.setImageModelConfigId(request.imageModelConfigId());
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insertProject(project);
        return toView(project, List.of(), List.of());
    }

    public PageResponse<PptProjectView> list(Long userId, Integer pageNo, Integer pageSize) {
        int limit = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<PptProjectView> projects = projectMapper.findByUser(userId, limit, offset).stream()
                .map(project -> toView(project, List.of(), List.of()))
                .toList();
        return PageResponse.of(projects, projectMapper.countByUser(userId), pageNo, pageSize);
    }

    public PptProjectView detail(Long userId, Long projectId) {
        PptProject project = requireProject(userId, projectId);
        List<PptJob> jobs = jobMapper.findRecentByProject(projectId, 20);
        List<PptExport> exports = exportMapper.findByProjectAndUser(projectId, userId);
        return toView(project, jobs, exports);
    }

    @Transactional
    public void delete(Long userId, Long projectId) {
        if (projectMapper.softDelete(projectId, userId) != 1) {
            throw new BusinessException(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT 项目不存在");
        }
    }

    public List<EngineCapabilities> capabilities() {
        return engineRegistry.capabilities();
    }

    public List<PptExportView> exports(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return exportMapper.findByProjectAndUser(projectId, userId).stream()
                .map(this::toExportView)
                .toList();
    }

    public PptProject requireProject(Long userId, Long projectId) {
        PptProject project = projectMapper.findByIdAndUser(projectId, userId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT 项目不存在");
        }
        return project;
    }

    public PptJobView toJobView(PptJob job) {
        JsonNode result = readJson(job.getResultJson());
        PptJobView.ErrorView error = job.getErrorCode() == null && job.getErrorMessage() == null
                ? null
                : new PptJobView.ErrorView(job.getErrorCode(), job.getErrorMessage());
        return new PptJobView(
                job.getId(),
                job.getProjectId(),
                job.getJobType(),
                job.getStatus(),
                value(job.getProgress()),
                job.getProgressMessage(),
                job.getEngineCode(),
                job.getCreditState(),
                value(job.getReservedCredits()),
                value(job.getActualCredits()),
                Boolean.TRUE.equals(job.getRetryable()),
                error,
                result,
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getFinishedAt()
        );
    }

    public PptExportView toExportView(PptExport export) {
        return new PptExportView(
                export.getId(),
                export.getProjectId(),
                export.getExportType(),
                export.getStatus(),
                export.getFileName(),
                export.getContentType(),
                export.getFileSize(),
                "/api/v2/ppt/projects/" + export.getProjectId() + "/exports/" + export.getId() + "/download",
                export.getCreatedAt()
        );
    }

    private PptProjectView toView(PptProject project, List<PptJob> jobs, List<PptExport> exports) {
        JsonNode latestDeck = jobs.stream()
                .filter(job -> "SUCCEEDED".equals(job.getStatus()))
                .filter(job -> job.getResultJson() != null)
                .filter(job -> !job.getJobType().startsWith("EXPORT_"))
                .map(job -> readJson(job.getResultJson()))
                .findFirst()
                .orElse(null);
        return new PptProjectView(
                project.getId(),
                project.getTitle(),
                project.getTopic(),
                project.getCreationType(),
                project.getLanguage(),
                project.getAspectRatio(),
                project.getPageCount(),
                project.getStatus(),
                project.getEngineStrategy(),
                latestDeck,
                jobs.stream().map(this::toJobView).toList(),
                exports.stream().map(this::toExportView).toList(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }
}
