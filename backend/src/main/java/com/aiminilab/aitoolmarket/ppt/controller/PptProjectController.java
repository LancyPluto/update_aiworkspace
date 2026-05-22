package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.ppt.dto.CreatePptProjectRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptExportResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectCreatedResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptProjectSummaryResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptStepRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptTaskResponse;
import com.aiminilab.aitoolmarket.ppt.service.PptProjectService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ppt/projects")
public class PptProjectController {

    private final PptProjectService pptProjectService;

    public PptProjectController(PptProjectService pptProjectService) {
        this.pptProjectService = pptProjectService;
    }

    @PostMapping
    public ApiResponse<PptProjectCreatedResponse> create(@Valid @RequestBody CreatePptProjectRequest request) {
        return ApiResponse.success(pptProjectService.createProject(AuthContext.get().userId(), request));
    }

    @GetMapping
    public ApiResponse<Map<String, List<PptProjectSummaryResponse>>> list() {
        return ApiResponse.success(Map.of(
                "projects",
                pptProjectService.listProjects(AuthContext.get().userId())
        ));
    }

    @GetMapping("/{bindingId}")
    public ApiResponse<ObjectNode> detail(@PathVariable Long bindingId) {
        return ApiResponse.success(pptProjectService.getProjectDetail(AuthContext.get().userId(), bindingId));
    }

    @DeleteMapping("/{bindingId}")
    public ApiResponse<Void> delete(@PathVariable Long bindingId) {
        pptProjectService.deleteProject(AuthContext.get().userId(), bindingId);
        return ApiResponse.success(null);
    }

    @PostMapping("/renovation")
    public ApiResponse<PptProjectCreatedResponse> renovation(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(required = false) String clientRequestId) {
        return ApiResponse.success(pptProjectService.createRenovation(AuthContext.get().userId(), file, clientRequestId));
    }

    @PostMapping("/{bindingId}/generate/outline")
    public ApiResponse<Object> generateOutline(@PathVariable Long bindingId,
                                               @RequestParam(required = false) String clientRequestId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.generateOutline(
                AuthContext.get().userId(),
                bindingId,
                body,
                new PptStepRequest(clientRequestId)
        ));
    }

    @PostMapping("/{bindingId}/generate/descriptions")
    public ApiResponse<PptTaskResponse> generateDescriptions(@PathVariable Long bindingId,
                                                             @RequestParam(required = false) String clientRequestId) {
        return ApiResponse.success(pptProjectService.generateDescriptions(
                AuthContext.get().userId(),
                bindingId,
                new PptStepRequest(clientRequestId)
        ));
    }

    @PostMapping("/{bindingId}/generate/images")
    public ApiResponse<PptTaskResponse> generateImages(@PathVariable Long bindingId,
                                                       @RequestParam(required = false) String clientRequestId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.generateImages(
                AuthContext.get().userId(),
                bindingId,
                body,
                new PptStepRequest(clientRequestId)
        ));
    }

    @GetMapping("/{bindingId}/tasks/{taskId}")
    public ApiResponse<PptTaskResponse> task(@PathVariable Long bindingId, @PathVariable String taskId) {
        return ApiResponse.success(pptProjectService.getTask(AuthContext.get().userId(), bindingId, taskId));
    }

    @GetMapping("/{bindingId}/export/pptx")
    public ApiResponse<PptExportResponse> exportPptx(@PathVariable Long bindingId,
                                                   @RequestParam(required = false) String filename,
                                                   @RequestParam(required = false) String clientRequestId) {
        return ApiResponse.success(pptProjectService.exportPptx(
                AuthContext.get().userId(),
                bindingId,
                filename,
                new PptStepRequest(clientRequestId)
        ));
    }

    @GetMapping("/{bindingId}/export/pdf")
    public ApiResponse<PptExportResponse> exportPdf(@PathVariable Long bindingId,
                                                  @RequestParam(required = false) String filename,
                                                  @RequestParam(required = false) String clientRequestId) {
        return ApiResponse.success(pptProjectService.exportPdf(
                AuthContext.get().userId(),
                bindingId,
                filename,
                new PptStepRequest(clientRequestId)
        ));
    }

    @GetMapping("/{bindingId}/export/images")
    public ApiResponse<PptExportResponse> exportImages(@PathVariable Long bindingId,
                                                       @RequestParam(required = false) String pageIds) {
        return ApiResponse.success(pptProjectService.exportImages(
                AuthContext.get().userId(),
                bindingId,
                pageIds
        ));
    }

    @PostMapping("/{bindingId}/template")
    public ApiResponse<Object> uploadTemplate(@PathVariable Long bindingId,
                                              @RequestParam("template_image") MultipartFile templateImage) {
        return ApiResponse.success(pptProjectService.uploadTemplate(
                AuthContext.get().userId(),
                bindingId,
                templateImage
        ));
    }

    @PutMapping("/{bindingId}/pages/{pageId}/outline")
    public ApiResponse<Object> updatePageOutline(@PathVariable Long bindingId,
                                                 @PathVariable String pageId,
                                                 @RequestBody Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/pages/" + pageId + "/outline",
                "PUT",
                body
        ));
    }

    @PutMapping("/{bindingId}/pages/{pageId}/description")
    public ApiResponse<Object> updatePageDescription(@PathVariable Long bindingId,
                                                     @PathVariable String pageId,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/pages/" + pageId + "/description",
                "PUT",
                body
        ));
    }

    @PostMapping("/{bindingId}/pages/{pageId}/generate/description")
    public ApiResponse<Object> generatePageDescription(@PathVariable Long bindingId,
                                                       @PathVariable String pageId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/pages/" + pageId + "/generate/description",
                "POST",
                body
        ));
    }

    @PostMapping("/{bindingId}/pages/{pageId}/generate/image")
    public ApiResponse<Object> generatePageImage(@PathVariable Long bindingId,
                                                 @PathVariable String pageId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/pages/" + pageId + "/generate/image",
                "POST",
                body
        ));
    }

    @PostMapping("/{bindingId}/pages/{pageId}/edit/image")
    public ApiResponse<Object> editPageImage(@PathVariable Long bindingId,
                                             @PathVariable String pageId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/pages/" + pageId + "/edit/image",
                "POST",
                body
        ));
    }

    @PostMapping("/{bindingId}/refine/outline")
    public ApiResponse<Object> refineOutline(@PathVariable Long bindingId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/refine/outline",
                "POST",
                body
        ));
    }

    @PostMapping("/{bindingId}/refine/descriptions")
    public ApiResponse<Object> refineDescriptions(@PathVariable Long bindingId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(pptProjectService.proxyMutation(
                AuthContext.get().userId(),
                bindingId,
                "/refine/descriptions",
                "POST",
                body
        ));
    }
}
