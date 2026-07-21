package com.aiminilab.aitoolmarket.comic.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicAssemblyBatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicAssetGenerationService;
import com.aiminilab.aitoolmarket.comic.service.ComicBatchDispatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicGenerationBatchService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectApplicationService;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectService;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowLaunchService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/agents/comic-projects")
public class ComicProjectController {
    private final ComicProjectService projectService;
    private final ComicProjectApplicationService applicationService;
    private final ComicGenerationBatchService batchService;
    private final ComicBatchDispatchService dispatchService;
    private final ComicAssemblyBatchService assemblyBatchService;
    private final ComicAssetGenerationService assetGenerationService;
    private final ComicWorkflowLaunchService workflowLaunchService;

    public ComicProjectController(ComicProjectService projectService,
                                  ComicProjectApplicationService applicationService,
                                  ComicGenerationBatchService batchService,
                                  ComicBatchDispatchService dispatchService,
                                  ComicAssemblyBatchService assemblyBatchService,
                                  ComicAssetGenerationService assetGenerationService,
                                  ComicWorkflowLaunchService workflowLaunchService) {
        this.projectService = projectService;
        this.applicationService = applicationService;
        this.batchService = batchService;
        this.dispatchService = dispatchService;
        this.assemblyBatchService = assemblyBatchService;
        this.assetGenerationService = assetGenerationService;
        this.workflowLaunchService = workflowLaunchService;
    }

    @PostMapping
    public ApiResponse<ComicDtos.ProjectDetail> create(@Valid @RequestBody ComicDtos.CreateProjectRequest request) {
        return ApiResponse.success(projectService.createProject(userId(), request));
    }

    @GetMapping
    public ApiResponse<ComicDtos.ProjectListResponse> list() {
        return ApiResponse.success(projectService.list(userId()));
    }

