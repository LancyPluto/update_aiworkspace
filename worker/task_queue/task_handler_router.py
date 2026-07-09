import logging
import threading
import uuid
from contextlib import nullcontext
from typing import Any

from client.backend_client import BackendClient, BackendClientError, backend_claim_context
from config import settings
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from handlers.image_generation_handler import ImageGenerationHandler
from handlers.music_generation_handler import MusicGenerationHandler
from handlers.text_task_handler import TextTaskHandler
from handlers.text_to_speech_handler import TextToSpeechHandler
from handlers.subject_sync_handler import SubjectSyncHandler
from handlers.video_generation_handler import VideoGenerationHandler
from handlers.workflow_step_handler import WorkflowStepHandler
from observability.log_context import log_trace_context


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}
ACTIVE_QUEUE_STATUSES = {"QUEUED", "CREATED", "RETRYING"}


class TaskHandlerRouter:
    def __init__(
        self,
        text_handler: TextTaskHandler | None = None,
        digital_human_handler: DigitalHumanVideoHandler | None = None,
        image_generation_handler: ImageGenerationHandler | None = None,
        music_generation_handler: MusicGenerationHandler | None = None,
        text_to_speech_handler: TextToSpeechHandler | None = None,
        video_generation_handler: VideoGenerationHandler | None = None,
        subject_sync_handler: SubjectSyncHandler | None = None,
        workflow_step_handler: WorkflowStepHandler | None = None,
        backend_client: BackendClient | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.text_handler = text_handler or TextTaskHandler(backend_client=self.backend_client)
        self.digital_human_handler = digital_human_handler or DigitalHumanVideoHandler(backend_client=self.backend_client)
        self.image_generation_handler = image_generation_handler or ImageGenerationHandler(backend_client=self.backend_client)
        self.music_generation_handler = music_generation_handler or MusicGenerationHandler(backend_client=self.backend_client)
        self.text_to_speech_handler = text_to_speech_handler or TextToSpeechHandler(backend_client=self.backend_client)
        self.video_generation_handler = video_generation_handler or VideoGenerationHandler(backend_client=self.backend_client)
        self.subject_sync_handler = subject_sync_handler or SubjectSyncHandler()
        self.workflow_step_handler = workflow_step_handler or WorkflowStepHandler(backend_client=self.backend_client)

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        with log_trace_context(message.get("traceId")):
            return self._handle(message)

    def _handle(self, message: dict[str, Any]) -> dict[str, Any]:
        message_type = str(message.get("messageType") or "").strip().lower()
        if message_type in {"subject_sync", "subject_delete"}:
            return self.subject_sync_handler.handle(message)

        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        claim_token = uuid.uuid4().hex
        claim = self._claim_task(task_id, claim_token, trace_id)
        if not claim.get("claimed", True):
            LOGGER.info(
                "skip unclaimed task taskId=%s status=%s reason=%s traceId=%s",
                task_id,
                claim.get("status"),
                claim.get("reason"),
                trace_id or "-",
            )
            return {
                "status": "SKIPPED",
                "taskId": task_id,
                "taskStatus": claim.get("status"),
                "reason": claim.get("reason", "claim_denied"),
                "traceId": trace_id,
            }
        active_claim_token = claim.get("claimToken") or claim_token
        if claim.get("reason") == "client_without_claim":
            active_claim_token = None

        context = self.backend_client.get_execution_context(task_id, trace_id=trace_id)
        status = str(context.get("status") or "").upper()
        if status in TERMINAL_TASK_STATUSES:
            LOGGER.info("skip terminal task taskId=%s status=%s", message.get("taskId"), status)
            return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status}
        if status in ACTIVE_QUEUE_STATUSES:
            try:
                processing_kwargs: dict[str, Any] = {
                    "progress": 8,
                    "progress_message": "正在生成中",
                    "trace_id": trace_id,
                }
                if active_claim_token:
                    processing_kwargs["claim_token"] = active_claim_token
                self.backend_client.mark_processing(task_id, **processing_kwargs)
            except BackendClientError:
                LOGGER.warning("failed to mark task processing at dequeue taskId=%s", task_id, exc_info=True)
        routed_message = {**message, "__executionContext": context, "__claimToken": active_claim_token}
        lease_context = (
            _LeaseRenewer(self.backend_client, task_id, active_claim_token, trace_id)
            if active_claim_token
            else nullcontext()
        )
        with backend_claim_context(active_claim_token), lease_context:
            params = context.get("params") or {}
            if params.get("workflowStep"):
                return self.workflow_step_handler.handle(routed_message)
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
            if context.get("toolCode") == "digital_human_agent":
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

    def _claim_task(self, task_id: int, claim_token: str, trace_id: str | None) -> dict[str, Any]:
        claim_fn = getattr(self.backend_client, "claim_task", None)
        if not callable(claim_fn):
            LOGGER.warning("backend client has no claim_task method; running task without lease taskId=%s", task_id)
            return {"claimed": True, "taskId": task_id, "claimToken": None, "reason": "client_without_claim"}
        return claim_fn(task_id, worker_id=settings.worker_id, claim_token=claim_token, trace_id=trace_id)


class _LeaseRenewer:
    def __init__(self, backend_client: BackendClient, task_id: int, claim_token: str, trace_id: str | None) -> None:
        self.backend_client = backend_client
        self.task_id = task_id
        self.claim_token = claim_token
        self.trace_id = trace_id
        self.interval = max(5.0, float(settings.worker_lease_renew_interval_seconds))
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, name=f"lease-renew-{task_id}", daemon=True)

    def __enter__(self) -> "_LeaseRenewer":
        self.thread.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop_event.set()
        self.thread.join(timeout=1.0)

    def _run(self) -> None:
        while not self.stop_event.wait(self.interval):
            try:
                self.backend_client.renew_lease(
                    self.task_id,
                    worker_id=settings.worker_id,
                    claim_token=self.claim_token,
                    trace_id=self.trace_id,
                )
            except Exception:
                LOGGER.warning("failed to renew task lease taskId=%s traceId=%s", self.task_id, self.trace_id or "-", exc_info=True)
