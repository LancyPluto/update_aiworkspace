package com.aiminilab.aitoolmarket.task.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.EstimateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.RegenerateTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.TaskDetailResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskEstimateResponse;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private static final long TASK_EVENT_TIMEOUT_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED", "TIMEOUT", "CANCELLED");
    private static final ExecutorService TASK_EVENT_EXECUTOR = Executors.newCachedThreadPool();

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<TaskStatusResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(taskService.create(AuthContext.get().userId(), request));
    }

    @PostMapping("/estimate")
    public ApiResponse<TaskEstimateResponse> estimate(@Valid @RequestBody EstimateTaskRequest request) {
        return ApiResponse.success(taskService.estimate(AuthContext.get().userId(), request));
    }

    @GetMapping("/{taskId}/status")
    public ApiResponse<TaskStatusResponse> status(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.status(AuthContext.get().userId(), taskId));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long taskId) {
        Long userId = AuthContext.get().userId();
        SseEmitter emitter = new SseEmitter(TASK_EVENT_TIMEOUT_MILLIS);
        TASK_EVENT_EXECUTOR.execute(() -> streamStatus(userId, taskId, emitter));
        return emitter;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.detail(AuthContext.get().userId(), taskId));
    }

    @GetMapping
    public ApiResponse<PageResponse<TaskDetailResponse>> list(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String toolCode,
                                                              @RequestParam(required = false) Integer pageNo,
                                                              @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(taskService.list(AuthContext.get().userId(), status, toolCode, pageNo, pageSize));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskStatusResponse> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.cancel(AuthContext.get().userId(), taskId));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> delete(@PathVariable Long taskId) {
        taskService.delete(AuthContext.get().userId(), taskId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/regenerate")
    public ApiResponse<TaskStatusResponse> regenerate(@PathVariable Long taskId,
                                                      @Valid @RequestBody RegenerateTaskRequest request) {
        return ApiResponse.success(taskService.regenerate(AuthContext.get().userId(), taskId, request));
    }

    private void streamStatus(Long userId, Long taskId, SseEmitter emitter) {
        try {
            TaskStatusResponse previous = null;
            while (true) {
                TaskStatusResponse current = taskService.status(userId, taskId);
                if (previous == null || changed(previous, current)) {
                    emitter.send(SseEmitter.event()
                            .name("task-progress")
                            .data(current));
                    previous = current;
                }
                if (TERMINAL_STATUSES.contains(current.status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(1000L);
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(exception);
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private boolean changed(TaskStatusResponse previous, TaskStatusResponse current) {
        return !previous.status().equals(current.status())
                || !java.util.Objects.equals(previous.progress(), current.progress())
                || !java.util.Objects.equals(previous.progressMessage(), current.progressMessage());
    }
}
