import base64
import json
import logging
import re
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.model_client import ModelClient, ModelClientError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from client.siliconflow_video_client import SiliconFlowVideoClient, SiliconFlowVideoError
from client.openai_images_client import OpenAIImagesClient
from client.agnes_video_client import AgnesVideoClient
from config import resolve_siliconflow_api_key
from providers import registry as provider_registry
from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from handlers.generated_image_persister import GeneratedImagePersister
from handlers.generated_video_persister import GeneratedVideoPersister
from storage.asset_storage import asset_storage


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}

# 每个分镜固定 5 秒：30s -> 6 镜，60s -> 12 镜，90s -> 18 镜
SCENE_SECONDS = 5
MAX_SCENES = 18
DEFAULT_EPISODE_SECONDS = 30


class WorkflowStepHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        model_client: ModelClient | None = None,
        image_client: SiliconFlowVideoClient | None = None,
        seedance_client: SeedanceVideoClient | None = None,
        postprocessor: DigitalHumanPostprocessor | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.model_client = model_client or ModelClient()
        self.image_client = image_client or SiliconFlowVideoClient()
        self.seedance_client = seedance_client or SeedanceVideoClient()
        self.postprocessor = postprocessor or DigitalHumanPostprocessor()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status}

            params = context.get("params") or {}
            if not params.get("workflowStep"):
                raise RuntimeError("workflow step marker missing")

            node_def_type = str(params.get("nodeDefType") or "").upper()
            workflow_inputs = params.get("workflowInputs") or {}
            form = _merge_form(workflow_inputs)
            model_config = context.get("modelConfig") or {}

            self.backend_client.mark_processing(task_id, progress=12, progress_message="工作流节点执行中", trace_id=trace_id)

            if node_def_type in {"LLM_TEXT", "MODEL_CALL"}:
                output = self._run_script_planner(form, workflow_inputs, model_config)
            elif node_def_type == "IMAGE_MODEL":
                output = self._run_keyframe(form, workflow_inputs, model_config, task_id, trace_id)
            elif node_def_type == "TTS_MODEL":
                output = self._run_tts(form, workflow_inputs, model_config, task_id, trace_id)
            elif node_def_type == "VIDEO_MODEL":
                output = self._run_video(form, workflow_inputs, model_config, task_id, trace_id)
            elif node_def_type in {"SUBTITLE", "TOOL_CALL"}:
                output = self._run_compose(form, workflow_inputs, task_id, trace_id)
            else:
                raise RuntimeError(f"unsupported workflow node type: {node_def_type}")

            self.backend_client.mark_success(
                task_id,
                {
                    "resourceType": "TEXT",
                    "contentText": json.dumps(output, ensure_ascii=False),
                },
                trace_id=trace_id,
            )
            return {"status": "SUCCESS", "taskId": task_id, "nodeDefType": node_def_type}
        except Exception as error:
            LOGGER.exception("workflow step failed taskId=%s", task_id)
            self._mark_failed_safe(task_id, error, trace_id=trace_id)
            return {"status": "FAILED", "taskId": task_id, "error": str(error)}

    def _run_script_planner(self, form: dict[str, Any], workflow_inputs: dict[str, Any], model_config: dict[str, Any]) -> dict[str, Any]:
        story_theme = form.get("storyTheme") or "温情漫剧"
        plot_outline = form.get("plotOutline") or ""
        visual_style = form.get("visualStyle") or "电影感写实"
        genre = form.get("genre") or ""
        scene_count = _resolve_scene_count(form, workflow_inputs)
        script_global, script_per_scene = _parse_per_scene_feedback(form.get("scriptFeedback") or form.get("scriptRevision"))
        storyboard_global, storyboard_per_scene = _parse_per_scene_feedback(form.get("storyboardFeedback"))

        feedback_lines: list[str] = []
        if script_global:
            feedback_lines.append(f"整体脚本意见：{script_global}")
        if storyboard_global:
            feedback_lines.append(f"整体分镜意见：{storyboard_global}")
        for index, text in sorted({**script_per_scene, **storyboard_per_scene}.items()):
            feedback_lines.append(f"分镜{index}意见：{text}")

        prompt = (
            "你是专业的AI漫剧分镜编剧。请根据用户提供的主题和梗概，创作一部完整可拍的多分镜短剧脚本。\n"
            f"目标时长约 {scene_count * SCENE_SECONDS} 秒，必须正好输出 {scene_count} 个分镜，每个分镜约 {SCENE_SECONDS} 秒。\n\n"
            "只输出 JSON 对象，不要 markdown，不要解释。JSON 格式如下：\n"
            '{\n'
            '  "title": "整集标题",\n'
            '  "synopsis": "故事梗概（2-3句话概述整个故事线）",\n'
            '  "genre": "题材类型描述，例如：生活/职场喜剧",\n'
            '  "characters": [\n'
            '    {"name": "角色名", "appearance": "外貌特征、服装、体态的详细描述", "personality": "性格特点简述"}\n'
            '  ],\n'
            '  "locations": [\n'
            '    {"name": "场景名称", "description": "场景的详细环境描述（时间、光线、陈设、氛围）"}\n'
            '  ],\n'
            '  "scenes": [\n'
            '    {\n'
            '      "index": 1,\n'
            '      "sceneTitle": "分镜标题",\n'
            '      "durationSeconds": 5,\n'
            '      "characterScene": "角色名 / 场景名",\n'
            '      "cameraLanguage": "镜头类型（特写/中景/全景/远景），机位（俯视/平视/仰视），运动（固定/推进/摇移/跟随）",\n'
            '      "sceneDescription": "详细的画面描述：人物的动作表情、环境细节、光影效果、构图要素，适合AI图生视频的英文提示词风格",\n'
            '      "plot": "这个分镜的情节描述（中文，说明发生了什么）",\n'
            '      "dialogue": "角色台词（5秒内能说完，简短自然）",\n'
            '      "narration": "旁白文字（如无旁白可留空）",\n'
            '      "voiceDirection": "配音指导，格式示例：【说话人=角色名｜性别｜年龄段】台词内容",\n'
            '      "subtitleZh": "中文字幕（与dialogue一致）",\n'
            '      "subtitleEn": "English subtitle translation",\n'
            '      "presenterGender": "female 或 male（主要说话人性别）"\n'
            '    }\n'
            '  ]\n'
            '}\n\n'
            "创作要求：\n"
            "1. 角色设计要具体鲜明，包含外貌、服装、体态等可视化细节\n"
            "2. 场景描述要详细，包含时间、光线、陈设、氛围等环境要素\n"
            "3. 分镜之间剧情连贯，有起承转合的叙事节奏\n"
            "4. sceneDescription 必须是电影感画面描述（英文），包含人物动作、镜头角度、光线效果，适合AI生图\n"
            "5. 每个分镜标注镜头语言（景别+机位+运动）\n"
            "6. dialogue 简短自然，5秒内能说完\n"
            "7. voiceDirection 标注说话人、性别和情感\n\n"
            f"主题：{story_theme}\n题材：{genre or '未指定'}\n梗概：{plot_outline}\n画风：{visual_style}\n"
            + ("\n".join(feedback_lines) + "\n" if feedback_lines else "")
            + "请围绕用户给定的主题进行创作，充分发挥想象力，设计有趣的角色和场景。"
        )
        parsed: dict[str, Any] | None = None
        try:
            raw = self.model_client.generate(
                prompt,
                system_prompt="只输出 JSON 对象，不要解释。",
                provider=model_config.get("provider"),
                model_name=model_config.get("modelName"),
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=model_config.get("timeoutSeconds") or 120,
                max_tokens=max(2400, 800 * scene_count),
            )
            parsed = _extract_json(raw)
        except ModelClientError:
            LOGGER.warning("script planner model call failed, using fallback scenes")

        scenes = _normalize_scenes(parsed, scene_count, form)
        title = ""
        if isinstance(parsed, dict):
            title = str(parsed.get("title") or "").strip()
        if not title:
            title = str(story_theme)

        synopsis = ""
        characters: list[dict[str, Any]] = []
        locations: list[dict[str, Any]] = []
        if isinstance(parsed, dict):
            synopsis = str(parsed.get("synopsis") or "").strip()
            if isinstance(parsed.get("characters"), list):
                characters = [c for c in parsed["characters"] if isinstance(c, dict)]
            if isinstance(parsed.get("locations"), list):
                locations = [loc for loc in parsed["locations"] if isinstance(loc, dict)]

        output: dict[str, Any] = {
            "title": title,
            "synopsis": synopsis,
            "genre": genre or (parsed.get("genre") if isinstance(parsed, dict) else "") or "",
            "characters": characters,
            "locations": locations,
            "sceneCount": len(scenes),
            "sceneSeconds": SCENE_SECONDS,
            "totalSeconds": len(scenes) * SCENE_SECONDS,
            "scenes": scenes,
        }
        # 向后兼容：旧版单镜字段取第一镜
        output.update({key: scenes[0][key] for key in (
            "sceneTitle", "sceneDescription", "dialogue", "subtitleZh", "subtitleEn", "narration", "presenterGender",
        )})
        return output

    def _run_keyframe(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        script = _find_script_payload(workflow_inputs)
        scenes = _scenes_from_script(script, form)
        script_global, _ = _parse_per_scene_feedback(form.get("scriptFeedback") or form.get("scriptRevision"))
        storyboard_global, storyboard_per_scene = _parse_per_scene_feedback(form.get("storyboardFeedback"))

        gen_image = _resolve_image_generator(model_config)
        total = len(scenes)
        source_urls: list[str] = []
        prompts: list[str] = []
        for position, scene in enumerate(scenes, start=1):
            scene_description = scene.get("sceneDescription") or scene.get("narration") or form.get("plotOutline") or ""
            merged = {**form, "plotOutline": scene_description, "mainCharacters": scene.get("dialogue") or form.get("mainCharacters")}
            feedback_parts = [part for part in (script_global, storyboard_global, storyboard_per_scene.get(position)) if part]
            prompt = (
                f"{DigitalHumanVideoHandler._build_comic_image_prompt(merged)}, "
                f"cinematic close-up, warm indoor lighting, shallow depth of field, emotional expression, "
                f"scene detail: {scene_description}, no text, no watermark, 16:9 composition."
            )
            if feedback_parts:
                prompt = f"{prompt}\nUser revision notes: {'；'.join(feedback_parts)}"
            self.backend_client.mark_processing(
                task_id,
                progress=20 + int(25 * position / max(total, 1)),
                progress_message=f"正在生成关键帧 {position}/{total}",
                trace_id=trace_id,
            )
            source_urls.append(gen_image(prompt))
            prompts.append(prompt)

        persisted = GeneratedImagePersister().persist_images(task_id=task_id, urls=source_urls)
        images: list[dict[str, Any]] = []
        for position, source_url in enumerate(source_urls, start=1):
            stable_url = persisted[position - 1]["url"] if len(persisted) >= position else source_url
            images.append(
                {
                    "sceneIndex": position,
                    "imageUrl": stable_url,
                    "sourceImageUrl": source_url,
                    "prompt": prompts[position - 1],
                }
            )
        return {
            "images": images,
            "sceneCount": total,
            # 向后兼容字段
            "imageUrl": images[0]["imageUrl"] if images else "",
            "sourceImageUrl": images[0]["sourceImageUrl"] if images else "",
            "prompt": images[0]["prompt"] if images else "",
        }

    def _run_tts(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        script = _find_script_payload(workflow_inputs)
        scenes = _scenes_from_script(script, form)
        client = SiliconFlowVideoClient(
            api_key=resolve_siliconflow_api_key(model_config),
            base_url=model_config.get("baseUrl"),
        )
        total = len(scenes)
        audios: list[dict[str, Any]] = []
        for position, scene in enumerate(scenes, start=1):
            speech_text = scene.get("dialogue") or scene.get("narration") or form.get("plotOutline") or "奶就放心了。"
            presenter_gender = scene.get("presenterGender") or DigitalHumanVideoHandler._resolve_presenter_gender(form)
            voice = DigitalHumanVideoHandler._resolve_voice(form, presenter_gender)
            self.backend_client.mark_processing(
                task_id,
                progress=48 + int(15 * position / max(total, 1)),
                progress_message=f"正在生成角色配音 {position}/{total}",
                trace_id=trace_id,
            )
            audio_data_url = client.generate_speech_data_url(
                input_text=speech_text,
                model=model_config.get("modelName"),
                voice=voice,
            )
            audio_url = _persist_audio_data_url(task_id, audio_data_url, index=position)
            audios.append(
                {
                    "sceneIndex": position,
                    "audioUrl": audio_url,
                    "audioDataUrl": audio_data_url,
                    "speechText": speech_text,
                    "voice": voice,
                }
            )
        first = audios[0] if audios else {}
        return {
            "audios": [{key: value for key, value in item.items() if key != "audioDataUrl"} for item in audios],
            "audioDataUrls": [item.get("audioDataUrl") or "" for item in audios],
            "sceneCount": total,
            # 向后兼容字段
            "audioUrl": first.get("audioUrl") or "",
            "audioDataUrl": first.get("audioDataUrl") or "",
            "speechText": first.get("speechText") or "",
            "voice": first.get("voice") or "",
        }

    def _run_video(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        keyframe = _find_upstream(workflow_inputs, "images")
        script = _find_script_payload(workflow_inputs)
        scenes = _scenes_from_script(script, form)
        keyframe_images = keyframe.get("images")
        if not isinstance(keyframe_images, list) or not keyframe_images:
            single = keyframe.get("imageUrl")
            if not single:
                raise SeedanceVideoError("keyframe image is required")
            keyframe_images = [{"sceneIndex": index + 1, "imageUrl": single} for index in range(len(scenes))]
        scene_global, scene_per_scene = _parse_per_scene_feedback(form.get("sceneFeedback"))

        gen_video = _resolve_video_generator(model_config)
        persister = GeneratedVideoPersister()
        total = len(scenes)
        clips: list[dict[str, Any]] = []
        for position, scene in enumerate(scenes, start=1):
            image_entry = keyframe_images[position - 1] if position <= len(keyframe_images) else keyframe_images[-1]
            image_url = (image_entry or {}).get("imageUrl")
            if not image_url:
                raise SeedanceVideoError(f"keyframe image missing for scene {position}")
            prompt = DigitalHumanVideoHandler._build_comic_video_prompt(form)
            if scene.get("sceneDescription"):
                prompt = f"{prompt}\nScene focus: {scene.get('sceneDescription')}"
            feedback_parts = [part for part in (scene_global, scene_per_scene.get(position)) if part]
            if feedback_parts:
                prompt = f"{prompt}\nUser revision notes: {'；'.join(feedback_parts)}"
            self.backend_client.mark_processing(
                task_id,
                progress=48 + int(15 * position / max(total, 1)),
                progress_message=f"正在生成分镜视频 {position}/{total}",
                trace_id=trace_id,
            )
            result = gen_video(prompt=prompt, image=image_url)
            persisted = persister.persist_video_url(task_id=task_id, source_url=result["videoUrl"], index=position)
            clips.append(
                {
                    "sceneIndex": position,
                    "videoUrl": persisted["url"],
                    "sourceVideoUrl": result["videoUrl"],
                }
            )
        first = clips[0] if clips else {}
        return {
            "clips": clips,
            "sceneCount": total,
            "provider": str(model_config.get("provider") or "seedance"),
            # 向后兼容字段
            "videoUrl": first.get("videoUrl") or "",
            "sourceVideoUrl": first.get("sourceVideoUrl") or "",
        }

    def _run_compose(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        video = _find_upstream(workflow_inputs, "clips")
        tts = _find_upstream(workflow_inputs, "audios")
        script = _find_script_payload(workflow_inputs)
        scenes = _scenes_from_script(script, form)

        clips = video.get("clips")
        if not isinstance(clips, list) or not clips:
            if not video.get("videoUrl"):
                raise DigitalHumanPostprocessError("video and audio are required for compose")
            clips = [{"sceneIndex": 1, "videoUrl": video.get("videoUrl")}]
        audios = tts.get("audios")
        audio_data_urls = tts.get("audioDataUrls") if isinstance(tts.get("audioDataUrls"), list) else []
        if not isinstance(audios, list) or not audios:
            if not tts.get("audioUrl") and not tts.get("audioDataUrl"):
                raise DigitalHumanPostprocessError("video and audio are required for compose")
            audios = [{"sceneIndex": 1, "audioUrl": tts.get("audioUrl") or "", "speechText": tts.get("speechText") or ""}]
            audio_data_urls = [tts.get("audioDataUrl") or ""]

        total = len(clips)
        segment_paths = []
        segments: list[dict[str, Any]] = []
        for position, clip in enumerate(clips, start=1):
            scene = scenes[position - 1] if position <= len(scenes) else (scenes[-1] if scenes else {})
            audio_entry = audios[position - 1] if position <= len(audios) else (audios[-1] if audios else {})
            audio_data_url = audio_data_urls[position - 1] if position <= len(audio_data_urls) else ""
            video_url = (clip or {}).get("videoUrl")
            audio_url = (audio_entry or {}).get("audioUrl") or ""
            if not video_url or (not audio_url and not audio_data_url):
                raise DigitalHumanPostprocessError(f"video and audio are required for compose (scene {position})")
            subtitle_text = scene.get("subtitleZh") or scene.get("dialogue") or (audio_entry or {}).get("speechText") or ""
            self.backend_client.mark_processing(
                task_id,
                progress=70 + int(20 * position / max(total, 1)),
                progress_message=f"正在合成分镜 {position}/{total}",
                trace_id=trace_id,
            )
            segment = self.postprocessor.process(
                task_id=task_id,
                video_url=video_url,
                audio_data_url=audio_data_url or "",
                audio_url=audio_url,
                subtitle_text=subtitle_text,
                segment=f"scene-{position}" if total > 1 else None,
            )
            segment_paths.append(segment.video_path)
            segments.append(
                {
                    "sceneIndex": position,
                    "videoUrl": segment.video_url,
                    "subtitleUrl": segment.subtitle_url,
                    "subtitleZh": subtitle_text,
                    "subtitleEn": scene.get("subtitleEn") or "",
                }
            )

        if total > 1:
            self.backend_client.mark_processing(task_id, progress=94, progress_message="正在拼接成片", trace_id=trace_id)
            _, final_video_url = self.postprocessor.concat_videos(task_id=task_id, video_paths=segment_paths)
            subtitle_url = segments[0]["subtitleUrl"]
        else:
            final_video_url = segments[0]["videoUrl"]
            subtitle_url = segments[0]["subtitleUrl"]

        title = script.get("title") or script.get("sceneTitle") or form.get("storyTheme") or "AI 漫剧成片"
        keyframe_images = _find_upstream(workflow_inputs, "images").get("images")
        first_image_url = ""
        if isinstance(keyframe_images, list) and keyframe_images:
            first_image_url = (keyframe_images[0] or {}).get("imageUrl") or ""
        else:
            first_image_url = _find_upstream(workflow_inputs, "images").get("imageUrl") or ""
        markdown = _build_delivery_markdown(
            title=title,
            final_video_url=final_video_url,
            subtitle_zh=segments[0]["subtitleZh"],
            subtitle_en=segments[0]["subtitleEn"],
            image_url=first_image_url or None,
            scenes=[
                {
                    "index": item["sceneIndex"],
                    "subtitleZh": item["subtitleZh"],
                    "subtitleEn": item["subtitleEn"],
                }
                for item in segments
            ] if total > 1 else None,
        )
        return {
            "finalVideoUrl": final_video_url,
            "subtitleUrl": subtitle_url,
            "videoUrl": final_video_url,
            "segments": segments,
            "sceneCount": total,
            "markdown": markdown,
            "subtitleZh": segments[0]["subtitleZh"],
            "subtitleEn": segments[0]["subtitleEn"],
        }

    def _mark_failed_safe(self, task_id: int, error: Exception, trace_id: str | None = None) -> None:
        try:
            self.backend_client.mark_failed(
                task_id,
                {
                    "errorCode": "MODEL_CALL_FAILED",
                    "errorMessage": str(error),
                },
                trace_id=trace_id,
            )
        except BackendClientError:
            LOGGER.exception("failed to report workflow step failure taskId=%s", task_id)


def _persist_audio_data_url(task_id: int, audio_data_url: str, index: int = 1) -> str:
    prefix = "base64,"
    if prefix not in audio_data_url:
        return audio_data_url
    encoded = audio_data_url.split(prefix, 1)[1]
    audio_bytes = base64.b64decode(encoded)
    suffix = "" if index <= 1 else f"-{index}"
    relative_key = f"audio/{task_id}/voice{suffix}.mp3"
    return asset_storage.put_bytes(relative_key, audio_bytes, "audio/mpeg")


def _merge_form(workflow_inputs: dict[str, Any]) -> dict[str, Any]:
    form: dict[str, Any] = {}
    field_input = workflow_inputs.get("field-input") or {}
    if isinstance(field_input, dict):
        fields = field_input.get("fields")
        if isinstance(fields, dict):
            form.update(fields)
    for node_id, payload in workflow_inputs.items():
        if not isinstance(payload, dict):
            continue
        if node_id.startswith("user-input-"):
            nested = payload.get("fields")
            if isinstance(nested, dict):
                form.update(nested)
            else:
                for key in (
                    "scriptFeedback",
                    "storyboardFeedback",
                    "sceneFeedback",
                    "bgmFeedback",
                    "scriptRevision",
                    "visualRevision",
                ):
                    if payload.get(key):
                        form[key] = payload.get(key)
    direct_form = workflow_inputs.get("form")
    if isinstance(direct_form, dict):
        form.update(direct_form)
    return form


def _find_upstream(workflow_inputs: dict[str, Any] | None, key: str) -> dict[str, Any]:
    """在所有上游节点产出里找首个包含非空 `key` 的产出。

    工作流节点 id 可能因画布编排而异（如 image-to-video / clip-video、voice-tts / tts），
    按内容查找而非硬编码节点 id，使各步骤对拓扑健壮（QC/审核等中间节点不会截断数据）。
    """
    if not isinstance(workflow_inputs, dict):
        return {}
    for value in workflow_inputs.values():
        if isinstance(value, dict) and value.get(key):
            return value
    return {}


def _find_script_payload(workflow_inputs: dict[str, Any] | None) -> dict[str, Any]:
    found = _find_upstream(workflow_inputs, "scenes")
    if found:
        return found
    if isinstance(workflow_inputs, dict):
        return workflow_inputs.get("script-planner") or {}
    return {}


def _provider_protocol(model_config: dict[str, Any]) -> str:
    provider = str(model_config.get("provider") or "").lower()
    try:
        return str(provider_registry.provider_protocol(provider) or "").lower()
    except Exception:
        return ""


def _retry_transient(call, *, attempts: int = 3, backoff: float = 3.0):
    """对生图/生视频调用做瞬时错误重试。

    本地/生产经代理访问外部媒体网关时偶发连接抖动（Max retries / proxy connection
    failed）；逐镜生成多次并发会放大该问题。命中连接类错误时重试，避免整条工作流因
    单次抖动失败。
    """
    import time

    last_error: Exception | None = None
    for attempt in range(1, max(1, attempts) + 1):
        try:
            return call()
        except Exception as error:  # noqa: BLE001 - 需对所有客户端异常统一重试
            message = str(error).lower()
            transient = any(
                token in message
                for token in ("max retries", "connection", "timed out", "timeout", "proxy", "temporarily", "reset")
            )
            last_error = error
            if not transient or attempt >= attempts:
                raise
            LOGGER.warning("transient media call failure (attempt %s/%s): %s", attempt, attempts, message[:160])
            time.sleep(backoff * attempt)
    if last_error:
        raise last_error


def _resolve_image_generator(model_config: dict[str, Any]):
    """按模型 provider 返回 (prompt)->url 的生图函数。

    漫剧关键帧节点可绑定任意生图模型：agnes(openai_images 协议) 走 OpenAIImagesClient，
    其余(siliconflow 等)沿用 SiliconFlowVideoClient。修复此前硬编码 SiliconFlow 导致
    绑定 agnes 时拼出 /v1/v1/images/generations → 404 的问题。
    """
    protocol = _provider_protocol(model_config)
    model_name = model_config.get("modelName")
    if protocol == "openai_images":
        client = OpenAIImagesClient(
            base_url=model_config.get("baseUrl"),
            api_key=model_config.get("apiKey"),
            endpoint_path=model_config.get("imagePath") or model_config.get("endpointPath"),
            timeout_seconds=model_config.get("timeoutSeconds"),
            extra_auth_json=model_config.get("extraAuthJson"),
        )

        def _gen(prompt: str) -> str:
            urls = _retry_transient(
                lambda: client.generate_images(prompt=prompt, model=model_name, image_size="1024x576", batch_size=1)
            )
            return urls[0] if urls else ""

        return _gen

    sf_client = SiliconFlowVideoClient(
        api_key=resolve_siliconflow_api_key(model_config),
        base_url=model_config.get("baseUrl"),
    )

    def _gen(prompt: str) -> str:
        return _retry_transient(
            lambda: sf_client.generate_image(prompt=prompt, model=model_name, image_size="1024x576")
        )

    return _gen


def _resolve_video_generator(model_config: dict[str, Any]):
    """按模型 provider 返回 (prompt,image)->result 的生视频函数。

    agnes_video 走 AgnesVideoClient(/v1/videos 异步轮询)，其余沿用 SeedanceVideoClient。
    """
    protocol = _provider_protocol(model_config)
    model_name = model_config.get("modelName")
    if protocol == "agnes_video":
        client = AgnesVideoClient(
            base_url=model_config.get("baseUrl"),
            api_key=model_config.get("apiKey"),
            extra_auth_json=model_config.get("extraAuthJson"),
            timeout_seconds=model_config.get("timeoutSeconds"),
        )

        def _gen(*, prompt: str, image: str):
            return _retry_transient(
                lambda: client.generate_video(
                    prompt=prompt, image=image, model=model_name, image_size="1024x576",
                    duration=str(SCENE_SECONDS), resolution="480p", aspect_ratio="16:9",
                )
            )

        return _gen

    seedance = SeedanceVideoClient.from_model_config(model_config)

    def _gen(*, prompt: str, image: str):
        return _retry_transient(
            lambda: seedance.generate_video(
                prompt=prompt, image=image, model=model_name, duration=str(SCENE_SECONDS),
                resolution="480p", aspect_ratio="16:9", image_size="1024x576",
            )
        )

    return _gen


def _resolve_scene_count(form: dict[str, Any], workflow_inputs: dict[str, Any] | None = None) -> int:
    # 优先使用画布"分镜循环(scene_loop)"节点的显式拆分结果
    explicit = _scene_loop_count(workflow_inputs)
    if explicit:
        return max(1, min(explicit, MAX_SCENES))
    raw = str(form.get("episodeLength") or form.get("episodeDuration") or "").strip()
    digits = re.sub(r"[^0-9.]", "", raw)
    try:
        seconds = float(digits) if digits else float(DEFAULT_EPISODE_SECONDS)
    except ValueError:
        seconds = float(DEFAULT_EPISODE_SECONDS)
    if seconds <= 0:
        seconds = float(DEFAULT_EPISODE_SECONDS)
    count = int(round(seconds / SCENE_SECONDS))
    return max(1, min(count, MAX_SCENES))


def _scene_loop_count(workflow_inputs: dict[str, Any] | None) -> int | None:
    """读取 scene_loop 节点输出（含 sceneCount + indices）。"""
    if not isinstance(workflow_inputs, dict):
        return None
    for payload in workflow_inputs.values():
        if not isinstance(payload, dict):
            continue
        scene_count = payload.get("sceneCount")
        if isinstance(scene_count, int) and scene_count > 0 and isinstance(payload.get("indices"), list):
            return scene_count
    return None


def _parse_per_scene_feedback(value: Any) -> tuple[str, dict[int, str]]:
    """用户意见兼容两种格式：纯文本（整体意见）或 JSON 映射（逐分镜意见）。

    JSON 形如 {"all": "整体加快节奏", "1": "第一镜镜头拉近", "scene-3": "换成夜景"}。
    返回 (整体意见, {分镜序号: 意见})。
    """
    if value is None:
        return "", {}
    mapping: dict[str, Any] | None = None
    if isinstance(value, dict):
        mapping = value
    else:
        text = str(value).strip()
        if not text:
            return "", {}
        if text.startswith("{"):
            try:
                parsed = json.loads(text)
                if isinstance(parsed, dict):
                    mapping = parsed
            except json.JSONDecodeError:
                mapping = None
        if mapping is None:
            return text, {}
    global_text = ""
    per_scene: dict[int, str] = {}
    for key, raw in mapping.items():
        if raw is None:
            continue
        text = str(raw).strip()
        if not text:
            continue
        normalized = str(key).strip().lower()
        if normalized in {"all", "global", "overall", "*", "整体"}:
            global_text = text
            continue
        match = re.search(r"(\d+)", normalized)
        if match:
            per_scene[int(match.group(1))] = text
    return global_text, per_scene


def _scenes_from_script(script: dict[str, Any], form: dict[str, Any]) -> list[dict[str, Any]]:
    scenes = script.get("scenes") if isinstance(script, dict) else None
    if isinstance(scenes, list) and scenes:
        return [scene for scene in scenes if isinstance(scene, dict)] or [_fallback_script(form)]
    if isinstance(script, dict) and (script.get("sceneDescription") or script.get("dialogue")):
        return [script]
    return [_fallback_script(form)]


def _normalize_scenes(parsed: dict[str, Any] | None, count: int, form: dict[str, Any]) -> list[dict[str, Any]]:
    scenes_raw: list[Any] = []
    if isinstance(parsed, dict):
        if isinstance(parsed.get("scenes"), list):
            scenes_raw = parsed["scenes"]
        elif parsed.get("sceneDescription") or parsed.get("dialogue"):
            scenes_raw = [parsed]
    fallback = _fallback_script(form)
    theme = form.get("storyTheme") or "温情漫剧"
    scenes: list[dict[str, Any]] = []
    for position in range(1, count + 1):
        source = scenes_raw[position - 1] if position <= len(scenes_raw) and isinstance(scenes_raw[position - 1], dict) else {}
        dialogue = str(source.get("dialogue") or "")
        if not dialogue:
            dialogue = f"这是{theme}第{position}幕的精彩台词。"
        scene_desc = str(source.get("sceneDescription") or "")
        if not scene_desc:
            scene_desc = (
                f"Cinematic shot for scene {position} of '{theme}', "
                f"expressive character, atmospheric lighting, shallow depth of field, 16:9."
            )
        scenes.append(
            {
                "index": position,
                "sceneTitle": str(source.get("sceneTitle") or f"{theme} · 分镜{position}"),
                "sceneDescription": scene_desc,
                "dialogue": dialogue,
                "subtitleZh": str(source.get("subtitleZh") or dialogue),
                "subtitleEn": str(source.get("subtitleEn") or f"Scene {position} of {theme}."),
                "narration": str(source.get("narration") or ""),
                "presenterGender": str(source.get("presenterGender") or fallback["presenterGender"]),
                "durationSeconds": int(source.get("durationSeconds") or SCENE_SECONDS),
                "characterScene": str(source.get("characterScene") or ""),
                "cameraLanguage": str(source.get("cameraLanguage") or ""),
                "plot": str(source.get("plot") or ""),
                "voiceDirection": str(source.get("voiceDirection") or ""),
            }
        )
    return scenes


def _extract_json(raw: str) -> dict[str, Any] | None:
    text = (raw or "").strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass
    match = re.search(r"\{.*\}", text, flags=re.DOTALL)
    if not match:
        return None
    try:
        parsed = json.loads(match.group(0))
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        return None


def _fallback_script(form: dict[str, Any]) -> dict[str, Any]:
    theme = form.get("storyTheme") or "温情漫剧"
    outline = form.get("plotOutline") or ""
    style = form.get("visualStyle") or "电影感写实"
    desc_hint = outline if outline else theme
    return {
        "sceneTitle": theme,
        "sceneDescription": (
            f"Cinematic close-up shot, {style} style, depicting a scene about '{desc_hint}'. "
            f"Expressive character in focus, atmospheric lighting, shallow depth of field, "
            f"detailed background matching the story theme, no text, no watermark, 16:9 composition."
        ),
        "dialogue": f"这就是关于{theme}的故事。",
        "subtitleZh": f"这就是关于{theme}的故事",
        "subtitleEn": f"This is a story about {theme}.",
        "narration": f"一段关于{theme}的精彩故事正在展开。",
        "presenterGender": "female",
    }


def _build_delivery_markdown(
    *,
    title: str,
    final_video_url: str,
    subtitle_zh: str,
    subtitle_en: str,
    image_url: str | None,
    scenes: list[dict[str, Any]] | None = None,
) -> str:
    lines = [
        f"# {title}",
        "",
        f"[video]({final_video_url})",
        "",
    ]
    if image_url:
        lines.extend([f"![关键帧]({image_url})", ""])
    if scenes:
        for scene in scenes:
            zh = scene.get("subtitleZh") or ""
            en = scene.get("subtitleEn") or ""
            line = f"**分镜{scene.get('index')}**"
            if zh:
                line += f" {zh}"
            lines.append(line + "  ")
            if en:
                lines.append(f"*{en}*  ")
        lines.append("")
    else:
        if subtitle_zh:
            lines.append(f"**{subtitle_zh}**  ")
        if subtitle_en:
            lines.append(f"*{subtitle_en}*")
    lines.extend(["", "> AI 制作", ""])
    return "\n".join(lines)
