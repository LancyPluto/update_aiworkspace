package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.ppt.service.PptEngineClient;
import com.aiminilab.aitoolmarket.ppt.service.PptProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ppt/files")
public class PptFileController {

    private final PptProjectService pptProjectService;
    private final PptEngineClient pptEngineClient;

    public PptFileController(PptProjectService pptProjectService, PptEngineClient pptEngineClient) {
        this.pptProjectService = pptProjectService;
        this.pptEngineClient = pptEngineClient;
    }

    @GetMapping("/{bindingId}/**")
    public ResponseEntity<byte[]> download(@PathVariable Long bindingId, HttpServletRequest request) {
        String prefix = "/api/v1/ppt/files/" + bindingId + "/";
        String uri = request.getRequestURI();
        String relative = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        String enginePath = pptProjectService.resolveEngineFilePath(AuthContext.get().userId(), bindingId, relative);
        byte[] content = pptEngineClient.downloadFile(enginePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
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
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".pptx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
