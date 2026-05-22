import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.infinitetalk_video_client import InfiniteTalkVideoClient, InfiniteTalkVideoError, InfiniteTalkVideoTimeoutError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from client.siliconflow_video_client import SiliconFlowVideoClient, SiliconFlowVideoError, SiliconFlowVideoTimeoutError
from config import resolve_infinitetalk_api_key, resolve_siliconflow_api_key, settings
from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from providers import registry as provider_registry


LOGGER = logging.getLogger(__name__)


class DigitalHumanVideoHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        video_client: SiliconFlowVideoClient | None = None,
        seedance_video_client: SeedanceVideoClient | None = None,
        infinitetalk_video_client: InfiniteTalkVideoClient | None = None,
        postprocessor: DigitalHumanPostprocessor | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.video_client = video_client
        self.seedance_video_client = seedance_video_client or SeedanceVideoClient()
        self.infinitetalk_video_client = infinitetalk_video_client
        self.postprocessor = postprocessor or DigitalHumanPostprocessor()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        LOGGER.info("start processing digital human video task %s", task_id)

        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id)
            self._report(task_id, 8, "任务已启动，正在整理脚本与参数")
            model_config = context.get("modelConfig") or {}
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "siliconflow_images").lower()
            if provider not in {"siliconflow", "siliconflow_images", "seedance", "infinitetalk"}:
                provider = "siliconflow_images"
            provider_registry.require_capability(provider, "DIGITAL_HUMAN")
            provider_registry.require_worker_ready(provider)

            siliconflow_client = self._siliconflow_client(model_config)

            params = context.get("params") or {}
            prompt = self._build_video_prompt(params)
            reference_image = self._optional_string(params.get("referenceImageUrl"))
            speech_text = self._speech_text(params)
            presenter_gender = self._resolve_presenter_gender(params)
            voice = self._resolve_voice(params, presenter_gender)

            self._report(task_id, 18, "正在通过硅基流动生成口播音频")
            audio_data_url = siliconflow_client.generate_speech_data_url(
                input_text=speech_text,
                model=self._optional_string(params.get("voiceModel")),
                voice=voice,
            )

            self._report(task_id, 36, "正在通过硅基流动生成数字人形象图")
            avatar_image_url = reference_image or siliconflow_client.generate_image(
                prompt=self._build_avatar_prompt(params),
                model=self._optional_string(params.get("imageModel")),
                image_size=self._resolve_reference_image_size(params),
            )
            background_image_url = ""

            if provider == "infinitetalk":
                self._report(task_id, 68, "正在通过 InfiniteTalk 生成音频驱动数字人成片")
            else:
                self._report(task_id, 68, "正在通过 Seedance 合成图生视频，通常需要 30 秒到 3 分钟")
            result = self._generate_video(provider, model_config, params, prompt, avatar_image_url, audio_data_url)

            if provider == "infinitetalk":
                self._report(task_id, 92, "InfiniteTalk 已返回音频驱动成片，正在整理输出")
                success_payload = {
                    "resourceType": "MARKDOWN",
                    "contentText": self._build_result_markdown(
                        params,
                        prompt,
                        result,
                        audio_data_url=audio_data_url,
                        avatar_image_url=avatar_image_url,
                        background_image_url=background_image_url,
                        final_video_url=result["videoUrl"],
                        subtitle_url="",
                        presenter_gender=presenter_gender,
                        voice=voice,
                    ),
                    "billableUnits": 1,
                }
                self.backend_client.mark_success(task_id, success_payload)
                LOGGER.info("digital human InfiniteTalk task %s completed successfully", task_id)
                return {"status": "SUCCESS", "taskId": task_id, "provider": provider}

            self._report(task_id, 88, "正在通过 FFmpeg 合并音频并烧录字幕")
            final_video = self.postprocessor.process(
                task_id=task_id,
                video_url=result["videoUrl"],
                audio_data_url=audio_data_url,
                subtitle_text=speech_text,
            )

            self._report(task_id, 94, "正在整理字幕与输出文件")
            success_payload = {
                "resourceType": "MARKDOWN",
                "contentText": self._build_result_markdown(
                    params,
                    prompt,
                    result,
                    audio_data_url=audio_data_url,
                    avatar_image_url=avatar_image_url,
                    background_image_url=background_image_url,
                    final_video_url=final_video.video_url,
                    subtitle_url=final_video.subtitle_url,
                    presenter_gender=presenter_gender,
                    voice=voice,
                ),
            }
            self.backend_client.mark_success(task_id, success_payload)
            LOGGER.info("digital human video task %s completed successfully", task_id)
            return {"status": "SUCCESS", "taskId": task_id}
        except SiliconFlowVideoTimeoutError as exc:
            return self._mark_failed(task_id, error_code="MODEL_TIMEOUT", error_message=str(exc))
        except SiliconFlowVideoError as exc:
            return self._mark_failed(task_id, error_code="MODEL_CALL_FAILED", error_message=str(exc))
        except SeedanceVideoTimeoutError as exc:
            return self._mark_failed(task_id, error_code="MODEL_TIMEOUT", error_message=str(exc))
        except SeedanceVideoError as exc:
            return self._mark_failed(task_id, error_code="MODEL_CALL_FAILED", error_message=str(exc))
        except InfiniteTalkVideoTimeoutError as exc:
            return self._mark_failed(task_id, error_code="MODEL_TIMEOUT", error_message=str(exc))
        except InfiniteTalkVideoError as exc:
            return self._mark_failed(task_id, error_code="MODEL_CALL_FAILED", error_message=str(exc))
        except DigitalHumanPostprocessError as exc:
            return self._mark_failed(task_id, error_code="POSTPROCESS_FAILED", error_message=str(exc))
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(task_id, error_code="WORKER_INTERNAL_ERROR", error_message=str(exc))

    def _mark_failed(self, task_id: int, *, error_code: str, error_message: str) -> dict[str, Any]:
        LOGGER.exception("digital human video task %s failed: %s", task_id, error_message)
        self.backend_client.mark_failed(
            task_id,
            {
                "errorCode": error_code,
                "errorMessage": error_message,
            },
        )
        return {"status": "FAILED", "taskId": task_id, "errorCode": error_code}

    def _report(self, task_id: int, progress: int, message: str) -> None:
        self.backend_client.mark_processing(
            task_id,
            progress=progress,
            progress_message=message,
        )

    def _siliconflow_client(self, model_config: dict[str, Any]) -> SiliconFlowVideoClient:
        if self.video_client is not None:
            return self.video_client
        provider = str(model_config.get("provider") or "").lower()
        return SiliconFlowVideoClient(
            base_url=self._optional_string(model_config.get("baseUrl")) if provider.startswith("siliconflow") else None,
            api_key=resolve_siliconflow_api_key(model_config if provider.startswith("siliconflow") else None),
        )

    def _video_generation_client(self) -> SeedanceVideoClient:
        return self.seedance_video_client

    def _infinitetalk_client(self, model_config: dict[str, Any]) -> InfiniteTalkVideoClient:
        if self.infinitetalk_video_client is not None:
            return self.infinitetalk_video_client
        return InfiniteTalkVideoClient(
            base_url=self._optional_string(model_config.get("baseUrl")) or None,
            api_key=resolve_infinitetalk_api_key(model_config),
            timeout_seconds=model_config.get("timeoutSeconds"),
        )

    def _generate_video(
        self,
        provider: str,
        model_config: dict[str, Any],
        params: dict[str, Any],
        prompt: str,
        avatar_image_url: str,
        audio_data_url: str,
    ) -> dict[str, Any]:
        common = {
            "prompt": prompt,
            "negative_prompt": str(params.get("negativePrompt") or ""),
            "model": self._resolve_model(params, model_config, provider),
            "image": avatar_image_url,
            "audio_data_url": audio_data_url,
            "seed": self._optional_int(params.get("seed")),
            "duration": str(params.get("duration") or ""),
            "aspect_ratio": str(params.get("aspectRatio") or ""),
            "resolution": self._resolve_resolution(params),
        }
        if provider == "infinitetalk":
            return self._infinitetalk_client(model_config).generate_video(
                **common,
                source_video=self._optional_string(params.get("sourceVideoUrl") or params.get("referenceVideoUrl")),
                mode=str(params.get("mode") or "streaming"),
            )
        return self._video_generation_client().generate_video(
            **common,
            image_size=self._resolve_image_size(params),
        )

    @staticmethod
    def _build_video_prompt(params: dict[str, Any]) -> str:
        if DigitalHumanVideoHandler._is_comic_drama_params(params):
            return DigitalHumanVideoHandler._build_comic_video_prompt(params)
        presenter_gender = DigitalHumanVideoHandler._resolve_presenter_gender(params)
        parts = [
            "Create a realistic digital human presenter video.",
            f"Topic: {params.get('videoTopic') or 'digital human presentation'}",
            f"Presenter style: {params.get('avatarStyle') or 'professional presenter'}",
            f"Presenter gender: {presenter_gender}. Keep the visual gender consistent with the selected voice.",
            f"Scene: {params.get('scene') or 'clean studio'}",
            f"Requested duration: {params.get('duration') or '5 seconds'}",
            f"Target resolution: {DigitalHumanVideoHandler._resolve_resolution(params)}",
            "The presenter should face the camera, speak naturally, keep stable facial details, and use clean lighting.",
            "Use the supplied driving audio to align mouth shapes, speech rhythm, facial motion, and subtitle timing.",
        ]
        brand_name = str(params.get("brandName") or "").strip()
        if brand_name:
            parts.append(f"Brand or product: {brand_name}")

        script = str(params.get("script") or "").strip()
        if script:
            parts.append(f"Voiceover script content: {script}")

        visual_requirements = str(params.get("visualRequirements") or "").strip()
        if visual_requirements:
            parts.append(f"Visual requirements: {visual_requirements}")

        return "\n".join(parts)

    @staticmethod
    def _speech_text(params: dict[str, Any]) -> str:
        script = str(params.get("script") or "").strip()
        if script:
            return script[:1000]
        plot_outline = str(params.get("plotOutline") or "").strip()
        if plot_outline:
            return plot_outline[:1000]
        story_theme = str(params.get("storyTheme") or "").strip()
        if story_theme:
            return f"本集主题：{story_theme}。"
        return str(params.get("videoTopic") or "这是一段数字人口播视频。")

    @staticmethod
    def _resolve_voice(params: dict[str, Any], presenter_gender: str | None = None) -> str:
        requested = str(params.get("voice") or "").strip()
        if requested:
            return requested
        gender = presenter_gender or DigitalHumanVideoHandler._resolve_presenter_gender(params)
        voice_id = "anna" if gender == "female" else "alex"
        return f"{settings.siliconflow_voice_model}:{voice_id}"

    @staticmethod
    def _resolve_presenter_gender(params: dict[str, Any]) -> str:
        explicit = str(
            params.get("presenterGender")
            or params.get("gender")
            or params.get("voiceGender")
            or ""
        ).strip().lower()
        if any(token in explicit for token in ("female", "woman", "女")):
            return "female"
        if any(token in explicit for token in ("male", "masculine", "男")):
            return "male"

        text = " ".join(
            str(params.get(key) or "")
            for key in ("avatarStyle", "visualRequirements", "script", "videoTopic")
        ).lower()
        if any(token in text for token in ("female", "woman", "girl", "lady", "女", "女性", "女声", "女士", "小姐姐", "姐姐")):
            return "female"
        if any(token in text for token in ("male", "masculine", "gentleman", "boy", "男", "男性", "男声", "先生", "大叔")):
            return "male"
        return "female"

    @staticmethod
    def _build_avatar_prompt(params: dict[str, Any]) -> str:
        if DigitalHumanVideoHandler._is_comic_drama_params(params):
            return DigitalHumanVideoHandler._build_comic_image_prompt(params)
        presenter_gender = DigitalHumanVideoHandler._resolve_presenter_gender(params)
        gender_phrase = "female presenter" if presenter_gender == "female" else "male presenter"
        avatar_style = params.get("avatarStyle") or "professional presenter"
        brand_name = params.get("brandName") or ""
        scene = params.get("scene") or "clean studio"
        return (
            f"High quality digital human avatar, {gender_phrase}, {avatar_style}, half body, facing camera, "
            f"natural expression, commercial lighting, {scene}, suitable for {brand_name} product presentation."
        )

    @staticmethod
    def _is_comic_drama_params(params: dict[str, Any]) -> bool:
        return bool(params.get("storyTheme") or params.get("plotOutline") or params.get("visualStyle"))

    @staticmethod
    def _build_comic_video_prompt(params: dict[str, Any]) -> str:
        return "\n".join(
            [
                "Create a short AI comic-drama image-to-video clip from the supplied key frame.",
                f"Story theme: {params.get('storyTheme') or 'comic drama story'}",
                f"Genre: {params.get('genre') or 'dramatic short series'}",
                f"Target audience: {params.get('targetAudience') or 'short drama audience'}",
                f"Plot outline: {params.get('plotOutline') or ''}",
                f"Main characters: {params.get('mainCharacters') or ''}",
                f"Visual style: {params.get('visualStyle') or 'cinematic comic style'}",
                f"Episode duration target: {params.get('episodeDuration') or params.get('duration') or '5 seconds'}",
                f"Target resolution: {DigitalHumanVideoHandler._resolve_resolution(params)}",
                "Animate the key frame with subtle camera movement, expressive character motion, and clear story mood.",
                "Avoid gore, explicit content, copyrighted characters, unstable faces, text artifacts, and distorted hands.",
            ]
        )

    @staticmethod
    def _build_comic_image_prompt(params: dict[str, Any]) -> str:
        return (
            "High quality key frame for an AI comic drama, "
            f"theme: {params.get('storyTheme') or 'short drama'}, "
            f"genre: {params.get('genre') or 'dramatic'}, "
            f"characters: {params.get('mainCharacters') or 'main character with expressive face'}, "
            f"plot: {params.get('plotOutline') or ''}, "
            f"visual style: {params.get('visualStyle') or 'cinematic comic illustration'}, "
            "clear composition, dramatic lighting, no text, no watermark."
        )

    @staticmethod
    def _resolve_image_size(params: dict[str, Any]) -> str:
        resolution = DigitalHumanVideoHandler._resolve_resolution(params)
        ratio = str(params.get("aspectRatio") or "").strip()
        if "9:16" in ratio or "竖屏" in ratio or "vertical" in ratio.lower():
            return "480x854" if resolution == "480p" else "720x1280"
        if "1:1" in ratio or "方形" in ratio or "square" in ratio.lower():
            return "480x480" if resolution == "480p" else "960x960"
        return "854x480" if resolution == "480p" else "1280x720"

    @staticmethod
    def _resolve_reference_image_size(params: dict[str, Any]) -> str:
        ratio = str(params.get("aspectRatio") or "").strip()
        if "9:16" in ratio or "竖屏" in ratio or "vertical" in ratio.lower():
            return "768x1024"
        if "1:1" in ratio or "方形" in ratio or "square" in ratio.lower():
            return "1024x1024"
        return "1024x768"

    @staticmethod
    def _resolve_resolution(params: dict[str, Any]) -> str:
        raw = str(params.get("resolution") or params.get("quality") or "480p").strip().lower()
        if "720" in raw or "高清" in raw or "hd" in raw:
            return "720p"
        return "480p"

    @staticmethod
    def _resolve_model(
        params: dict[str, Any],
        model_config: dict[str, Any] | None = None,
        provider: str = "",
    ) -> str:
        requested_model = str(params.get("model") or "").strip()
        if requested_model:
            return requested_model
        configured_model = str((model_config or {}).get("modelName") or "").strip()
        if configured_model and provider in {"seedance", "infinitetalk"}:
            return configured_model
        return settings.seedance_video_model

    @staticmethod
    def _build_result_markdown(
        params: dict[str, Any],
        prompt: str,
        result: dict[str, Any],
        *,
        audio_data_url: str,
        avatar_image_url: str,
        background_image_url: str,
        final_video_url: str = "",
        subtitle_url: str = "",
        presenter_gender: str = "",
        voice: str = "",
    ) -> str:
        timings = result.get("timings") or {}
        inference = timings.get("inference") if isinstance(timings, dict) else None
        inference_line = f"\n- 推理耗时：{inference}" if inference is not None else ""
        seed_line = f"\n- Seed：{result.get('seed')}" if result.get("seed") is not None else ""
        final_video_line = final_video_url or result["videoUrl"]
        subtitle_line = f"- 字幕文件：{subtitle_url}\n" if subtitle_url else ""
        provider_line = f"\n- 视频供应商：{result.get('provider')}" if result.get("provider") else ""
        model_line = f"\n- 视频模型：{result.get('model')}" if result.get("model") else ""
        resolution_line = f"\n- 视频清晰度：{result.get('resolution')}" if result.get("resolution") else ""
        hint_line = (
            "提示：InfiniteTalk 已直接返回音频驱动成片；为避免再次合成导致口型漂移，已跳过 FFmpeg 合并与烧录字幕。"
            if result.get("provider") == "infinitetalk"
            else "提示：最终成片已通过 FFmpeg 合并口播音频并烧录字幕；原始视频链接保留用于排查。"
        )

        return (
            "## 数字人视频生成结果\n\n"
            f"- 最终成片：{final_video_line}\n"
            f"{subtitle_line}"
            f"- 原始视频链接：{result['videoUrl']}\n"
            f"- 请求 ID：{result['requestId']}\n"
            f"- 状态：{result['status']}"
            f"{provider_line}"
            f"{model_line}"
            f"{resolution_line}"
            f"{seed_line}"
            f"{inference_line}\n\n"
            "## 生成素材\n\n"
            f"- 数字人参考图：{avatar_image_url or '未使用参考图'}\n"
            f"- 背景图：{background_image_url or '未生成独立背景图'}\n"
            f"- 口播音频：<audio controls src=\"{audio_data_url}\"></audio>\n\n"
            "## 字幕草稿\n\n"
            f"```text\n{DigitalHumanVideoHandler._speech_text(params)}\n```\n\n"
            "## 生成信息\n\n"
            f"- 视频主题：{params.get('videoTopic') or ''}\n"
            f"- 数字人形象：{params.get('avatarStyle') or ''}\n"
            f"- 讲述人性别：{presenter_gender or DigitalHumanVideoHandler._resolve_presenter_gender(params)}\n"
            f"- 语音音色：{voice or DigitalHumanVideoHandler._resolve_voice(params)}\n"
            f"- 视频场景：{params.get('scene') or ''}\n"
            f"- 画面比例：{params.get('aspectRatio') or ''}\n"
            f"- 视频清晰度：{DigitalHumanVideoHandler._resolve_resolution(params)}\n"
            f"- 视频时长要求：{params.get('duration') or '5 秒'}\n\n"
            "## 实际提交给视频模型的 Prompt\n\n"
            f"```text\n{prompt}\n```\n\n"
            f"{hint_line}"
        )

    @staticmethod
    def _optional_string(value: Any) -> str:
        return str(value).strip() if value is not None else ""

    @staticmethod
    def _optional_int(value: Any) -> int | None:
        if value is None or value == "":
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None
