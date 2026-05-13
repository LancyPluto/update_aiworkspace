import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.siliconflow_video_client import (
    SiliconFlowVideoClient,
    SiliconFlowVideoError,
    SiliconFlowVideoTimeoutError,
)
from config import settings


LOGGER = logging.getLogger(__name__)


class DigitalHumanVideoHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        video_client: SiliconFlowVideoClient | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.video_client = video_client or SiliconFlowVideoClient()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        LOGGER.info("start processing digital human video task %s", task_id)

        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id)
            self._report(task_id, 8, "数字人任务已启动，正在整理脚本与参数")

            params = context.get("params") or {}
            prompt = self._build_video_prompt(params)
            image = self._optional_string(params.get("referenceImageUrl"))

            self._report(task_id, 18, "正在生成数字人口播音频")
            audio_data_url = self.video_client.generate_speech_data_url(
                input_text=self._speech_text(params),
                model=self._optional_string(params.get("voiceModel")),
                voice=self._resolve_voice(params),
            )

            self._report(task_id, 36, "正在生成数字人形象图")
            avatar_image_url = image or self.video_client.generate_image(
                prompt=self._build_avatar_prompt(params),
                model=self._optional_string(params.get("imageModel")),
                image_size="1024x1024",
            )

            self._report(task_id, 52, "正在生成视频背景画面")
            background_image_url = self.video_client.generate_image(
                prompt=self._build_background_prompt(params),
                model=self._optional_string(params.get("imageModel")),
                image_size="1280x720",
            )

            self._report(task_id, 68, "正在合成数字人视频，通常需要 30 秒到 3 分钟")
            result = self.video_client.generate_video(
                prompt=prompt,
                image_size=self._resolve_image_size(params),
                negative_prompt=str(params.get("negativePrompt") or ""),
                model=self._resolve_model(params, avatar_image_url),
                image=avatar_image_url,
                seed=self._optional_int(params.get("seed")),
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
                ),
            }
            self.backend_client.mark_success(task_id, success_payload)
            LOGGER.info("digital human video task %s completed successfully", task_id)
            return {"status": "SUCCESS", "taskId": task_id}
        except SiliconFlowVideoTimeoutError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_TIMEOUT",
                error_message=str(exc),
            )
        except SiliconFlowVideoError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_CALL_FAILED",
                error_message=str(exc),
            )
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(
                task_id,
                error_code="WORKER_INTERNAL_ERROR",
                error_message=str(exc),
            )

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

    @staticmethod
    def _build_video_prompt(params: dict[str, Any]) -> str:
        parts = [
            "Create a realistic digital human presenter video.",
            f"Topic: {params.get('videoTopic') or 'digital human presentation'}",
            f"Presenter style: {params.get('avatarStyle') or 'professional presenter'}",
            f"Scene: {params.get('scene') or 'clean studio'}",
            f"Requested duration: {params.get('duration') or '5 秒'}",
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
    def _build_background_prompt(params: dict[str, Any]) -> str:
        scene = params.get("scene") or "clean studio"
        visual_requirements = params.get("visualRequirements") or ""
        return (
            f"Professional video background for a digital human presenter, scene: {scene}. "
            f"Clean lighting, product presentation space, no text overlays. {visual_requirements}"
        )

    @staticmethod
    def _resolve_image_size(params: dict[str, Any]) -> str:
        ratio = str(params.get("aspectRatio") or "").strip()
        if "9:16" in ratio or "竖屏" in ratio:
            return "720x1280"
        if "1:1" in ratio or "方形" in ratio:
            return "960x960"
        return "1280x720"

    @staticmethod
    def _resolve_model(params: dict[str, Any], image: str) -> str:
        requested_model = str(params.get("model") or "").strip()
        if requested_model:
            return requested_model
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
    ) -> str:
        timings = result.get("timings") or {}
        inference = timings.get("inference") if isinstance(timings, dict) else None
        inference_line = f"\n- 推理耗时：{inference}" if inference is not None else ""
        seed_line = f"\n- Seed：{result.get('seed')}" if result.get("seed") is not None else ""

        return (
            "## 数字人视频生成结果\n\n"
            f"- 视频链接：{result['videoUrl']}\n"
            f"- 请求 ID：{result['requestId']}\n"
            f"- 状态：{result['status']}"
            f"{seed_line}"
            f"{inference_line}\n\n"
            "## 生成素材\n\n"
            f"- 数字人形象图：{avatar_image_url}\n"
            f"- 背景图：{background_image_url}\n"
            f"- 口播音频：<audio controls src=\"{audio_data_url}\"></audio>\n\n"
            "## 字幕草稿\n\n"
            f"```text\n{DigitalHumanVideoHandler._speech_text(params)}\n```\n\n"
            "> 当前硅基流动视频接口不接收音频入参，本结果已输出视频、口播音频和字幕草稿。"
            "如需得到已烧录字幕并合成音频的最终 MP4，需要接入文件存储与 FFmpeg 后处理。\n\n"
            "## 生成信息\n\n"
            f"- 视频主题：{params.get('videoTopic') or ''}\n"
            f"- 数字人形象：{params.get('avatarStyle') or ''}\n"
            f"- 视频场景：{params.get('scene') or ''}\n"
            f"- 画面比例：{params.get('aspectRatio') or ''}\n\n"
            f"- 视频时长要求：{params.get('duration') or '5 秒'}\n\n"
            "## 实际提交给视频模型的 Prompt\n\n"
            f"```text\n{prompt}\n```\n\n"
            "提示：硅基流动返回的视频链接有有效期，请及时下载或转存。"
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
