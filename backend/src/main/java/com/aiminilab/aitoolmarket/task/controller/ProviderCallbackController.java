package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.task.service.ProviderCallbackService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/provider-callbacks")
public class ProviderCallbackController {
    private final ProviderCallbackService providerCallbackService;

    public ProviderCallbackController(ProviderCallbackService providerCallbackService) {
        this.providerCallbackService = providerCallbackService;
    }

    @PostMapping("/suno/music/{callbackToken}")
    public ResponseEntity<Map<String, String>> receiveSuno(
            @PathVariable String callbackToken,
            @RequestBody JsonNode payload
    ) {
        providerCallbackService.receiveSuno(callbackToken, payload);
        return ResponseEntity.ok(Map.of("status", "received"));
    }
}
