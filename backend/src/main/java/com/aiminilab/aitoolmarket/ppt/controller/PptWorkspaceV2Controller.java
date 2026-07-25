package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.ppt.dto.CreatePptWorkspaceProjectRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptExportView;
import com.aiminilab.aitoolmarket.ppt.dto.PptJobView;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelBindingView;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelOptionsView;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectView;
import com.aiminilab.aitoolmarket.ppt.dto.PptWorkbenchStatusView;
import com.aiminilab.aitoolmarket.ppt.dto.RetryPptJobRequest;
import com.aiminilab.aitoolmarket.ppt.dto.SubmitPptJobRequest;
import com.aiminilab.aitoolmarket.ppt.dto.UpdatePptModelBindingRequest;
import com.aiminilab.aitoolmarket.ppt.engine.EngineCapabilities;
import com.aiminilab.aitoolmarket.ppt.service.PptJobApplicationService;
import com.aiminilab.aitoolmarket.ppt.service.PptPlatformModelBindingService;
import com.aiminilab.aitoolmarket.ppt.service.PptWorkspaceService;
import com.aiminilab.aitoolmarket.ppt.service.PptWorkbenchGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2/ppt")
public class PptWorkspaceV2Controller {

    private final PptWorkspaceService workspaceService;
    private final PptJobApplicationService jobService;
    private final PptPlatformModelBindingService platformModelBindingService;
    private final PptWorkbenchGuard workbenchGuard;

    public PptWorkspaceV2Controller(PptWorkspaceService workspaceService,
                                    PptJobApplicationService jobService,
                                    PptPlatformModelBindingService platformModelBindingService,
                                    PptWorkbenchGuard workbenchGuard) {
        this.workspaceService = workspaceService;
        this.jobService = jobService;
        this.platformModelBindingService = platformModelBindingService;
        this.workbenchGuard = workbenchGuard;
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<EngineCapabilities>> capabilities() {
        return ApiResponse.success(workspaceService.capabilities());
    }

    @GetMapping("/status")
    public ApiResponse<PptWorkbenchStatusView> status() {
        boolean enabled = workbenchGuard.enabled();
        return ApiResponse.success(new PptWorkbenchStatusView(
                enabled,
                enabled
                        ? "PPT 工作台可用"
                        : "PPT 工作台维护中，暂不可新建或生成；已有项目与文件仍可查看下载"
        ));
    }

    @GetMapping("/model-options")
    public ApiResponse<PptModelOptionsView> modelOptions() {
        PptPlatformModelBindingService.ModelOptions options = platformModelBindingService.options();
        return ApiResponse.success(PptModelOptionsView.from(
                options.textModels(),
                options.imageModels()
        ));
    }

    @PostMapping("/projects")
    public ApiResponse<PptProjectView> createProject(
            @Valid @RequestBody CreatePptWorkspaceProjectRequest request) {
        workbenchGuard.requireGenerationEnabled();
        return ApiResponse.success(workspaceService.create(AuthContext.get().userId(), request));
    }

    @GetMapping("/projects")
    public ApiResponse<PageResponse<PptProjectView>> listProjects(
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(workspaceService.list(AuthContext.get().userId(), pageNo, pageSize));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<PptProjectView> projectDetail(@PathVariable Long projectId) {
        return ApiResponse.success(workspaceService.detail(AuthContext.get().userId(), projectId));
    }

    @GetMapping("/projects/{projectId}/model-binding")
    public ApiResponse<PptModelBindingView> modelBinding(@PathVariable Long projectId) {
        return ApiResponse.success(PptModelBindingView.from(
                platformModelBindingService.resolve(
                        workspaceService.requireProject(AuthContext.get().userId(), projectId)
                )
        ));
    }

    @PutMapping("/projects/{projectId}/model-binding")
    public ApiResponse<PptModelBindingView> updateModelBinding(
            @PathVariable Long projectId,
            @RequestBody UpdatePptModelBindingRequest request) {
        workbenchGuard.requireGenerationEnabled();
        return ApiResponse.success(PptModelBindingView.from(
                platformModelBindingService.updateSelection(
                        workspaceService.requireProject(AuthContext.get().userId(), projectId),
                        request.textModelConfigId(),
                        request.imageModelConfigId()
                )
        ));
    }

    @DeleteMapping("/projects/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable Long projectId) {
        workspaceService.delete(AuthContext.get().userId(), projectId);
        return ApiResponse.success(null);
    }

    @PostMapping("/projects/{projectId}/jobs")
    public ApiResponse<PptJobView> submitJob(@PathVariable Long projectId,
                                             @Valid @RequestBody SubmitPptJobRequest request) {
        workbenchGuard.requireGenerationEnabled();
        return ApiResponse.success(jobService.submit(AuthContext.get().userId(), projectId, request));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<PptJobView> job(@PathVariable Long jobId) {
        return ApiResponse.success(jobService.get(AuthContext.get().userId(), jobId));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ApiResponse<PptJobView> retry(@PathVariable Long jobId,
                                         @Valid @RequestBody RetryPptJobRequest request) {
        workbenchGuard.requireGenerationEnabled();
        return ApiResponse.success(jobService.retry(
                AuthContext.get().userId(), jobId, request.clientRequestId()));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ApiResponse<PptJobView> cancel(@PathVariable Long jobId) {
        return ApiResponse.success(jobService.cancel(AuthContext.get().userId(), jobId));
    }

    @GetMapping("/projects/{projectId}/exports")
    public ApiResponse<List<PptExportView>> exports(@PathVariable Long projectId) {
        return ApiResponse.success(workspaceService.exports(AuthContext.get().userId(), projectId));
    }

    @GetMapping("/projects/{projectId}/exports/{exportId}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable Long projectId,
                                                  @PathVariable Long exportId) {
        byte[] content = jobService.downloadExport(AuthContext.get().userId(), projectId, exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("presentation-" + projectId + ".pptx", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .body(content);
    }

    @GetMapping("/projects/{projectId}/files/**")
    public ResponseEntity<byte[]> engineFile(@PathVariable Long projectId, HttpServletRequest request) {
        String prefix = "/api/v2/ppt/projects/" + projectId + "/files/";
        String uri = request.getRequestURI();
        String relative = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        byte[] content = jobService.downloadEngineFile(AuthContext.get().userId(), projectId, relative);
        return ResponseEntity.ok()
                .contentType(guessMediaType(relative))
                .body(content);
    }

    private MediaType guessMediaType(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
