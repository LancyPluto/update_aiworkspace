package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.dto.ExecutionContextResponse;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverResponse;
import com.aiminilab.aitoolmarket.task.routing.ModelRoutingService;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/internal/v1/tasks")
public class InternalTaskRouteController {
    private final ModelRoutingService routingService;
    private final InternalTaskService internalTaskService;

    public InternalTaskRouteController(ModelRoutingService routingService,
                                       InternalTaskService internalTaskService) {
        this.routingService = routingService;
        this.internalTaskService = internalTaskService;
    }

    @PostMapping("/{taskId}/route-failover")
    @Transactional
    public ApiResponse<RouteFailoverResponse> failover(@PathVariable Long taskId,
                                                       @RequestBody RouteFailoverRequest request) {
        ModelRoutingService.FailoverDecision decision = routingService.failover(taskId, request);
        ExecutionContextResponse executionContext = decision.switched()
                ? internalTaskService.executionContext(taskId)
                : null;
        return ApiResponse.success(new RouteFailoverResponse(
                decision.switched(), decision.reason(), decision.routeAttemptId(), executionContext
        ));
    }
}