    @GetMapping("/by-run/{rootTaskId}")
    public ApiResponse<ComicDtos.WorkspaceBinding> byRun(@PathVariable Long rootTaskId) {
        return ApiResponse.success(applicationService.workspaceByRootTaskId(rootTaskId, userId()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ComicDtos.ProjectDetail> detail(@PathVariable Long projectId) {
        return ApiResponse.success(projectService.detail(userId(), projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ComicDtos.ProjectDetail> update(@PathVariable Long projectId,
                                                       @Valid @RequestBody ComicDtos.UpdateProjectRequest request) {
        return ApiResponse.success(projectService.updateProject(userId(), projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId) {
        projectService.deleteProject(userId(), projectId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{projectId}/episodes")
    public ApiResponse<ComicDtos.EpisodeDetail> createEpisode(
            @PathVariable Long projectId, @Valid @RequestBody ComicDtos.CreateEpisodeRequest request) {
        return ApiResponse.success(projectService.createEpisode(userId(), projectId, request));
    }

    @PostMapping("/{projectId}/episodes/generate")
    public ApiResponse<ComicDtos.EpisodeDetail> generateEpisode(
            @PathVariable Long projectId,
            @Valid @RequestBody ComicDtos.GenerateEpisodeRequest request) {
        return ApiResponse.success(workflowLaunchService.generateScript(userId(), projectId, request));
    }

    @PostMapping(value = "/{projectId}/episodes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ComicDtos.EpisodeDetail> importEpisode(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) @Size(max = 255) String title,
            @RequestParam(required = false) @Min(1) Integer episodeNo) {
        return ApiResponse.success(projectService.importEpisode(userId(), projectId, file, title, episodeNo));
    }

    @GetMapping("/{projectId}/episodes/{episodeId}")
    public ApiResponse<ComicDtos.EpisodeDetail> episode(
            @PathVariable Long projectId, @PathVariable Long episodeId) {
        return ApiResponse.success(projectService.episodeDetail(userId(), projectId, episodeId));
    }

    @PutMapping("/{projectId}/episodes/{episodeId}")
    public ApiResponse<ComicDtos.EpisodeDetail> updateEpisode(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.UpdateEpisodeRequest request) {
        return ApiResponse.success(projectService.updateEpisode(userId(), projectId, episodeId, request));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/generate-storyboard")
    public ApiResponse<ComicDtos.EpisodeDetail> generateStoryboard(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.GenerateStoryboardRequest request) {
        return ApiResponse.success(workflowLaunchService.generateStoryboard(
                userId(), projectId, episodeId, request
        ));
    }

    @PutMapping("/{projectId}/episodes/{episodeId}/shots")
    public ApiResponse<ComicDtos.EpisodeDetail> replaceShots(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.ReplaceShotsRequest request) {
        return ApiResponse.success(projectService.replaceShots(userId(), projectId, episodeId, request));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/lock-storyboard")
    public ApiResponse<ComicDtos.EpisodeDetail> lockStoryboard(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.RevisionRequest request) {
        return ApiResponse.success(projectService.lockStoryboard(userId(), projectId, episodeId, request));
    }

    @PatchMapping("/{projectId}/episodes/{episodeId}/shots/{shotId}/asset-refs")
    public ApiResponse<ComicDtos.EpisodeDetail> updateAssetRefs(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long shotId,
            @Valid @RequestBody ComicDtos.UpdateAssetRefsRequest request) {
        return ApiResponse.success(projectService.updateAssetRefs(
                userId(), projectId, episodeId, shotId, request
        ));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/confirm-assets")
    public ApiResponse<ComicDtos.EpisodeDetail> confirmAssets(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.RevisionRequest request) {
        return ApiResponse.success(projectService.confirmAssets(userId(), projectId, episodeId, request));
    }

    @PostMapping("/{projectId}/characters")
    public ApiResponse<ComicDtos.CharacterDetail> createCharacter(
            @PathVariable Long projectId, @Valid @RequestBody ComicDtos.CreateCharacterRequest request) {
        return ApiResponse.success(projectService.createCharacter(userId(), projectId, request));
    }

    @PostMapping("/{projectId}/characters/{characterId}/versions")
    public ApiResponse<ComicDtos.CharacterVersionDetail> createCharacterVersion(
            @PathVariable Long projectId, @PathVariable Long characterId,
            @Valid @RequestBody ComicDtos.CreateCharacterVersionRequest request) {
        return ApiResponse.success(projectService.createCharacterVersion(
                userId(), projectId, characterId, request
        ));
    }

    @PostMapping("/{projectId}/characters/{characterId}/versions/{versionId}/generate")
    public ApiResponse<ComicDtos.AssetGenerationDetail> generateCharacterVersion(
            @PathVariable Long projectId, @PathVariable Long characterId, @PathVariable Long versionId,
            @Valid @RequestBody ComicDtos.GenerateAssetVersionRequest request) {
        return ApiResponse.success(assetGenerationService.generateCharacter(
                userId(), projectId, characterId, versionId, request
        ));
    }

    @PostMapping("/{projectId}/scenes")
    public ApiResponse<ComicDtos.SceneDetail> createScene(
            @PathVariable Long projectId, @Valid @RequestBody ComicDtos.CreateSceneRequest request) {
        return ApiResponse.success(projectService.createScene(userId(), projectId, request));
    }

    @PostMapping("/{projectId}/scenes/{sceneId}/versions")
    public ApiResponse<ComicDtos.SceneVersionDetail> createSceneVersion(
            @PathVariable Long projectId, @PathVariable Long sceneId,
            @Valid @RequestBody ComicDtos.CreateSceneVersionRequest request) {
        return ApiResponse.success(projectService.createSceneVersion(userId(), projectId, sceneId, request));
    }

    @PostMapping("/{projectId}/scenes/{sceneId}/versions/{versionId}/generate")
    public ApiResponse<ComicDtos.AssetGenerationDetail> generateSceneVersion(
            @PathVariable Long projectId, @PathVariable Long sceneId, @PathVariable Long versionId,
            @Valid @RequestBody ComicDtos.GenerateAssetVersionRequest request) {
        return ApiResponse.success(assetGenerationService.generateScene(
                userId(), projectId, sceneId, versionId, request
        ));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/generation-batches")
    public ApiResponse<ComicDtos.BatchDetail> createBatch(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.CreateBatchRequest request) {
        Long userId = userId();
        ComicDtos.BatchDetail created = batchService.create(userId, projectId, episodeId, request);
        return ApiResponse.success(dispatchService.dispatch(
                userId, projectId, episodeId, created.id()
        ));
    }

    @GetMapping("/{projectId}/episodes/{episodeId}/generation-batches/{batchId}")
    public ApiResponse<ComicDtos.BatchDetail> batch(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long batchId) {
        return ApiResponse.success(dispatchService.refresh(userId(), projectId, episodeId, batchId));
    }

    @GetMapping("/{projectId}/episodes/{episodeId}/generation-batches/latest")
    public ApiResponse<ComicDtos.BatchDetail> latestBatch(
            @PathVariable Long projectId, @PathVariable Long episodeId) {
        return ApiResponse.success(dispatchService.refreshLatest(userId(), projectId, episodeId));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/generation-batches/{batchId}/dispatch")
    public ApiResponse<ComicDtos.BatchDetail> dispatch(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long batchId) {
        return ApiResponse.success(dispatchService.dispatch(userId(), projectId, episodeId, batchId));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/shots/{shotId}/retry")
    public ApiResponse<ComicDtos.BatchDetail> retryShot(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long shotId,
            @Valid @RequestBody ComicDtos.RetryShotRequest request) {
        Long userId = userId();
        ComicDtos.BatchDetail created = batchService.createRetry(
                userId, projectId, episodeId, shotId, request
        );
        return ApiResponse.success(dispatchService.dispatch(
                userId, projectId, episodeId, created.id()
        ));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/shots/{shotId}/select-attempt")
    public ApiResponse<ComicDtos.EpisodeDetail> selectAttempt(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long shotId,
            @Valid @RequestBody ComicDtos.SelectAttemptRequest request) {
        return ApiResponse.success(projectService.selectAttempt(
                userId(), projectId, episodeId, shotId, request
        ));
    }

    @PostMapping("/{projectId}/episodes/{episodeId}/assembly-batches")
    public ApiResponse<ComicDtos.AssemblyBatchDetail> createAssemblyBatch(
            @PathVariable Long projectId, @PathVariable Long episodeId,
            @Valid @RequestBody ComicDtos.CreateAssemblyBatchRequest request) {
        return ApiResponse.success(assemblyBatchService.create(userId(), projectId, episodeId, request));
    }

    @GetMapping("/{projectId}/episodes/{episodeId}/assembly-batches/{batchId}")
    public ApiResponse<ComicDtos.AssemblyBatchDetail> assemblyBatch(
            @PathVariable Long projectId, @PathVariable Long episodeId, @PathVariable Long batchId) {
        return ApiResponse.success(assemblyBatchService.get(userId(), projectId, episodeId, batchId));
    }

    @GetMapping("/{projectId}/episodes/{episodeId}/assembly-batches/latest")
    public ApiResponse<ComicDtos.AssemblyBatchDetail> latestAssemblyBatch(
            @PathVariable Long projectId, @PathVariable Long episodeId) {
        return ApiResponse.success(assemblyBatchService.latest(userId(), projectId, episodeId));
    }

    private Long userId() {
        return AuthContext.get().userId();
    }
}
