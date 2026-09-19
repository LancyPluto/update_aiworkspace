package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.service.AgentRunRecoveryService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/v1/agent/runs/{runId}")
public class InternalAgentRecoveryController {
    private final AgentRunRecoveryService recovery;
    private final TaskMapper tasks;
    private final TaskService taskService;
    private final com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper calls;
    public InternalAgentRecoveryController(AgentRunRecoveryService recovery, TaskMapper tasks, TaskService taskService, com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper calls) {
        this.recovery=recovery; this.tasks=tasks; this.taskService=taskService; this.calls=calls;
    }
    @PostMapping("/execution-lease/adopt")
    public ApiResponse<Map<String,Boolean>> adopt(@PathVariable Long runId, @RequestBody Map<String,String> body) {
        return ApiResponse.success(Map.of("acquired", recovery.adopt(runId,body.get("expectedOwner"),body.get("ownerToken"))));
    }
    @GetMapping("/recovery")
    public ApiResponse<Map<String,Object>> snapshot(@PathVariable Long runId) { return ApiResponse.success(recovery.snapshot(runId)); }
    @PutMapping("/recovery/runtime")
    public ApiResponse<Void> runtime(@PathVariable Long runId, @RequestBody Map<String,String> body) {
        recovery.saveRuntime(runId,body.get("runtimeJson")); return ApiResponse.success(null);
    }
    @PostMapping("/recovery/outcome")
    public ApiResponse<Void> outcome(@PathVariable Long runId, @RequestHeader("X-Agent-Execution-Owner") String owner, @RequestBody Outcome body) {
        recovery.outcome(runId,owner,body.error(),body.permanent(),body.waiting()); return ApiResponse.success(null);
    }
    @GetMapping("/recovery/tool-call")
    public ApiResponse<com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse> toolCall(
            @PathVariable Long runId, @RequestParam String idempotencyKey) {
        var call=calls.selectByRunIdAndIdempotencyKey(runId,idempotencyKey);
        return ApiResponse.success(call==null?null:com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse.from(call));
    }
    @GetMapping("/recovery/task")
    public ApiResponse<Object> task(@PathVariable Long runId, @RequestParam Long userId, @RequestParam String clientRequestId) {
        return ApiResponse.success(tasks.findByUserIdAndIdempotencyKeyIncludingDeleted(userId,clientRequestId)
                .map(t -> (Object)taskService.detail(userId,t.getId())).orElse(null));
    }
    public record Outcome(String error, boolean permanent, boolean waiting) {}
}
