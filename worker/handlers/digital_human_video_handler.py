import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.siliconflow_video_client import (
    SiliconFlowVideoClient,
    SiliconFlowVideoError,
    SiliconFlowVideoTimeoutError,
)
from client.skywork_video_client import (
    SkyworkVideoClient,
    SkyworkVideoConfigurationError,
    SkyworkVideoError,
    SkyworkVideoTimeoutError,
)
from config import settings
from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from providers import registry as provider_registry


LOGGER = logging.getLogger(__name__)


class DigitalHumanVideoHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        video_client: SiliconFlowVideoClient | None = None,
        skywork_video_client: SkyworkVideoClient | None = None,
        postprocessor: DigitalHumanPostprocessor | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.video_client = video_client or SiliconFlowVideoClient()
        self.skywork_video_client = skywork_video_client or SkyworkVideoClient()
        self.postprocessor = postprocessor or DigitalHumanPostprocessor()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        LOGGER.info("start processing digital human video task %s", task_id)

        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id)
            self._report(task_id, 8, "数字人任务已启动，正在整理脚本与参数")
            model_config = context.get("modelConfig") or {}
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "DIGITAL_HUMAN")
            provider_registry.require_worker_ready(provider)

            params = context.get("params") or {}
            prompt = self._build_video_prompt(params)
            reference_image = self._optional_string(params.get("referenceImageUrl"))
            speech_text = self._speech_text(params)

            if self._uses_skywork_video():
                avatar_image_url = reference_image
                background_image_url = ""
                audio_data_url = ""
            else:
                self._report(task_id, 18, "正在生成数字人口播音频")
                audio_data_url = self.video_client.generate_speech_data_url(
                    input_text=speech_text,
                    model=self._optional_string(params.get("voiceModel")),
                    voice=self._resolve_voice(params),
                )

                self._report(task_id, 36, "正在生成数字人形象图")
                avatar_image_url = reference_image or self.video_client.generate_image(
                    prompt=self._build_avatar_prompt(params),
                    model=self._optional_string(params.get("imageModel")),
                    image_size="1024x1024",
                )
                background_image_url = ""

            self._report(task_id, 68, "正在合成数字人视频，通常需要 30 秒到 3 分钟")
            result = self._video_generation_client().generate_video(
                prompt=prompt,
                image_size=self._resolve_image_size(params),
                negative_prompt=str(params.get("negativePrompt") or ""),
                model=self._resolve_model(params, avatar_image_url),
                image=avatar_image_url,
                seed=self._optional_int(params.get("seed")),
                duration=str(params.get("duration") or ""),
                aspect_ratio=str(params.get("aspectRatio") or ""),
            )

            if self._uses_skywork_video():
                self._report(task_id, 78, "视频生成成功，正在生成口播音频")
                audio_data_url = self.video_client.generate_speech_data_url(
                    input_text=speech_text,
                    model=self._optional_string(params.get("voiceModel")),
                    voice=self._resolve_voice(params),
                )

            self._report(task_id, 88, "正在合并音频并烧录字幕")
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
                ),
            }
            self.backend_client.mark_success(task_id, success_payload)
            LOGGER.info("digital human video task %s completed successfully", task_id)
            return {"status": "SUCCESS", "taskId": task_id}
        except SiliconFlowVideoTimeoutError as exc:
            return self._mark_failed(task_id, error_code="MODEL_TIMEOUT", error_message=str(exc))
        except SiliconFlowVideoError as exc:
            return self._mark_failed(task_id, error_code="MODEL_CALL_FAILED", error_message=str(exc))
        except SkyworkVideoTimeoutError as exc:
            return self._mark_failed(task_id, error_code="MODEL_TIMEOUT", error_message=str(exc))
        except SkyworkVideoConfigurationError as exc:
            return self._mark_failed(task_id, error_code="MODEL_CALL_FAILED", error_message=str(exc))
        except SkyworkVideoError as exc:
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

    def _video_generation_client(self) -> Any:
        if self._uses_skywork_video():
            return self.skywork_video_client
        return self.video_client

    @staticmethod
    def _uses_skywork_video() -> bool:
        return settings.digital_human_video_provider.lower() == "skywork"

    @staticmethod
    def _build_video_prompt(params: dict[str, Any]) -> str:
        parts = [
            "Create a realistic digital human presenter video.",
            f"Topic: {params.get('videoTopic') or 'digital human presentation'}",
            f"Presenter style: {params.get('avatarStyle') or 'professional presenter'}",
            f"Scene: {params.get('scene') or 'clean studio'}",
            f"Requested duration: {params.get('duration') or '5 seconds'}",
            "The presenter should face the camera, speak naturally, keep stable facial details, and use clean lighting.",
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
        return str(params.get("videoTopic") or "这是一段数字人口播视频。")

    @staticmethod
    def _resolve_voice(params: dict[str, Any]) -> str:
        return str(params.get("voice") or "FunAudioLLM/CosyVoice2-0.5B:alex").strip()

    @staticmethod
    def _build_avatar_prompt(params: dict[str, Any]) -> str:
        avatar_style = params.get("avatarStyle") or "professional presenter"
        brand_name = params.get("brandName") or ""
        return (
            f"High quality digital human avatar, {avatar_style}, half body, facing camera, "
            f"clean commercial look, natural expression, suitable for {brand_name} product presentation."
        )

    @staticmethod
    def _resolve_image_size(params: dict[str, Any]) -> str:
        ratio = str(params.get("aspectRatio") or "").strip()
        if "9:16" in ratio or "竖屏" in ratio or "vertical" in ratio.lower():
            return "720x1280"
        if "1:1" in ratio or "方形" in ratio or "square" in ratio.lower():
            return "960x960"
        return "1280x720"

    @staticmethod
    def _resolve_model(params: dict[str, Any], image: str) -> str:
        requested_model = str(params.get("model") or "").strip()
        if requested_model:
            return requested_model
        if DigitalHumanVideoHandler._uses_skywork_video():
            return settings.skywork_video_model
        if image:
            return settings.siliconflow_image_to_video_model
        return settings.siliconflow_video_model

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
    ) -> str:
        timings = result.get("timings") or {}
        inference = timings.get("inference") if isinstance(timings, dict) else None
        inference_line = f"\n- 推理耗时：{inference}" if inference is not None else ""
        seed_line = f"\n- Seed：{result.get('seed')}" if result.get("seed") is not None else ""
        final_video_line = final_video_url or result["videoUrl"]
        provider_line = f"\n- 视频供应商：{result.get('provider')}" if result.get("provider") else ""
        model_line = f"\n- 视频模型：{result.get('model')}" if result.get("model") else ""

        return (
            "## 数字人视频生成结果\n\n"
            f"- 最终成片：{final_video_line}\n"
            f"- 原始视频链接：{result['videoUrl']}\n"
            f"- 请求 ID：{result['requestId']}\n"
            f"- 状态：{result['status']}"
            f"{provider_line}"
            f"{model_line}"
            f"{seed_line}"
            f"{inference_line}\n\n"
            "## 生成素材\n\n"
            f"- 数字人参考图：{avatar_image_url or '未使用参考图，视频由 Skywork 根据文本提示生成'}\n"
            f"- 背景图：{background_image_url or '未生成独立背景图'}\n"
            f"- 口播音频：<audio controls src=\"{audio_data_url}\"></audio>\n\n"
            "## 字幕草稿\n\n"
            f"```text\n{DigitalHumanVideoHandler._speech_text(params)}\n```\n\n"
            "## 生成信息\n\n"
            f"- 视频主题：{params.get('videoTopic') or ''}\n"
            f"- 数字人形象：{params.get('avatarStyle') or ''}\n"
            f"- 视频场景：{params.get('scene') or ''}\n"
            f"- 画面比例：{params.get('aspectRatio') or ''}\n"
            f"- 视频时长要求：{params.get('duration') or '5 秒'}\n\n"
            "## 实际提交给视频模型的 Prompt\n\n"
            f"```text\n{prompt}\n```\n\n"
            "提示：最终成片已通过 FFmpeg 合并口播音频并烧录字幕；原始视频链接仍保留用于排查。"
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
