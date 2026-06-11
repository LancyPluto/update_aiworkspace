import json
import logging
import re
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.model_client import ModelClient, ModelClientError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from client.siliconflow_video_client import SiliconFlowVideoClient, SiliconFlowVideoError
from config import resolve_siliconflow_api_key
from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from handlers.digital_human_video_handler import DigitalHumanVideoHandler


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


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
        plot_outline = form.get("plotOutline") or "祖孙之间的暖心对话"
        visual_style = form.get("visualStyle") or "电影感写实"
        script_feedback = form.get("scriptFeedback") or form.get("scriptRevision") or ""
        storyboard_feedback = form.get("storyboardFeedback") or ""
        prompt = (
            "你是 AI 漫剧分镜编剧。根据用户输入，输出一个 5 秒单镜场景的 JSON，不要 markdown。\n"
            "字段：sceneTitle, sceneDescription, dialogue, subtitleZh, subtitleEn, narration, presenterGender。\n"
            "要求：电影感镜头、人物表情细腻、适合图生视频；对白简短自然；subtitleZh 与 dialogue 一致；subtitleEn 为地道英文。\n"
            f"主题：{story_theme}\n梗概：{plot_outline}\n画风：{visual_style}\n"
            f"脚本意见：{script_feedback or '无'}\n分镜意见：{storyboard_feedback or '无'}\n"
            "若用户未指定角色，可生成祖孙温情室内对话场景。"
        )
        try:
            raw = self.model_client.generate(
                prompt,
                system_prompt="只输出 JSON 对象，不要解释。",
                provider=model_config.get("provider"),
                model_name=model_config.get("modelName"),
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=model_config.get("timeoutSeconds") or 90,
                max_tokens=1200,
            )
            parsed = _extract_json(raw)
            if parsed:
                return parsed
        except ModelClientError:
            LOGGER.warning("script planner model call failed, using fallback scene")
        return _fallback_script(form)

    def _run_keyframe(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        script = workflow_inputs.get("script-planner") or {}
        scene_description = script.get("sceneDescription") or script.get("narration") or form.get("plotOutline") or ""
        merged = {**form, "plotOutline": scene_description, "mainCharacters": script.get("dialogue") or form.get("mainCharacters")}
        prompt = (
            f"{DigitalHumanVideoHandler._build_comic_image_prompt(merged)}, "
            f"cinematic close-up, warm indoor lighting, shallow depth of field, emotional expression, "
            f"scene detail: {scene_description}, no text, no watermark, 16:9 composition."
        )
        self.backend_client.mark_processing(task_id, progress=30, progress_message="正在生成电影感关键帧", trace_id=trace_id)
        image_client = SiliconFlowVideoClient(
            api_key=resolve_siliconflow_api_key(model_config),
            base_url=model_config.get("baseUrl"),
        )
        image_url = image_client.generate_image(
            prompt=prompt,
            model=model_config.get("modelName"),
            image_size="1024x576",
        )
        return {"imageUrl": image_url, "prompt": prompt}

    def _run_tts(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        script = workflow_inputs.get("script-planner") or {}
        speech_text = script.get("dialogue") or script.get("narration") or form.get("plotOutline") or "奶就放心了。"
        presenter_gender = script.get("presenterGender") or DigitalHumanVideoHandler._resolve_presenter_gender(form)
        voice = DigitalHumanVideoHandler._resolve_voice(form, presenter_gender)
        self.backend_client.mark_processing(task_id, progress=45, progress_message="正在生成角色配音", trace_id=trace_id)
        client = SiliconFlowVideoClient(
            api_key=resolve_siliconflow_api_key(model_config),
            base_url=model_config.get("baseUrl"),
        )
        audio_data_url = client.generate_speech_data_url(
            input_text=speech_text,
            model=model_config.get("modelName"),
            voice=voice,
        )
        return {"audioDataUrl": audio_data_url, "speechText": speech_text, "voice": voice}

    def _run_video(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        keyframe = workflow_inputs.get("keyframe") or {}
        image_url = keyframe.get("imageUrl")
        if not image_url:
            raise SeedanceVideoError("keyframe image is required")
        prompt = DigitalHumanVideoHandler._build_comic_video_prompt(form)
        script = workflow_inputs.get("script-planner") or {}
        if script.get("sceneDescription"):
            prompt = f"{prompt}\nScene focus: {script.get('sceneDescription')}"
        self.backend_client.mark_processing(task_id, progress=65, progress_message="正在生成图生视频片段", trace_id=trace_id)
        video_client = SeedanceVideoClient.from_model_config(model_config)
        result = video_client.generate_video(
            prompt=prompt,
            image=image_url,
            model=model_config.get("modelName"),
            duration="5",
            resolution="480p",
            aspect_ratio="16:9",
            image_size="1024x576",
        )
        return {"videoUrl": result["videoUrl"], "provider": "seedance"}

    def _run_compose(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        task_id: int,
        trace_id: str | None,
    ) -> dict[str, Any]:
        video = workflow_inputs.get("clip-video") or {}
        tts = workflow_inputs.get("tts") or {}
        script = workflow_inputs.get("script-planner") or {}
        video_url = video.get("videoUrl")
        audio_data_url = tts.get("audioDataUrl")
        if not video_url or not audio_data_url:
            raise DigitalHumanPostprocessError("video and audio are required for compose")
        subtitle_text = script.get("subtitleZh") or script.get("dialogue") or tts.get("speechText") or ""
        subtitle_en = script.get("subtitleEn") or ""
        self.backend_client.mark_processing(task_id, progress=85, progress_message="正在烧录字幕并合成成片", trace_id=trace_id)
        final = self.postprocessor.process(
            task_id=task_id,
            video_url=video_url,
            audio_data_url=audio_data_url,
            subtitle_text=subtitle_text,
        )
        markdown = _build_delivery_markdown(
            title=script.get("sceneTitle") or form.get("storyTheme") or "AI 漫剧成片",
            final_video_url=final.video_url,
            subtitle_zh=subtitle_text,
            subtitle_en=subtitle_en,
            image_url=(workflow_inputs.get("keyframe") or {}).get("imageUrl"),
        )
        return {
            "finalVideoUrl": final.video_url,
            "subtitleUrl": final.subtitle_url,
            "videoUrl": final.video_url,
            "markdown": markdown,
            "subtitleZh": subtitle_text,
            "subtitleEn": subtitle_en,
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
    return {
        "sceneTitle": theme,
        "sceneDescription": (
            "Warm cinematic indoor close-up of a gentle elderly grandmother with grey hair and a colorful scarf, "
            "smiling softly at a child in the foreground, traditional wooden door and peeling wall in background, "
            "shallow depth of field, film lighting."
        ),
        "dialogue": "奶就放心了。",
        "subtitleZh": "奶就放心了",
        "subtitleEn": "so Grandma won't worry.",
        "narration": "奶奶慈祥地看着孩子，轻轻说出一句安心的话。",
        "presenterGender": "female",
    }


def _build_delivery_markdown(
    *,
    title: str,
    final_video_url: str,
    subtitle_zh: str,
    subtitle_en: str,
    image_url: str | None,
) -> str:
    lines = [
        f"# {title}",
        "",
        f"[video]({final_video_url})",
        "",
    ]
    if image_url:
        lines.extend([f"![关键帧]({image_url})", ""])
    if subtitle_zh:
        lines.append(f"**{subtitle_zh}**  ")
    if subtitle_en:
        lines.append(f"*{subtitle_en}*")
    lines.extend(["", "> AI 制作", ""])
    return "\n".join(lines)
