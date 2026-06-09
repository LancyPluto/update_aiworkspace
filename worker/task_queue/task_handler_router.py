import logging
from typing import Any

from client.backend_client import BackendClient
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from handlers.image_generation_handler import ImageGenerationHandler
from handlers.music_generation_handler import MusicGenerationHandler
from handlers.text_task_handler import TextTaskHandler
from handlers.text_to_speech_handler import TextToSpeechHandler
from handlers.video_generation_handler import VideoGenerationHandler


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class TaskHandlerRouter:
    def __init__(
        self,
        text_handler: TextTaskHandler | None = None,
        digital_human_handler: DigitalHumanVideoHandler | None = None,
        image_generation_handler: ImageGenerationHandler | None = None,
        music_generation_handler: MusicGenerationHandler | None = None,
        text_to_speech_handler: TextToSpeechHandler | None = None,
        video_generation_handler: VideoGenerationHandler | None = None,
        backend_client: BackendClient | None = None,
    ) -> None:
        self.text_handler = text_handler or TextTaskHandler()
        self.digital_human_handler = digital_human_handler or DigitalHumanVideoHandler()
        self.image_generation_handler = image_generation_handler or ImageGenerationHandler()
        self.music_generation_handler = music_generation_handler or MusicGenerationHandler()
        self.text_to_speech_handler = text_to_speech_handler or TextToSpeechHandler()
        self.video_generation_handler = video_generation_handler or VideoGenerationHandler()
        self.backend_client = backend_client or BackendClient()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        context = self.backend_client.get_execution_context(int(message["taskId"]))
        status = str(context.get("status") or "").upper()
        if status in TERMINAL_TASK_STATUSES:
            LOGGER.info("skip terminal task taskId=%s status=%s", message.get("taskId"), status)
            return {"status": "SKIPPED", "taskId": int(message["taskId"]), "taskStatus": status}
        routed_message = {**message, "__executionContext": context}
        handler = str(context.get("executionHandler") or "").upper()
        if handler == "DIGITAL_HUMAN":
            return self.digital_human_handler.handle(routed_message)
        if handler == "IMAGE_GENERATION":
            return self.image_generation_handler.handle(routed_message)
        if handler == "MUSIC_GENERATION":
            return self.music_generation_handler.handle(routed_message)
        if handler == "TEXT_TO_SPEECH":
            return self.text_to_speech_handler.handle(routed_message)
        if handler == "VIDEO_GENERATION":
            return self.video_generation_handler.handle(routed_message)
        if context.get("toolCode") in {"digital_human_agent", "ai_comic_drama_agent"}:
            return self.digital_human_handler.handle(routed_message)
        tool_code = str(context.get("toolCode") or "").strip().lower()
        if tool_code in {"suno", "suno_music"}:
            return self.music_generation_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "IMAGE_GENERATION":
            return self.image_generation_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "MUSIC_GENERATION":
            return self.music_generation_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "TEXT_TO_SPEECH":
            return self.text_to_speech_handler.handle(routed_message)
        if str(context.get("toolType") or "").upper() == "VIDEO_GENERATION":
            return self.video_generation_handler.handle(routed_message)
        return self.text_handler.handle(routed_message)
