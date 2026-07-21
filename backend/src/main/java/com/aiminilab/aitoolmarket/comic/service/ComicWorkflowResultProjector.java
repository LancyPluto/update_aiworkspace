package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.comic.entity.ComicCharacter;
import com.aiminilab.aitoolmarket.comic.entity.ComicCharacterVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicProjectWorkflowRun;
import com.aiminilab.aitoolmarket.comic.entity.ComicScene;
import com.aiminilab.aitoolmarket.comic.entity.ComicSceneVersion;
import com.aiminilab.aitoolmarket.comic.entity.ComicShot;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicCharacterVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicEpisodeMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicProjectWorkflowRunMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicSceneVersionMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicShotMapper;
import com.aiminilab.aitoolmarket.comic.mapper.ComicWorkflowProjectionMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ComicWorkflowResultProjector {
    private static final String SCRIPT_HANDLER = "comic.script";
    private static final String STORYBOARD_HANDLER = "comic.storyboard";
    private static final String CHARACTER_HANDLER = "comic.character_reference";
    private static final String SCENE_HANDLER = "comic.scene_reference";

    private final ComicProjectWorkflowRunMapper workflowRunMapper;
    private final ComicWorkflowProjectionMapper projectionMapper;
    private final ComicEpisodeMapper episodeMapper;
    private final ComicShotMapper shotMapper;
    private final ComicCharacterMapper characterMapper;
    private final ComicCharacterVersionMapper characterVersionMapper;
    private final ComicSceneMapper sceneMapper;
    private final ComicSceneVersionMapper sceneVersionMapper;
    private final WorkflowRunMapper workflowMapper;
    private final ObjectMapper objectMapper;

    public ComicWorkflowResultProjector(ComicProjectWorkflowRunMapper workflowRunMapper,
                                        ComicWorkflowProjectionMapper projectionMapper,
                                        ComicEpisodeMapper episodeMapper,
                                        ComicShotMapper shotMapper,
                                        ComicCharacterMapper characterMapper,
                                        ComicCharacterVersionMapper characterVersionMapper,
                                        ComicSceneMapper sceneMapper,
                                        ComicSceneVersionMapper sceneVersionMapper,
                                        WorkflowRunMapper workflowMapper,
                                        ObjectMapper objectMapper) {
        this.workflowRunMapper = workflowRunMapper;
        this.projectionMapper = projectionMapper;
        this.episodeMapper = episodeMapper;
        this.shotMapper = shotMapper;
        this.characterMapper = characterMapper;
        this.characterVersionMapper = characterVersionMapper;
        this.sceneMapper = sceneMapper;
        this.sceneVersionMapper = sceneVersionMapper;
        this.workflowMapper = workflowMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void projectSucceeded(Long workflowRunId, JsonNode context) {
        ComicProjectWorkflowRun binding = workflowRunMapper.selectByWorkflowRunInternal(workflowRunId);
        if (binding == null || projectionMapper.claim(workflowRunId, "SUCCESS") != 1) {
            return;
        }
        if (binding.getEpisodeId() != null) {
            JsonNode script = findHandlerOutput(context, SCRIPT_HANDLER);
            List<AssetCandidate> scriptCharacters = List.of();
            List<AssetCandidate> scriptScenes = List.of();
            if (script != null) {
                projectScript(binding.getEpisodeId(), script);
                scriptCharacters = scriptAssets(script, "characters", AssetType.CHARACTER);
                scriptScenes = scriptAssets(script, "locations", AssetType.SCENE);
                projectDraftAssets(binding.getProjectId(), scriptCharacters, scriptScenes);
            }
            JsonNode storyboard = findHandlerOutput(context, STORYBOARD_HANDLER);
            if (storyboard != null) {
                projectStoryboard(binding.getEpisodeId(), storyboard);
                AssetCandidates fallback = storyboardAssets(storyboard);
                projectDraftAssets(
                        binding.getProjectId(),
                        scriptCharacters.isEmpty() ? fallback.characters() : List.of(),
                        scriptScenes.isEmpty() ? fallback.scenes() : List.of()
                );
            }
        }
        JsonNode character = findHandlerOutput(context, CHARACTER_HANDLER);
        if (character != null) {
            projectCharacter(binding, character);
        }
        JsonNode scene = findHandlerOutput(context, SCENE_HANDLER);
        if (scene != null) {
            projectScene(binding, scene);
        }
        if (projectionMapper.complete(workflowRunId, "SUCCESS") != 1) {
            throw new IllegalStateException("Comic workflow projection completion lost ownership");
        }
    }

    @Transactional
    public void projectFailed(Long workflowRunId) {
        ComicProjectWorkflowRun binding = workflowRunMapper.selectByWorkflowRunInternal(workflowRunId);
        if (binding == null || projectionMapper.claim(workflowRunId, "FAILED") != 1) {
            return;
        }
        if (binding.getEpisodeId() != null) {
            episodeMapper.resetGenerationStatus(binding.getEpisodeId());
        }
        projectFailedAsset(binding);
        if (projectionMapper.complete(workflowRunId, "FAILED") != 1) {
            throw new IllegalStateException("Comic workflow failure projection lost ownership");
        }
    }

    private void projectFailedAsset(ComicProjectWorkflowRun binding) {
        WorkflowRun run = workflowMapper.selectById(binding.getWorkflowRunId());
        JsonNode input = readJson(run == null ? null : run.getInputJson());
        String assetType = firstText(input, "comicAssetType");
        Long versionId = positiveLong(input == null ? null : input.path("comicAssetVersionId"));
        if (assetType == null || versionId == null) {
            return;
        }
        if ("CHARACTER".equalsIgnoreCase(assetType)) {
            ComicCharacterVersion version = characterVersionMapper.selectById(versionId);
            ComicCharacter character = version == null ? null : characterMapper.selectById(version.getCharacterId());
            if (character == null || !binding.getProjectId().equals(character.getProjectId())) {
                throw new IllegalStateException("Character version does not belong to the comic project");
            }
            characterVersionMapper.markFailed(versionId);
            return;
        }
        if ("SCENE".equalsIgnoreCase(assetType)) {
            ComicSceneVersion version = sceneVersionMapper.selectById(versionId);
            ComicScene scene = version == null ? null : sceneMapper.selectById(version.getSceneId());
            if (scene == null || !binding.getProjectId().equals(scene.getProjectId())) {
                throw new IllegalStateException("Scene version does not belong to the comic project");
            }
            sceneVersionMapper.markFailed(versionId);
        }
    }

    private void projectScript(Long episodeId, JsonNode output) {
        JsonNode script = output.path("script");
        String scriptText = firstText(script, "screenplay", "scriptText", "text", "content");
        if (scriptText == null) {
            scriptText = firstText(output, "screenplay", "scriptText", "text", "contentText");
        }
        if (scriptText == null) {
            throw new IllegalStateException("comic.script returned no screenplay");
        }
        String title = firstText(script, "title");
        if (title == null) {
            title = firstText(output, "title");
        }
        if (episodeMapper.completeScriptProjection(
                episodeId,
                limit(title == null ? "AI 漫剧" : title, 255),
                limit(scriptText, 500_000)
        ) != 1) {
            throw new IllegalStateException("Comic script projection rejected the episode state");
        }
    }

    private void projectStoryboard(Long episodeId, JsonNode output) {
        JsonNode shots = output.path("shots");
        if (!shots.isArray() || shots.isEmpty() || shots.size() > 18) {
            throw new IllegalStateException("comic.storyboard must contain between 1 and 18 shots");
        }
        shotMapper.deleteByEpisode(episodeId);
        Set<String> usedKeys = new HashSet<>();
        String version = firstText(output, "storyboardVersionId");
        int index = 0;
        for (JsonNode value : shots) {
            index++;
            ComicShot shot = toShot(episodeId, version, value, index, usedKeys);
            shotMapper.insert(shot);
        }
        if (episodeMapper.completeStoryboardProjection(episodeId) != 1) {
            throw new IllegalStateException("Comic storyboard projection rejected the episode state");
        }
    }

    private void projectDraftAssets(Long projectId, List<AssetCandidate> characters,
                                    List<AssetCandidate> scenes) {
        for (AssetCandidate candidate : characters) {
            ensureCharacterDraft(projectId, candidate);
        }
        for (AssetCandidate candidate : scenes) {
            ensureSceneDraft(projectId, candidate);
        }
    }

    private void ensureCharacterDraft(Long projectId, AssetCandidate candidate) {
        ComicCharacter character = characterMapper.selectByNormalizedNameForUpdate(
                projectId, candidate.normalizedName()
        );
        if (character == null) {
            character = new ComicCharacter();
            character.setProjectId(projectId);
            character.setName(candidate.name());
            character.setDescription(limit(candidate.description(), 2000));
            character.setStatus("ACTIVE");
            character.setCreatedAt(LocalDateTime.now());
            character.setUpdatedAt(LocalDateTime.now());
            try {
                if (characterMapper.insert(character) != 1) {
                    throw new IllegalStateException("Could not create comic character draft asset");
                }
            } catch (DuplicateKeyException exception) {
                character = characterMapper.selectByNormalizedNameForUpdate(
                        projectId, candidate.normalizedName()
                );
                if (character == null) throw exception;
            }
        }
        if (characterVersionMapper.countReusable(character.getId()) > 0) {
            return;
        }
        ComicCharacterVersion version = new ComicCharacterVersion();
        version.setCharacterId(character.getId());
        version.setVersionNo(characterVersionMapper.maxVersionNo(character.getId()) + 1);
        version.setVisualPrompt(limit(candidate.description(), 10_000));
        version.setStatus("DRAFT");
        version.setCreatedAt(LocalDateTime.now());
        if (characterVersionMapper.insert(version) != 1) {
            throw new IllegalStateException("Could not create comic character draft version");
        }
    }

    private void ensureSceneDraft(Long projectId, AssetCandidate candidate) {
        ComicScene scene = sceneMapper.selectByNormalizedNameForUpdate(projectId, candidate.normalizedName());
        if (scene == null) {
            scene = new ComicScene();
            scene.setProjectId(projectId);
            scene.setName(candidate.name());
            scene.setDescription(limit(candidate.description(), 2000));
            scene.setStatus("ACTIVE");
            scene.setCreatedAt(LocalDateTime.now());
            scene.setUpdatedAt(LocalDateTime.now());
            try {
                if (sceneMapper.insert(scene) != 1) {
                    throw new IllegalStateException("Could not create comic scene draft asset");
                }
            } catch (DuplicateKeyException exception) {
                scene = sceneMapper.selectByNormalizedNameForUpdate(projectId, candidate.normalizedName());
                if (scene == null) throw exception;
            }
        }
        if (sceneVersionMapper.countReusable(scene.getId()) > 0) {
            return;
        }
        ComicSceneVersion version = new ComicSceneVersion();
        version.setSceneId(scene.getId());
        version.setVersionNo(sceneVersionMapper.maxVersionNo(scene.getId()) + 1);
        version.setVisualPrompt(limit(candidate.description(), 10_000));
        version.setStatus("DRAFT");
        version.setCreatedAt(LocalDateTime.now());
        if (sceneVersionMapper.insert(version) != 1) {
            throw new IllegalStateException("Could not create comic scene draft version");
        }
    }

    private List<AssetCandidate> scriptAssets(JsonNode output, String field, AssetType assetType) {
        JsonNode script = output.path("script");
        JsonNode source = script.isObject() ? script : output;
        Map<String, AssetCandidate> candidates = new LinkedHashMap<>();
        collectAssetCandidates(source.get(field), assetType, candidates);
        return List.copyOf(candidates.values());
    }

    private AssetCandidates storyboardAssets(JsonNode output) {
        JsonNode nested = output.path("storyboard");
        JsonNode source = nested.isObject() ? nested : output;
        JsonNode shots = source.path("shots");
        Map<String, AssetCandidate> characters = new LinkedHashMap<>();
        Map<String, AssetCandidate> scenes = new LinkedHashMap<>();
        if (!shots.isArray()) {
            return new AssetCandidates(List.of(), List.of());
        }
        for (JsonNode shot : shots) {
            collectAssetCandidates(shot.get("characters"), AssetType.CHARACTER, characters);
            collectAssetCandidates(shot.get("character"), AssetType.CHARACTER, characters);
            collectNamedCandidate(
                    shot, AssetType.CHARACTER, characters,
                    new String[]{"characterName"},
                    new String[]{"characterDescription", "appearance"}
            );
            collectAssetCandidates(shot.get("scenes"), AssetType.SCENE, scenes);
            collectAssetCandidates(shot.get("scene"), AssetType.SCENE, scenes);
            collectAssetCandidates(shot.get("locations"), AssetType.SCENE, scenes);
            collectAssetCandidates(shot.get("location"), AssetType.SCENE, scenes);
            collectNamedCandidate(
                    shot, AssetType.SCENE, scenes,
                    new String[]{"sceneName", "locationName"},
                    new String[]{"locationDescription", "sceneDescription"}
            );

            JsonNode references = shot.path("references");
            if (!references.isObject()) continue;
            for (String field : List.of("characters", "characterRefs", "characterReferences")) {
                collectAssetCandidates(references.get(field), AssetType.CHARACTER, characters);
            }
            for (String field : List.of("scenes", "locations", "sceneRefs", "locationRefs", "sceneReferences")) {
                collectAssetCandidates(references.get(field), AssetType.SCENE, scenes);
            }
            for (String field : List.of("assets", "referenceAssets", "assetVersions")) {
                collectTypedReferenceCandidates(references.get(field), characters, scenes);
            }
        }
        return new AssetCandidates(List.copyOf(characters.values()), List.copyOf(scenes.values()));
    }

    private void collectTypedReferenceCandidates(JsonNode value,
                                                 Map<String, AssetCandidate> characters,
                                                 Map<String, AssetCandidate> scenes) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) {
            value.forEach(item -> collectTypedReferenceCandidates(item, characters, scenes));
            return;
        }
        if (!value.isObject()) return;
        String type = firstText(value, "assetType", "type");
        if (type == null) return;
        if ("CHARACTER".equalsIgnoreCase(type) || "character".equalsIgnoreCase(type)) {
            collectAssetCandidates(value, AssetType.CHARACTER, characters);
        } else if ("SCENE".equalsIgnoreCase(type) || "location".equalsIgnoreCase(type)) {
            collectAssetCandidates(value, AssetType.SCENE, scenes);
        }
    }

    private void collectAssetCandidates(JsonNode value, AssetType assetType,
                                        Map<String, AssetCandidate> candidates) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) {
            value.forEach(item -> collectAssetCandidates(item, assetType, candidates));
            return;
        }
        if (!value.isObject()) return;
        addCandidate(
                firstText(value, "name", "title", "label"),
                structuredDescription(value, assetType),
                assetType,
                candidates
        );
    }

    private void collectNamedCandidate(JsonNode source, AssetType assetType,
                                       Map<String, AssetCandidate> candidates,
                                       String[] nameFields, String[] descriptionFields) {
        String name = firstText(source, nameFields);
        if (name == null) return;
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        for (String field : descriptionFields) {
            String text = firstText(source, field);
            if (text != null) descriptions.add(text);
        }
        addCandidate(name, String.join("; ", descriptions), assetType, candidates);
    }

    private String structuredDescription(JsonNode value, AssetType assetType) {
        String[] fields = assetType == AssetType.CHARACTER
                ? new String[]{"appearance", "description", "visualDescription", "costume", "traits", "personality", "visualPrompt"}
                : new String[]{"description", "visualDescription", "environment", "appearance", "lighting", "atmosphere", "visualPrompt"};
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        for (String field : fields) {
            String text = firstText(value, field);
            if (text != null) descriptions.add(text);
        }
        return String.join("; ", descriptions);
    }

    private void addCandidate(String rawName, String rawDescription, AssetType assetType,
                              Map<String, AssetCandidate> candidates) {
        String name = canonicalAssetName(rawName);
        String description = normalizeText(rawDescription);
        if (name == null || description == null) {
            return;
        }
        if (description.length() < 5) {
            description = (assetType == AssetType.CHARACTER ? "角色设定：" : "场景设定：") + description;
        }
        String normalizedName = normalizedAssetName(name);
        AssetCandidate candidate = new AssetCandidate(name, normalizedName, description);
        candidates.merge(
                normalizedName,
                candidate,
                (existing, replacement) -> replacement.description().length() > existing.description().length()
                        ? replacement : existing
        );
    }

    private String canonicalAssetName(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : limit(normalized, 128);
    }

    private String normalizedAssetName(String value) {
        return value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private ComicShot toShot(Long episodeId, String storyboardVersion, JsonNode value, int index,
                             Set<String> usedKeys) {
        JsonNode timecode = value.path("timecode");
        JsonNode camera = value.path("camera");
        JsonNode performance = value.path("performance");
        JsonNode audio = value.path("audio");
        JsonNode prompts = value.path("prompts");
        String visualDescription = firstText(value, "visualDescription", "sceneDescription");
        if (visualDescription == null) {
            visualDescription = firstText(prompts, "image");
        }
        if (visualDescription == null) {
            throw new IllegalStateException("Storyboard shot " + index + " has no visual description");
        }
        LocalDateTime now = LocalDateTime.now();
        ComicShot shot = new ComicShot();
        shot.setEpisodeId(episodeId);
        shot.setShotKey(stableShotKey(episodeId, storyboardVersion, value, index, usedKeys));
        shot.setSequenceNo(index);
        shot.setDurationMs(durationMs(value, timecode));
        shot.setShotScale(limit(firstText(camera, "shotSize"), 64));
        shot.setCameraAngle(limit(firstText(camera, "angle"), 128));
        shot.setCameraMovement(limit(firstText(camera, "movement"), 128));
        shot.setEmotion(limit(firstText(performance, "emotion"), 128));
        shot.setVisualDescription(limit(visualDescription, 10_000));
        shot.setDialogue(limit(firstText(audio, "dialogue"), 10_000));
        shot.setNarration(limit(firstText(audio, "narration"), 10_000));
        shot.setSoundEffect(limit(joinText(audio.path("sfx")), 1000));
        shot.setBgmCue(limit(firstText(audio, "bgmMood"), 1000));
        shot.setFirstFramePrompt(limit(firstText(prompts, "image"), 10_000));
        shot.setVideoPrompt(limit(firstText(prompts, "video"), 10_000));
        shot.setNegativePrompt(limit(firstText(prompts, "negative"), 10_000));
        shot.setCharacterVersionIdsJson("[]");
        shot.setStatus("DRAFT");
        shot.setRevision(0L);
        shot.setCreatedAt(now);
        shot.setUpdatedAt(now);
        return shot;
    }

    private void projectCharacter(ComicProjectWorkflowRun binding, JsonNode output) {
        JsonNode versionOutput = referenceVersion(output);
        Long outputVersionId = positiveLong(versionOutput.path("assetVersionId"));
        AssetRunIdentity identity = requireAssetRunIdentity(
                binding, CHARACTER_HANDLER, "CHARACTER", "character"
        );
        if (!Objects.equals(outputVersionId, identity.versionId())) {
            throw new IllegalStateException("comic.character_reference returned another asset version");
        }
        Long versionId = identity.versionId();
        ComicCharacterVersion version = characterVersionMapper.selectById(versionId);
        ComicCharacter character = version == null ? null : characterMapper.selectById(version.getCharacterId());
        if (character == null || !binding.getProjectId().equals(character.getProjectId())
                || !identity.assetId().equals(character.getId())) {
            throw new IllegalStateException("Character version does not belong to the comic project");
        }
        ImageViews views = imageViews(versionOutput);
        if (views.front() == null && views.composite() == null) {
            throw new IllegalStateException("comic.character_reference returned no image");
        }
        String front = firstNonBlank(views.front(), views.composite());
        String side = firstNonBlank(views.side(), views.composite(), front);
        String back = firstNonBlank(views.back(), views.composite(), front);
        int updated = characterVersionMapper.markReady(versionId, front, side, back);
        if (updated == 0 && !"READY".equals(version.getStatus())) {
            throw new IllegalStateException("Character version could not be marked READY");
        }
    }

    private void projectScene(ComicProjectWorkflowRun binding, JsonNode output) {
        JsonNode versionOutput = referenceVersion(output);
        Long outputVersionId = positiveLong(versionOutput.path("assetVersionId"));
        AssetRunIdentity identity = requireAssetRunIdentity(
                binding, SCENE_HANDLER, "SCENE", "scene"
        );
        if (!Objects.equals(outputVersionId, identity.versionId())) {
            throw new IllegalStateException("comic.scene_reference returned another asset version");
        }
        Long versionId = identity.versionId();
        ComicSceneVersion version = sceneVersionMapper.selectById(versionId);
        ComicScene scene = version == null ? null : sceneMapper.selectById(version.getSceneId());
        if (scene == null || !binding.getProjectId().equals(scene.getProjectId())
                || !identity.assetId().equals(scene.getId())) {
            throw new IllegalStateException("Scene version does not belong to the comic project");
        }
        ImageViews views = imageViews(versionOutput);
        String anchor = firstNonBlank(views.composite(), views.front(), views.side(), views.back());
        if (anchor == null) {
            throw new IllegalStateException("comic.scene_reference returned no image");
        }
        int updated = sceneVersionMapper.markReady(versionId, anchor);
        if (updated == 0 && !"READY".equals(version.getStatus())) {
            throw new IllegalStateException("Scene version could not be marked READY");
        }
    }

    private AssetRunIdentity requireAssetRunIdentity(ComicProjectWorkflowRun binding,
                                                     String handlerKey,
                                                     String assetType,
                                                     String assetField) {
        WorkflowRun run = workflowMapper.selectById(binding.getWorkflowRunId());
        if (run == null
                || !Objects.equals(run.getId(), binding.getWorkflowRunId())
                || !Objects.equals(run.getUserId(), binding.getUserId())
                || !Objects.equals(run.getRootTaskId(), binding.getRootTaskId())
                || !"COMIC_PROJECT".equals(run.getLaunchSource())) {
            throw new IllegalStateException("Comic asset workflow binding does not match its run");
        }
        JsonNode input = readJson(run.getInputJson());
        JsonNode operationHandlerKeys = input == null ? null : input.path("operationHandlerKeys");
        JsonNode asset = input == null ? null : input.path(assetField);
        Long versionId = positiveLong(input == null ? null : input.path("comicAssetVersionId"));
        Long nestedVersionId = positiveLong(asset == null ? null : asset.path("assetVersionId"));
        Long assetId = positiveLong(asset == null ? null : asset.path("assetId"));
        if (input == null
                || input.path("comicProjectId").asLong() != binding.getProjectId()
                || input.path("projectId").asLong() != binding.getProjectId()
                || !assetType.equalsIgnoreCase(input.path("comicAssetType").asText())
                || operationHandlerKeys == null
                || !operationHandlerKeys.isArray()
                || operationHandlerKeys.size() != 1
                || !handlerKey.equals(operationHandlerKeys.path(0).asText())
                || asset == null
                || !asset.isObject()
                || versionId == null
                || !Objects.equals(versionId, nestedVersionId)
                || assetId == null) {
            throw new IllegalStateException("Comic asset workflow input identity is invalid");
        }
        return new AssetRunIdentity(assetId, versionId);
    }

    private JsonNode referenceVersion(JsonNode output) {
        JsonNode direct = output.path("referenceAssetVersion");
        if (direct.isObject()) {
            return direct;
        }
        JsonNode versions = output.path("referenceAssetVersions");
        return versions.isArray() && !versions.isEmpty() ? versions.get(0) : output;
    }

    private ImageViews imageViews(JsonNode versionOutput) {
        String front = null;
        String side = null;
        String back = null;
        String composite = null;
        JsonNode views = versionOutput.path("views");
        if (views.isArray()) {
            for (JsonNode view : views) {
                String role = firstText(view, "role");
                String url = firstText(view, "url", "imageUrl");
                if (url == null) continue;
                if (role == null) composite = firstNonBlank(composite, url);
                else if (role.contains("front")) front = url;
                else if (role.contains("side")) side = url;
                else if (role.contains("back")) back = url;
                else composite = firstNonBlank(composite, url);
            }
        }
        JsonNode references = versionOutput.path("referenceImages");
        if (composite == null && references.isArray() && !references.isEmpty()) {
            composite = references.get(0).asText(null);
        }
        return new ImageViews(front, side, back, composite);
    }

    private JsonNode findHandlerOutput(JsonNode context, String handlerKey) {
        if (context == null || !context.isObject()) {
            return null;
        }
        Iterator<JsonNode> values = context.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (handlerKey.equals(firstText(value, "handlerKey"))) {
                return value;
            }
        }
        return null;
    }

    private int durationMs(JsonNode value, JsonNode timecode) {
        long duration = integral(timecode.path("targetDurationMs"));
        if (duration <= 0) duration = integral(value.path("durationMs"));
        if (duration <= 0) {
            long start = integral(timecode.path("startMs"));
            long end = integral(timecode.path("endMs"));
            duration = end > start ? end - start : 5000;
        }
        return (int) Math.max(1000, Math.min(30_000, duration));
    }

    private String stableShotKey(Long episodeId, String storyboardVersion, JsonNode value, int index,
                                 Set<String> usedKeys) {
        String supplied = firstText(value, "shotId", "id");
        if (supplied != null && supplied.length() <= 36 && usedKeys.add(supplied)) {
            return supplied;
        }
        String source = episodeId + ":" + (storyboardVersion == null ? "storyboard" : storyboardVersion)
                + ":" + (supplied == null ? index : supplied) + ":" + index;
        String generated = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
        usedKeys.add(generated);
        return generated;
    }

    private Long positiveLong(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            long parsed = value.isIntegralNumber() ? value.asLong() : Long.parseLong(value.asText().trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid comic workflow input JSON", exception);
        }
    }

    private long integral(JsonNode value) {
        return value != null && value.isIntegralNumber() ? value.asLong() : 0L;
    }

    private String joinText(JsonNode values) {
        if (values == null || !values.isArray()) return null;
        StringBuilder joined = new StringBuilder();
        for (JsonNode value : values) {
            String text = value.asText("").trim();
            if (text.isEmpty()) continue;
            if (!joined.isEmpty()) joined.append("、");
            joined.append(text);
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private enum AssetType {
        CHARACTER,
        SCENE
    }

    private record AssetCandidate(String name, String normalizedName, String description) {
    }

    private record AssetCandidates(List<AssetCandidate> characters, List<AssetCandidate> scenes) {
    }

    private record AssetRunIdentity(Long assetId, Long versionId) {
    }

    private record ImageViews(String front, String side, String back, String composite) {
    }
}
