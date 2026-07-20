package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicEpisode;
import com.aiminilab.aitoolmarket.comic.entity.ComicProject;
import com.aiminilab.aitoolmarket.comic.entity.ComicScene;
import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.entity.ComicShotAttempt;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotAttemptMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ComicProjectService {
    private static final Set<String> EPISODE_SOURCE_TYPES = Set.of("AI", "PASTE", "TXT", "MARKDOWN", "DOCX");

    private final ComicProjectMapper projectMapper;
    private final ComicEpisodeMapper episodeMapper;
    private final ComicShotMapper shotMapper;
    private final ComicCharacterMapper characterMapper;
    private final ComicCharacterVersionMapper characterVersionMapper;
    private final ComicSceneMapper sceneMapper;
    private final ComicSceneVersionMapper sceneVersionMapper;
    private final ComicShotAttemptMapper attemptMapper;
    private final ComicScriptImportService importService;
    private final ObjectMapper objectMapper;

    public ComicProjectService(ComicProjectMapper projectMapper, ComicEpisodeMapper episodeMapper,
                               ComicShotMapper shotMapper, ComicCharacterMapper characterMapper,
                               ComicCharacterVersionMapper characterVersionMapper, ComicSceneMapper sceneMapper,
                               ComicSceneVersionMapper sceneVersionMapper, ComicShotAttemptMapper attemptMapper,
                               ComicScriptImportService importService, ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.episodeMapper = episodeMapper;
        this.shotMapper = shotMapper;
        this.characterMapper = characterMapper;
        this.characterVersionMapper = characterVersionMapper;
        this.sceneMapper = sceneMapper;
        this.sceneVersionMapper = sceneVersionMapper;
        this.attemptMapper = attemptMapper;
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComicDtos.ProjectDetail createProject(Long userId, ComicDtos.CreateProjectRequest request) {
        LocalDateTime now = LocalDateTime.now();
        ComicProject project = new ComicProject();
        project.setUserId(userId);
        project.setTitle(request.title().trim());
        project.setDescription(trimToNull(request.description()));
        project.setAspectRatio(defaultValue(request.aspectRatio(), "16:9"));
        project.setVisualStyle(trimToNull(request.visualStyle()));
        project.setStatus("ACTIVE");
        project.setRevision(0L);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);
        return detail(userId, project.getId());
    }

    public ComicDtos.ProjectListResponse list(Long userId) {
        List<ComicDtos.ProjectSummary> projects = projectMapper.selectByUser(userId).stream()
                .map(project -> {
                    List<ComicEpisode> episodes = episodeMapper.selectByProject(project.getId());
                    return new ComicDtos.ProjectSummary(
                            project.getId(), project.getTitle(), project.getDescription(), project.getAspectRatio(),
                            project.getVisualStyle(), project.getStatus(), value(project.getRevision()), episodes.size(),
                            project.getCreatedAt(), project.getUpdatedAt(), workspacePath(project.getId())
                    );
                }).toList();
        return new ComicDtos.ProjectListResponse(projects);
    }

    public ComicDtos.ProjectDetail detail(Long userId, Long projectId) {
        ComicProject project = requireProject(userId, projectId);
        return new ComicDtos.ProjectDetail(
                project.getId(), project.getTitle(), project.getDescription(), project.getAspectRatio(),
                project.getVisualStyle(), project.getStatus(), value(project.getRevision()),
                episodeMapper.selectByProject(projectId).stream().map(this::toEpisodeSummary).toList(),
                characterMapper.selectByProject(projectId).stream().map(this::toCharacterDetail).toList(),
                sceneMapper.selectByProject(projectId).stream().map(this::toSceneDetail).toList(),
                project.getCreatedAt(), project.getUpdatedAt(), workspacePath(projectId)
        );
    }

    @Transactional
    public ComicDtos.ProjectDetail updateProject(Long userId, Long projectId,
                                                  ComicDtos.UpdateProjectRequest request) {
        requireProject(userId, projectId);
        if (projectMapper.updateMetadata(
                projectId, userId, request.expectedRevision(), request.title().trim(),
                trimToNull(request.description()), defaultValue(request.aspectRatio(), "16:9"),
                trimToNull(request.visualStyle())) != 1) {
            throw conflict("项目已被其他操作更新，请刷新后重试");
        }
        return detail(userId, projectId);
    }

    @Transactional
    public void deleteProject(Long userId, Long projectId) {
        if (projectMapper.softDelete(projectId, userId) != 1) {
            throw notFound("漫剧项目不存在");
        }
    }

    @Transactional
    public ComicDtos.EpisodeDetail createEpisode(Long userId, Long projectId,
                                                  ComicDtos.CreateEpisodeRequest request) {
        requireProjectForUpdate(userId, projectId);
        String sourceType = defaultValue(request.sourceType(), "PASTE").toUpperCase();
        if (!EPISODE_SOURCE_TYPES.contains(sourceType)) {
            throw invalid("不支持的剧本来源");
        }
        ComicEpisode episode = newEpisode(
                projectId,
                request.episodeNo() == null ? episodeMapper.maxEpisodeNo(projectId) + 1 : request.episodeNo(),
                request.title(), sourceType, null, request.scriptText()
        );
        insertEpisode(episode);
        return episodeDetail(userId, projectId, episode.getId());
    }

    @Transactional
    public ComicDtos.EpisodeDetail importEpisode(Long userId, Long projectId, MultipartFile file,
                                                  String title, Integer episodeNo) {
        requireProjectForUpdate(userId, projectId);
        ComicScriptImportService.ImportedScript imported = importService.parse(file);
        int targetNo = episodeNo == null ? episodeMapper.maxEpisodeNo(projectId) + 1 : episodeNo;
        String targetTitle = trimToNull(title);
        if (targetTitle == null) {
            targetTitle = stripExtension(imported.filename());
        }
        ComicEpisode episode = newEpisode(
                projectId, targetNo, targetTitle, imported.sourceType(), imported.filename(), imported.text()
        );
        insertEpisode(episode);
        return episodeDetail(userId, projectId, episode.getId());
    }

    public ComicDtos.EpisodeDetail episodeDetail(Long userId, Long projectId, Long episodeId) {
        ComicEpisode episode = requireEpisode(userId, projectId, episodeId);
        return toEpisodeDetail(episode);
    }

    @Transactional
    public ComicDtos.EpisodeDetail updateEpisode(Long userId, Long projectId, Long episodeId,
                                                  ComicDtos.UpdateEpisodeRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        requireStatus(episode.getStatus(), "DRAFT", "分镜锁定后不能修改剧本");
        if (episodeMapper.updateDraft(
                episodeId, request.expectedRevision(), request.title().trim(), request.scriptText().trim()) != 1) {
            throw conflict("剧集已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    @Transactional
    public ComicDtos.EpisodeDetail replaceShots(Long userId, Long projectId, Long episodeId,
                                                 ComicDtos.ReplaceShotsRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        requireStatus(episode.getStatus(), "DRAFT", "分镜已锁定，不能修改内容或顺序");
        List<ComicDtos.ShotInput> inputs = request.shots();
        validateShotSequences(inputs);
        Map<Long, ComicShot> existing = new HashMap<>();
        for (ComicShot shot : shotMapper.selectByEpisode(episodeId)) {
            existing.put(shot.getId(), shot);
        }
        Set<Long> retained = new HashSet<>();
        if (!existing.isEmpty()) {
            shotMapper.shiftSequences(episodeId);
        }
        for (int index = 0; index < inputs.size(); index++) {
            ComicDtos.ShotInput input = inputs.get(index);
            ComicShot shot;
            if (input.id() == null) {
                shot = new ComicShot();
                shot.setEpisodeId(episodeId);
                shot.setShotKey(input.shotKey() == null || input.shotKey().isBlank()
                        ? UUID.randomUUID().toString() : input.shotKey());
                shot.setStatus("DRAFT");
                shot.setRevision(0L);
                shot.setCreatedAt(LocalDateTime.now());
            } else {
                shot = existing.get(input.id());
                if (shot == null) {
                    throw invalid("分镜不属于当前剧集: " + input.id());
                }
                retained.add(shot.getId());
            }
            applyShotInput(shot, input, index + 1);
            shot.setUpdatedAt(LocalDateTime.now());
            if (shot.getId() == null) {
                shotMapper.insert(shot);
                retained.add(shot.getId());
            } else {
                shotMapper.updateDraft(shot);
            }
        }
        List<Long> removed = existing.keySet().stream().filter(id -> !retained.contains(id)).toList();
        if (!removed.isEmpty()) {
            shotMapper.deleteByIds(removed);
        }
        validateDependencies(shotMapper.selectByEpisode(episodeId));
        if (episodeMapper.touchDraft(episodeId, value(episode.getRevision())) != 1) {
            throw conflict("剧集已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    @Transactional
    public ComicDtos.EpisodeDetail lockStoryboard(Long userId, Long projectId, Long episodeId,
                                                   ComicDtos.RevisionRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        requireStatus(episode.getStatus(), "DRAFT", "只有草稿分镜可以锁定");
        List<ComicShot> shots = shotMapper.selectByEpisode(episodeId);
        if (shots.size() < 6 || shots.size() > 18) {
            throw invalid("每集必须包含 6 到 18 个分镜");
        }
        int duration = shots.stream().mapToInt(shot -> shot.getDurationMs() == null ? 0 : shot.getDurationMs()).sum();
        if (duration < 30_000 || duration > 90_000) {
            throw invalid("每集总时长必须在 30 到 90 秒之间");
        }
        validateDependencies(shots);
        if (episodeMapper.lockStoryboard(episodeId, request.expectedRevision()) != 1) {
            throw conflict("剧集已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    @Transactional
    public ComicDtos.EpisodeDetail updateAssetRefs(Long userId, Long projectId, Long episodeId, Long shotId,
                                                    ComicDtos.UpdateAssetRefsRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        requireStatus(episode.getStatus(), "STORYBOARD_LOCKED", "当前阶段不能修改参考资产");
        ComicShot shot = shotMapper.selectInEpisode(shotId, episodeId);
        if (shot == null) throw notFound("分镜不存在");
        validateAssetVersions(projectId, request.characterVersionIds(), request.sceneVersionId(), false);
        if (shotMapper.updateAssetRefs(
                shotId, episodeId, value(shot.getRevision()), writeLongList(request.characterVersionIds()),
                request.sceneVersionId()) != 1) {
            throw conflict("分镜已被其他操作更新，请刷新后重试");
        }
        if (episodeMapper.touchLocked(episodeId, request.expectedRevision()) != 1) {
            throw conflict("剧集已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    @Transactional
    public ComicDtos.EpisodeDetail confirmAssets(Long userId, Long projectId, Long episodeId,
                                                  ComicDtos.RevisionRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        requireStatus(episode.getStatus(), "STORYBOARD_LOCKED", "当前阶段不能确认参考资产");
        List<ComicShot> shots = shotMapper.selectByEpisode(episodeId);
        for (ComicShot shot : shots) {
            validateAssetVersions(projectId, readLongList(shot.getCharacterVersionIdsJson()),
                    shot.getSceneVersionId(), true);
        }
        if (episodeMapper.confirmAssets(episodeId, request.expectedRevision()) != 1) {
            throw conflict("剧集已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    @Transactional
    public ComicDtos.CharacterDetail createCharacter(Long userId, Long projectId,
                                                      ComicDtos.CreateCharacterRequest request) {
        requireProject(userId, projectId);
        ComicCharacter character = new ComicCharacter();
        character.setProjectId(projectId);
        character.setName(request.name().trim());
        character.setDescription(trimToNull(request.description()));
        character.setVoiceConfigJson(writeJson(request.voiceConfig()));
        character.setStatus("ACTIVE");
        character.setCreatedAt(LocalDateTime.now());
        character.setUpdatedAt(LocalDateTime.now());
        try {
            characterMapper.insert(character);
        } catch (DuplicateKeyException exception) {
            throw invalid("角色名称已存在");
        }
        return toCharacterDetail(character);
    }

    @Transactional
    public ComicDtos.CharacterVersionDetail createCharacterVersion(
            Long userId, Long projectId, Long characterId, ComicDtos.CreateCharacterVersionRequest request) {
        requireProject(userId, projectId);
        ComicCharacter character = characterMapper.selectById(characterId);
        if (character == null || !projectId.equals(character.getProjectId())) throw notFound("角色不存在");
        String status = normalizeAssetStatus(request.status());
        if ("READY".equals(status) && (blank(request.frontImageUrl()) || blank(request.sideImageUrl())
                || blank(request.backImageUrl()))) {
            throw invalid("可用的角色版本必须包含正面、侧面和背面三视图");
        }
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setCharacterId(characterId);
        version.setVersionNo(characterVersionMapper.maxVersionNo(characterId) + 1);
        version.setVisualPrompt(request.visualPrompt().trim());
        version.setFrontImageUrl(trimToNull(request.frontImageUrl()));
        version.setSideImageUrl(trimToNull(request.sideImageUrl()));
        version.setBackImageUrl(trimToNull(request.backImageUrl()));
        version.setStatus(status);
        version.setCreatedAt(LocalDateTime.now());
        characterVersionMapper.insert(version);
        return toCharacterVersionDetail(version);
    }

    @Transactional
    public ComicDtos.SceneDetail createScene(Long userId, Long projectId, ComicDtos.CreateSceneRequest request) {
        requireProject(userId, projectId);
        ComicScene scene = new ComicScene();
        scene.setProjectId(projectId);
        scene.setName(request.name().trim());
        scene.setDescription(trimToNull(request.description()));
        scene.setStatus("ACTIVE");
        scene.setCreatedAt(LocalDateTime.now());
        scene.setUpdatedAt(LocalDateTime.now());
        try {
            sceneMapper.insert(scene);
        } catch (DuplicateKeyException exception) {
            throw invalid("场景名称已存在");
        }
        return toSceneDetail(scene);
    }

    @Transactional
    public ComicDtos.SceneVersionDetail createSceneVersion(
            Long userId, Long projectId, Long sceneId, ComicDtos.CreateSceneVersionRequest request) {
        requireProject(userId, projectId);
        ComicScene scene = sceneMapper.selectById(sceneId);
        if (scene == null || !projectId.equals(scene.getProjectId())) throw notFound("场景不存在");
        String status = normalizeAssetStatus(request.status());
        if ("READY".equals(status) && blank(request.anchorImageUrl())) {
            throw invalid("可用的场景版本必须包含锚点图");
        }
        ComicSceneVersion version = new ComicSceneVersion();
        version.setSceneId(sceneId);
        version.setVersionNo(sceneVersionMapper.maxVersionNo(sceneId) + 1);
        version.setVisualPrompt(request.visualPrompt().trim());
        version.setAnchorImageUrl(trimToNull(request.anchorImageUrl()));
        version.setStatus(status);
        version.setCreatedAt(LocalDateTime.now());
        sceneVersionMapper.insert(version);
        return toSceneVersionDetail(version);
    }

    @Transactional
    public ComicDtos.EpisodeDetail selectAttempt(Long userId, Long projectId, Long episodeId, Long shotId,
                                                  ComicDtos.SelectAttemptRequest request) {
        ComicEpisode episode = requireEpisodeForUpdate(userId, projectId, episodeId);
        if (!Set.of("ASSETS_CONFIRMED", "GENERATING", "COMPLETED").contains(episode.getStatus())) {
            throw invalid("当前阶段不能选择分镜版本");
        }
        ComicShot shot = shotMapper.selectInEpisode(shotId, episodeId);
        if (shot == null) throw notFound("分镜不存在");
        ComicShotAttempt attempt = attemptMapper.selectForShot(request.attemptId(), shotId);
        if (attempt == null || !"SUCCESS".equals(attempt.getStatus())) {
            throw invalid("只能选择当前分镜已成功的生成版本");
        }
        if (shotMapper.selectAttempt(
                shotId, episodeId, attempt.getId(), request.expectedRevision()) != 1) {
            throw conflict("分镜已被其他操作更新，请刷新后重试");
        }
        return episodeDetail(userId, projectId, episodeId);
    }

    public ObjectNode buildShotOperationInput(Long userId, Long projectId, Long episodeId, Long shotId) {
        ComicProject project = requireProject(userId, projectId);
        ComicEpisode episode = requireEpisode(userId, projectId, episodeId);
        ComicShot shot = shotMapper.selectInEpisode(shotId, episodeId);
        if (shot == null) throw notFound("分镜不存在");
        ObjectNode input = objectMapper.createObjectNode();
        input.put("productionMode", "SHOT_VIDEO");
        input.put("operationHandlerKey", "comic.shot_video");
        input.put("comicProjectId", projectId);
        input.put("comicEpisodeId", episodeId);
        input.put("comicShotId", shotId);
        input.put("aspectRatio", project.getAspectRatio());
        input.put("visualStyle", project.getVisualStyle());
        input.set("shot", objectMapper.valueToTree(toShotDetail(shot)));
        ArrayNode refs = input.putArray("referenceAssetVersions");
        for (Long versionId : readLongList(shot.getCharacterVersionIdsJson())) {
            ComicCharacterVersion version = characterVersionMapper.selectById(versionId);
            if (version != null) {
                ObjectNode ref = refs.addObject();
                ref.put("assetType", "CHARACTER");
                ref.put("versionId", version.getId());
                ref.put("visualPrompt", version.getVisualPrompt());
                ArrayNode urls = ref.putArray("imageUrls");
                addIfPresent(urls, version.getFrontImageUrl());
                addIfPresent(urls, version.getSideImageUrl());
                addIfPresent(urls, version.getBackImageUrl());
            }
        }
        if (shot.getSceneVersionId() != null) {
            ComicSceneVersion version = sceneVersionMapper.selectById(shot.getSceneVersionId());
            if (version != null) {
                ObjectNode ref = refs.addObject();
                ref.put("assetType", "SCENE");
                ref.put("versionId", version.getId());
                ref.put("visualPrompt", version.getVisualPrompt());
                ArrayNode urls = ref.putArray("imageUrls");
                addIfPresent(urls, version.getAnchorImageUrl());
            }
        }
        input.put("episodeTitle", episode.getTitle());
        return input;
    }

    ComicProject requireProject(Long userId, Long projectId) {
        ComicProject project = projectMapper.selectOwned(projectId, userId);
        if (project == null) throw notFound("漫剧项目不存在");
        return project;
    }

    ComicEpisode requireEpisode(Long userId, Long projectId, Long episodeId) {
        ComicEpisode episode = episodeMapper.selectOwned(projectId, episodeId, userId);
        if (episode == null) throw notFound("漫剧剧集不存在");
        return episode;
    }

    private ComicProject requireProjectForUpdate(Long userId, Long projectId) {
        ComicProject project = projectMapper.selectOwnedForUpdate(projectId, userId);
        if (project == null) throw notFound("漫剧项目不存在");
        return project;
    }

    private ComicEpisode requireEpisodeForUpdate(Long userId, Long projectId, Long episodeId) {
        ComicEpisode episode = episodeMapper.selectOwnedForUpdate(projectId, episodeId, userId);
        if (episode == null) throw notFound("漫剧剧集不存在");
        return episode;
    }

    private ComicEpisode newEpisode(Long projectId, int episodeNo, String title, String sourceType,
                                    String filename, String scriptText) {
        if (episodeNo <= 0) throw invalid("集数必须是正整数");
        ComicEpisode episode = new ComicEpisode();
        episode.setProjectId(projectId);
        episode.setEpisodeNo(episodeNo);
        episode.setTitle(title.trim());
        episode.setScriptSourceType(sourceType);
        episode.setScriptFileName(filename);
        episode.setScriptText(scriptText.trim());
        episode.setStatus("DRAFT");
        episode.setRevision(0L);
        episode.setCreatedAt(LocalDateTime.now());
        episode.setUpdatedAt(LocalDateTime.now());
        return episode;
    }

    private void insertEpisode(ComicEpisode episode) {
        try {
            episodeMapper.insert(episode);
        } catch (DuplicateKeyException exception) {
            throw invalid("该集数已经存在");
        }
    }

    private void applyShotInput(ComicShot shot, ComicDtos.ShotInput input, int fallbackSequence) {
        shot.setSequenceNo(input.sequenceNo() == null ? fallbackSequence : input.sequenceNo());
        shot.setDurationMs(input.durationMs());
        shot.setShotScale(trimToNull(input.shotScale()));
        shot.setCameraAngle(trimToNull(input.cameraAngle()));
        shot.setCameraMovement(trimToNull(input.cameraMovement()));
        shot.setEmotion(trimToNull(input.emotion()));
        shot.setVisualDescription(input.visualDescription().trim());
        shot.setDialogue(trimToNull(input.dialogue()));
        shot.setNarration(trimToNull(input.narration()));
        shot.setSoundEffect(trimToNull(input.soundEffect()));
        shot.setBgmCue(trimToNull(input.bgmCue()));
        shot.setFirstFramePrompt(trimToNull(input.firstFramePrompt()));
        shot.setVideoPrompt(trimToNull(input.videoPrompt()));
        shot.setNegativePrompt(trimToNull(input.negativePrompt()));
        shot.setCharacterVersionIdsJson(writeLongList(input.characterVersionIds()));
        shot.setSceneVersionId(input.sceneVersionId());
        shot.setDependsOnShotId(input.dependsOnShotId());
    }

    private void validateShotSequences(List<ComicDtos.ShotInput> inputs) {
        Set<Integer> sequences = new HashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            Integer sequence = inputs.get(index).sequenceNo() == null ? index + 1 : inputs.get(index).sequenceNo();
            if (!sequences.add(sequence)) throw invalid("分镜顺序不能重复");
        }
        for (int sequence = 1; sequence <= inputs.size(); sequence++) {
            if (!sequences.contains(sequence)) throw invalid("分镜顺序必须从 1 连续排列");
        }
    }

    private void validateDependencies(List<ComicShot> shots) {
        Map<Long, Integer> positions = new HashMap<>();
        for (ComicShot shot : shots) positions.put(shot.getId(), shot.getSequenceNo());
        for (ComicShot shot : shots) {
            if (shot.getDependsOnShotId() == null) continue;
            Integer dependency = positions.get(shot.getDependsOnShotId());
            if (dependency == null || dependency >= shot.getSequenceNo()) {
                throw invalid("连续动作只能依赖同一剧集中更早的分镜");
            }
        }
    }

    private void validateAssetVersions(Long projectId, List<Long> characterVersionIds,
                                       Long sceneVersionId, boolean requireReady) {
        for (Long versionId : new LinkedHashSet<>(characterVersionIds == null ? List.of() : characterVersionIds)) {
            ComicCharacterVersion version = characterVersionMapper.selectById(versionId);
            ComicCharacter character = version == null ? null : characterMapper.selectById(version.getCharacterId());
            if (character == null || !projectId.equals(character.getProjectId())) {
                throw invalid("角色版本不属于当前项目: " + versionId);
            }
            if (requireReady && !"READY".equals(version.getStatus())) {
                throw invalid("角色三视图尚未准备完成: " + versionId);
            }
        }
        if (sceneVersionId == null) {
            if (requireReady) throw invalid("每个分镜都必须选择场景锚点图");
            return;
        }
        ComicSceneVersion version = sceneVersionMapper.selectById(sceneVersionId);
        ComicScene scene = version == null ? null : sceneMapper.selectById(version.getSceneId());
        if (scene == null || !projectId.equals(scene.getProjectId())) {
            throw invalid("场景版本不属于当前项目");
        }
        if (requireReady && !"READY".equals(version.getStatus())) {
            throw invalid("场景锚点图尚未准备完成");
        }
    }

    private ComicDtos.EpisodeSummary toEpisodeSummary(ComicEpisode episode) {
        List<ComicShot> shots = shotMapper.selectByEpisode(episode.getId());
        return new ComicDtos.EpisodeSummary(
                episode.getId(), episode.getEpisodeNo(), episode.getTitle(), episode.getScriptSourceType(),
                episode.getStatus(), value(episode.getRevision()), shots.size(),
                shots.stream().mapToInt(shot -> shot.getDurationMs() == null ? 0 : shot.getDurationMs()).sum(),
                episode.getStoryboardLockedAt(), episode.getAssetsConfirmedAt(),
                episode.getCreatedAt(), episode.getUpdatedAt()
        );
    }

    private ComicDtos.EpisodeDetail toEpisodeDetail(ComicEpisode episode) {
        return new ComicDtos.EpisodeDetail(
                episode.getId(), episode.getProjectId(), episode.getEpisodeNo(), episode.getTitle(),
                episode.getScriptSourceType(), episode.getScriptFileName(), episode.getScriptText(),
                episode.getStatus(), value(episode.getRevision()),
                shotMapper.selectByEpisode(episode.getId()).stream().map(this::toShotDetail).toList(),
                episode.getStoryboardLockedAt(), episode.getAssetsConfirmedAt(),
                episode.getCreatedAt(), episode.getUpdatedAt()
        );
    }

    private ComicDtos.ShotDetail toShotDetail(ComicShot shot) {
        return new ComicDtos.ShotDetail(
                shot.getId(), shot.getShotKey(), shot.getSequenceNo(), shot.getDurationMs(), shot.getShotScale(),
                shot.getCameraAngle(), shot.getCameraMovement(), shot.getEmotion(), shot.getVisualDescription(),
                shot.getDialogue(), shot.getNarration(), shot.getSoundEffect(), shot.getBgmCue(),
                shot.getFirstFramePrompt(), shot.getVideoPrompt(), shot.getNegativePrompt(),
                readLongList(shot.getCharacterVersionIdsJson()), shot.getSceneVersionId(),
                shot.getDependsOnShotId(), shot.getSelectedAttemptId(), shot.getStatus(), value(shot.getRevision())
        );
    }

    private ComicDtos.CharacterDetail toCharacterDetail(ComicCharacter character) {
        return new ComicDtos.CharacterDetail(
                character.getId(), character.getName(), character.getDescription(), readJson(character.getVoiceConfigJson()),
                character.getStatus(), characterVersionMapper.selectByCharacter(character.getId()).stream()
                .map(this::toCharacterVersionDetail).toList()
        );
    }

    private ComicDtos.CharacterVersionDetail toCharacterVersionDetail(ComicCharacterVersion version) {
        return new ComicDtos.CharacterVersionDetail(
                version.getId(), version.getVersionNo(), version.getVisualPrompt(), version.getFrontImageUrl(),
                version.getSideImageUrl(), version.getBackImageUrl(), version.getStatus(), version.getCreatedAt()
        );
    }

    private ComicDtos.SceneDetail toSceneDetail(ComicScene scene) {
        return new ComicDtos.SceneDetail(
                scene.getId(), scene.getName(), scene.getDescription(), scene.getStatus(),
                sceneVersionMapper.selectByScene(scene.getId()).stream().map(this::toSceneVersionDetail).toList()
        );
    }

    private ComicDtos.SceneVersionDetail toSceneVersionDetail(ComicSceneVersion version) {
        return new ComicDtos.SceneVersionDetail(
                version.getId(), version.getVersionNo(), version.getVisualPrompt(),
                version.getAnchorImageUrl(), version.getStatus(), version.getCreatedAt()
        );
    }

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid character version list", exception);
        }
    }

    private String writeLongList(List<Long> values) {
        try {
            List<Long> normalized = values == null ? List.of() : values.stream()
                    .filter(Objects::nonNull).distinct().toList();
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize character version list", exception);
        }
    }

    private String writeJson(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize JSON", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JSON", exception);
        }
    }

    private void addIfPresent(ArrayNode array, String value) {
        if (value != null && !value.isBlank()) array.add(value);
    }

    private String normalizeAssetStatus(String status) {
        String value = defaultValue(status, "DRAFT").toUpperCase();
        if (!Set.of("DRAFT", "GENERATING", "READY", "FAILED").contains(value)) {
            throw invalid("不支持的资产状态");
        }
        return value;
    }

    private void requireStatus(String actual, String expected, String message) {
        if (!expected.equals(actual)) throw invalid(message);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String workspacePath(Long projectId) {
        return "/agents/comic-projects/" + projectId;
    }

    private String defaultValue(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }
}
