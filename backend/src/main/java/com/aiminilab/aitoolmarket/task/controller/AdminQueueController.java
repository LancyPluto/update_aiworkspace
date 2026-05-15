package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.service.QueueOperationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/queues")
public class AdminQueueController {

    private final QueueOperationsService queueOperationsService;

    public AdminQueueController(QueueOperationsService queueOperationsService) {
        this.queueOperationsService = queueOperationsService;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(queueOperationsService.stats());
    }

    @PostMapping("/dead/requeue")
    public ApiResponse<Map<String, Object>> requeueDead(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(queueOperationsService.requeueDead(limit));
    }
}
