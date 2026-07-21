import { apiRequest } from "./client"
import type { RequestOptions } from "./client"

export const COMIC_PROJECT_API_BASE = "/api/v1/agents/comic-projects"

export type ComicEntityId = string | number
export type ComicEpisodeSourceType = "AI" | "PASTE" | "TXT" | "MD" | "MARKDOWN" | "DOCX"
export type ComicAssetStatus = "DRAFT" | "GENERATING" | "READY" | "FAILED"
export type ComicBatchStatus =
  | "CREATED"
  | "DRAFT"
  | "QUEUED"
  | "DISPATCHING"
  | "RUNNING"
  | "AWAITING_USER"
  | "AWAITING_FUNDS"
  | "CANCELLING"
  | "PARTIAL_FAILED"
  | "PARTIAL_SUCCESS"
  | "SUCCESS"
  | "FAILED"
  | "CANCELLED"
  | "TIMEOUT"
export type ComicAttemptStatus = "PENDING" | "QUEUED" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLED"

export interface ComicProjectSummary {
  id: number
  title: string
  description?: string | null
  aspectRatio?: string | null
  visualStyle?: string | null
  status?: string | null
  coverUrl?: string | null
  episodeCount?: number | null
  currentEpisodeId?: number | null
  currentStage?: string | null
  revision?: number | null
  workspacePath?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ComicProjectPage {
  projects: ComicProjectSummary[]
}

export interface ComicProjectDetail extends ComicProjectSummary {
  episodes?: ComicEpisodeSummary[] | null
  characters?: ComicCharacter[] | null
  scenes?: ComicScene[] | null
}

export interface ComicWorkspaceBinding {
  projectId: number
  episodeId?: number | null
  shotId?: number | null
  rootTaskId: number
  workflowRunId: number
  launchSource?: string | null
  workspacePath: string
}

export interface ComicEpisodeSummary {
  id: number
  projectId: number
  episodeNo?: number | null
  title: string
  sourceType?: ComicEpisodeSourceType | string | null
  scriptSourceType?: ComicEpisodeSourceType | string | null
  status?: string | null
  stage?: string | null
  revision?: number | null
  storyboardLocked?: boolean | null
  assetsConfirmed?: boolean | null
  shotCount?: number | null
  totalDurationMs?: number | null
  durationMs?: number | null
  storyboardLockedAt?: string | null
  assetsConfirmedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ComicEpisodeDetail extends ComicEpisodeSummary {
  scriptFileName?: string | null
  scriptText?: string | null
  shots?: ComicShot[] | null
  characters?: ComicCharacter[] | null
  scenes?: ComicScene[] | null
  latestGenerationBatch?: ComicGenerationBatch | null
  latestAssemblyBatch?: ComicAssemblyBatch | null
}

export interface ComicShot {
  id?: number | null
  shotKey?: string | null
  sequenceNo: number
  durationMs: number
  shotScale?: string | null
  cameraAngle?: string | null
  cameraMovement?: string | null
  emotion?: string | null
  visualDescription: string
  dialogue?: string | null
  narration?: string | null
  soundEffect?: string | null
  bgmCue?: string | null
  firstFramePrompt?: string | null
  videoPrompt?: string | null
  negativePrompt?: string | null
  characterVersionIds?: number[] | null
  sceneVersionId?: number | null
  dependsOnShotId?: number | null
  selectedAttemptId?: number | null
  selectedAttempt?: ComicShotAttempt | null
}

export interface ComicCharacterVersion {
  id: number
  characterId?: number | null
  versionNo?: number | null
  visualPrompt?: string | null
  frontImageUrl?: string | null
  sideImageUrl?: string | null
  backImageUrl?: string | null
  status?: ComicAssetStatus | string | null
  createdAt?: string | null
}

export interface ComicCharacter {
  id: number
  projectId?: number | null
  name: string
  description?: string | null
  voiceConfig?: Record<string, unknown> | string | null
  status?: ComicAssetStatus | string | null
  selectedVersionId?: number | null
  versions?: ComicCharacterVersion[] | null
}

export interface ComicSceneVersion {
  id: number
  sceneId?: number | null
  versionNo?: number | null
  visualPrompt?: string | null
  anchorImageUrl?: string | null
  status?: ComicAssetStatus | string | null
  createdAt?: string | null
}

export interface ComicScene {
  id: number
  projectId?: number | null
  name: string
  description?: string | null
  status?: ComicAssetStatus | string | null
  selectedVersionId?: number | null
  versions?: ComicSceneVersion[] | null
}

export interface ComicShotAttempt {
  id: number
  shotId: number
  attemptNo: number
  status: ComicAttemptStatus | string
  workflowRunId?: number | null
  rootTaskId?: number | null
  outputJson?: string | Record<string, unknown> | null
  result?: Record<string, unknown> | null
  errorCode?: string | null
  errorMessage?: string | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface ComicGenerationBatch {
  id: number
  episodeId: number
  status: ComicBatchStatus | string
  toolCode?: string | null
  clientRequestId?: string | null
  maxParallelism?: number | null
  totalCount?: number | null
  pendingCount?: number | null
  runningCount?: number | null
  succeededCount?: number | null
  failedCount?: number | null
  attempts?: ComicShotAttempt[] | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface ComicAssemblyBatch {
  id: number
  projectId?: number | null
  episodeId: number
  status: ComicBatchStatus | string
  toolCode?: string | null
  clientRequestId?: string | null
  shotCount?: number | null
  selectedAttemptIds?: number[] | null
  workflowRunId?: number | null
  rootTaskId?: number | null
  progress?: number | null
  finalVideoUrl?: string | null
  videoUrl?: string | null
  subtitleUrl?: string | null
  result?: Record<string, unknown> | null
  errorCode?: string | null
  errorMessage?: string | null
  confirmedAt?: string | null
  startedAt?: string | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface CreateComicProjectRequest {
  title: string
  description?: string
  aspectRatio?: string
  visualStyle?: string
}

export interface UpdateComicProjectRequest extends CreateComicProjectRequest {
  status?: string
  expectedRevision: number
}

export interface CreateComicEpisodeRequest {
  title: string
  scriptText: string
  sourceType?: ComicEpisodeSourceType | string
  episodeNo?: number
}

export interface GenerateComicEpisodeRequest {
  title: string
  prompt: string
  episodeNo?: number
  clientRequestId: string
}

export interface GenerateComicStoryboardRequest {
  expectedRevision: number
  clientRequestId: string
}

export interface UpdateComicEpisodeRequest {
  title: string
  scriptText: string
  expectedRevision: number
}

export interface ReplaceComicShotsRequest {
  shots: ComicShot[]
}

export interface PatchComicShotAssetRefsRequest {
  characterVersionIds: number[]
  sceneVersionId?: number | null
  expectedRevision: number
}

export interface CreateComicGenerationBatchRequest {
  clientRequestId: string
  toolCode?: string
  maxParallelism?: number
  estimatedCredits?: number
  confirmed: true
  shotIds?: number[]
}

export interface RetryComicShotRequest {
  clientRequestId: string
  toolCode?: string
}

export interface GenerateComicAssetVersionRequest {
  clientRequestId: string
}

export interface CreateComicAssemblyBatchRequest {
  clientRequestId: string
  toolCode?: string
  confirmed: true
}

type ComicRequest = <T>(
  method: string,
  path: string,
  options?: RequestOptions & { body?: unknown },
) => Promise<T>

function encoded(value: ComicEntityId): string {
  return encodeURIComponent(String(value))
}

function projectPath(projectId: ComicEntityId): string {
  return `${COMIC_PROJECT_API_BASE}/${encoded(projectId)}`
}

function episodePath(projectId: ComicEntityId, episodeId: ComicEntityId): string {
  return `${projectPath(projectId)}/episodes/${encoded(episodeId)}`
}

function normalizeEpisodeSummary<T extends ComicEpisodeSummary>(episode: T): T {
  return {
    ...episode,
    sourceType: episode.sourceType ?? episode.scriptSourceType,
    storyboardLocked: episode.storyboardLocked ?? Boolean(episode.storyboardLockedAt),
    assetsConfirmed: episode.assetsConfirmed ?? Boolean(episode.assetsConfirmedAt),
    totalDurationMs: episode.totalDurationMs ?? episode.durationMs,
  }
}

function normalizeEpisodeDetail(episode: ComicEpisodeDetail): ComicEpisodeDetail {
  return {
    ...normalizeEpisodeSummary(episode),
    latestAssemblyBatch: episode.latestAssemblyBatch
      ? normalizeAssemblyBatch(episode.latestAssemblyBatch)
      : episode.latestAssemblyBatch,
  }
}

function normalizeAssemblyBatch(batch: ComicAssemblyBatch): ComicAssemblyBatch {
  const resultVideoUrl = typeof batch.result?.finalVideoUrl === "string"
    ? batch.result.finalVideoUrl
    : typeof batch.result?.videoUrl === "string"
      ? batch.result.videoUrl
      : null
  const resultSubtitleUrl = typeof batch.result?.subtitleUrl === "string"
    ? batch.result.subtitleUrl
    : null
  const finalVideoUrl = batch.finalVideoUrl ?? resultVideoUrl
  return {
    ...batch,
    finalVideoUrl,
    videoUrl: batch.videoUrl ?? finalVideoUrl,
    subtitleUrl: batch.subtitleUrl ?? resultSubtitleUrl,
  }
}

function normalizeProject(project: ComicProjectDetail): ComicProjectDetail {
  return {
    ...project,
    episodes: project.episodes?.map((episode) => normalizeEpisodeSummary(episode)) ?? [],
  }
}

export function createComicProjectApi(request: ComicRequest = apiRequest) {
  return {
    list(options?: RequestOptions): Promise<ComicProjectPage> {
      return request<ComicProjectPage>("GET", COMIC_PROJECT_API_BASE, options)
    },
    create(body: CreateComicProjectRequest, options?: RequestOptions): Promise<ComicProjectDetail> {
      return request<ComicProjectDetail>("POST", COMIC_PROJECT_API_BASE, { ...options, body }).then(normalizeProject)
    },
    get(projectId: ComicEntityId, options?: RequestOptions): Promise<ComicProjectDetail> {
      return request<ComicProjectDetail>("GET", projectPath(projectId), options).then(normalizeProject)
    },
    getWorkspaceByRun(rootTaskId: ComicEntityId, options?: RequestOptions): Promise<ComicWorkspaceBinding> {
      return request<ComicWorkspaceBinding>("GET", `${COMIC_PROJECT_API_BASE}/by-run/${encoded(rootTaskId)}`, options)
    },
    update(projectId: ComicEntityId, body: UpdateComicProjectRequest, options?: RequestOptions): Promise<ComicProjectDetail> {
      return request<ComicProjectDetail>("PUT", projectPath(projectId), { ...options, body }).then(normalizeProject)
    },
    remove(projectId: ComicEntityId, options?: RequestOptions): Promise<void> {
      return request<void>("DELETE", projectPath(projectId), options)
    },
    createEpisode(projectId: ComicEntityId, body: CreateComicEpisodeRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${projectPath(projectId)}/episodes`, { ...options, body }).then(normalizeEpisodeDetail)
    },
    importEpisode(projectId: ComicEntityId, file: File, title?: string, episodeNo?: number, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      const body = new FormData()
      body.append("file", file)
      if (title?.trim()) body.append("title", title.trim())
      if (episodeNo != null) body.append("episodeNo", String(episodeNo))
      return request<ComicEpisodeDetail>("POST", `${projectPath(projectId)}/episodes/import`, { ...options, body }).then(normalizeEpisodeDetail)
    },
    generateEpisode(projectId: ComicEntityId, body: GenerateComicEpisodeRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${projectPath(projectId)}/episodes/generate`, { ...options, body }).then(normalizeEpisodeDetail)
    },
    getEpisode(projectId: ComicEntityId, episodeId: ComicEntityId, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("GET", episodePath(projectId, episodeId), options).then(normalizeEpisodeDetail)
    },
    updateEpisode(projectId: ComicEntityId, episodeId: ComicEntityId, body: UpdateComicEpisodeRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("PUT", episodePath(projectId, episodeId), { ...options, body }).then(normalizeEpisodeDetail)
    },
    generateStoryboard(projectId: ComicEntityId, episodeId: ComicEntityId, body: GenerateComicStoryboardRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${episodePath(projectId, episodeId)}/generate-storyboard`, {
        ...options,
        body,
      }).then(normalizeEpisodeDetail)
    },
    replaceShots(projectId: ComicEntityId, episodeId: ComicEntityId, body: ReplaceComicShotsRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("PUT", `${episodePath(projectId, episodeId)}/shots`, { ...options, body }).then(normalizeEpisodeDetail)
    },
    lockStoryboard(projectId: ComicEntityId, episodeId: ComicEntityId, expectedRevision: number, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${episodePath(projectId, episodeId)}/lock-storyboard`, {
        ...options,
        body: { expectedRevision },
      }).then(normalizeEpisodeDetail)
    },
    patchShotAssetRefs(projectId: ComicEntityId, episodeId: ComicEntityId, shotId: ComicEntityId, body: PatchComicShotAssetRefsRequest, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("PATCH", `${episodePath(projectId, episodeId)}/shots/${encoded(shotId)}/asset-refs`, { ...options, body }).then(normalizeEpisodeDetail)
    },
    createCharacter(projectId: ComicEntityId, body: { name: string; description?: string; voiceConfig?: Record<string, unknown> }, options?: RequestOptions): Promise<ComicCharacter> {
      return request<ComicCharacter>("POST", `${projectPath(projectId)}/characters`, { ...options, body })
    },
    createCharacterVersion(projectId: ComicEntityId, characterId: ComicEntityId, body: { visualPrompt: string; frontImageUrl?: string; sideImageUrl?: string; backImageUrl?: string; status?: string }, options?: RequestOptions): Promise<ComicCharacterVersion> {
      return request<ComicCharacterVersion>("POST", `${projectPath(projectId)}/characters/${encoded(characterId)}/versions`, { ...options, body })
    },
    generateCharacterVersion(projectId: ComicEntityId, characterId: ComicEntityId, versionId: ComicEntityId, body: GenerateComicAssetVersionRequest, options?: RequestOptions): Promise<unknown> {
      return request<unknown>("POST", `${projectPath(projectId)}/characters/${encoded(characterId)}/versions/${encoded(versionId)}/generate`, { ...options, body })
    },
    createScene(projectId: ComicEntityId, body: { name: string; description?: string }, options?: RequestOptions): Promise<ComicScene> {
      return request<ComicScene>("POST", `${projectPath(projectId)}/scenes`, { ...options, body })
    },
    createSceneVersion(projectId: ComicEntityId, sceneId: ComicEntityId, body: { visualPrompt: string; anchorImageUrl?: string; status?: string }, options?: RequestOptions): Promise<ComicSceneVersion> {
      return request<ComicSceneVersion>("POST", `${projectPath(projectId)}/scenes/${encoded(sceneId)}/versions`, { ...options, body })
    },
    generateSceneVersion(projectId: ComicEntityId, sceneId: ComicEntityId, versionId: ComicEntityId, body: GenerateComicAssetVersionRequest, options?: RequestOptions): Promise<unknown> {
      return request<unknown>("POST", `${projectPath(projectId)}/scenes/${encoded(sceneId)}/versions/${encoded(versionId)}/generate`, { ...options, body })
    },
    confirmAssets(projectId: ComicEntityId, episodeId: ComicEntityId, expectedRevision: number, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${episodePath(projectId, episodeId)}/confirm-assets`, {
        ...options,
        body: { expectedRevision },
      }).then(normalizeEpisodeDetail)
    },
    createGenerationBatch(projectId: ComicEntityId, episodeId: ComicEntityId, body: CreateComicGenerationBatchRequest, options?: RequestOptions): Promise<ComicGenerationBatch> {
      return request<ComicGenerationBatch>("POST", `${episodePath(projectId, episodeId)}/generation-batches`, { ...options, body })
    },
    dispatchGenerationBatch(projectId: ComicEntityId, episodeId: ComicEntityId, batchId: ComicEntityId, options?: RequestOptions): Promise<ComicGenerationBatch> {
      return request<ComicGenerationBatch>("POST", `${episodePath(projectId, episodeId)}/generation-batches/${encoded(batchId)}/dispatch`, options)
    },
    getGenerationBatch(projectId: ComicEntityId, episodeId: ComicEntityId, batchId: ComicEntityId, options?: RequestOptions): Promise<ComicGenerationBatch> {
      return request<ComicGenerationBatch>("GET", `${episodePath(projectId, episodeId)}/generation-batches/${encoded(batchId)}`, options)
    },
    getLatestGenerationBatch(projectId: ComicEntityId, episodeId: ComicEntityId, options?: RequestOptions): Promise<ComicGenerationBatch> {
      return request<ComicGenerationBatch>("GET", `${episodePath(projectId, episodeId)}/generation-batches/latest`, options)
    },
    retryShot(projectId: ComicEntityId, episodeId: ComicEntityId, shotId: ComicEntityId, body: RetryComicShotRequest, options?: RequestOptions): Promise<ComicGenerationBatch> {
      return request<ComicGenerationBatch>("POST", `${episodePath(projectId, episodeId)}/shots/${encoded(shotId)}/retry`, { ...options, body })
    },
    selectShotAttempt(projectId: ComicEntityId, episodeId: ComicEntityId, shotId: ComicEntityId, attemptId: ComicEntityId, expectedRevision: number, options?: RequestOptions): Promise<ComicEpisodeDetail> {
      return request<ComicEpisodeDetail>("POST", `${episodePath(projectId, episodeId)}/shots/${encoded(shotId)}/select-attempt`, {
        ...options,
        body: { attemptId: Number(attemptId), expectedRevision },
      }).then(normalizeEpisodeDetail)
    },
    createAssemblyBatch(projectId: ComicEntityId, episodeId: ComicEntityId, body: CreateComicAssemblyBatchRequest, options?: RequestOptions): Promise<ComicAssemblyBatch> {
      const requestBody: CreateComicAssemblyBatchRequest = {
        clientRequestId: body.clientRequestId,
        ...(body.toolCode ? { toolCode: body.toolCode } : {}),
        confirmed: body.confirmed,
      }
      return request<ComicAssemblyBatch>("POST", `${episodePath(projectId, episodeId)}/assembly-batches`, { ...options, body: requestBody }).then(normalizeAssemblyBatch)
    },
    getAssemblyBatch(projectId: ComicEntityId, episodeId: ComicEntityId, batchId: ComicEntityId, options?: RequestOptions): Promise<ComicAssemblyBatch> {
      return request<ComicAssemblyBatch>("GET", `${episodePath(projectId, episodeId)}/assembly-batches/${encoded(batchId)}`, options).then(normalizeAssemblyBatch)
    },
    getLatestAssemblyBatch(projectId: ComicEntityId, episodeId: ComicEntityId, options?: RequestOptions): Promise<ComicAssemblyBatch> {
      return request<ComicAssemblyBatch>("GET", `${episodePath(projectId, episodeId)}/assembly-batches/latest`, options).then(normalizeAssemblyBatch)
    },
  }
}

export const comicProjectApi = createComicProjectApi()
