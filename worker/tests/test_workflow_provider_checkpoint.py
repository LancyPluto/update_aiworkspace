import json

import pytest
import requests

import handlers.workflow_step_handler as workflow_step_handler
from client.backend_client import BackendClient, BackendClientError
from client.text_to_speech_client import SpeechGenerationResult
from handlers.workflow_step_handler import WorkflowStepHandler


class RecordingBackendClient:
    def __init__(self, context: dict):
        self.context = context
        self.checkpoint_updates: list[dict] = []
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict:
        return self.context

    def mark_processing(self, *args, **kwargs):
        return {}

    def save_provider_checkpoint(
        self,
        task_id: int,
        checkpoint: dict,
        *,
        expected_version: int,
        trace_id: str | None = None,
        claim_token: str | None = None,
    ) -> dict:
        update = {
            "taskId": task_id,
            "checkpoint": json.loads(json.dumps(checkpoint)),
            "expectedVersion": expected_version,
            "traceId": trace_id,
            "claimToken": claim_token,
        }
        self.checkpoint_updates.append(update)
        return {
            "checkpoint": update["checkpoint"],
            "version": expected_version + 1,
        }

    def mark_success(self, task_id: int, payload: dict, trace_id: str | None = None):
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict, trace_id: str | None = None):
        self.failed_payload = payload
        return {}


class FakeVideoPersister:
    def persist_video_url(self, *, task_id: int, source_url: str, index: int = 1) -> dict[str, str]:
        return {
            "url": f"/generated/video/{task_id}/video-{index}.mp4",
            "sourceUrl": source_url,
        }


class FakeAudioPersister:
    def persist_audio_url(
        self,
        *,
        task_id: int,
        source_url: str,
        extension: str | None = None,
        index: int = 1,
    ) -> dict[str, str]:
        suffix = extension or "wav"
        return {
            "url": f"/generated/audio/{task_id}/voice-{index}.{suffix}",
            "sourceUrl": source_url,
        }

    def persist_audio_bytes(
        self,
        *,
        task_id: int,
        audio_bytes: bytes,
        content_type: str | None = None,
        extension: str | None = None,
        index: int = 1,
    ) -> dict[str, str]:
        suffix = extension or "mp3"
        return {
            "url": f"/generated/audio/{task_id}/voice-{index}.{suffix}",
            "sourceUrl": "",
        }


def test_backend_client_sends_checkpoint_cas_contract(monkeypatch):
    class Response:
        status_code = 200
        text = "ok"

        def raise_for_status(self):
            return None

        def json(self):
            return {
                "code": "SUCCESS",
                "data": {"checkpoint": {"kind": "WORKFLOW_VIDEO"}, "version": 3},
            }

    captured = {}
    client = BackendClient()

    def fake_request(method, path, *, json_body, timeout, trace_id=None):
        captured.update(
            {
                "method": method,
                "path": path,
                "jsonBody": json_body,
                "timeout": timeout,
                "traceId": trace_id,
            }
        )
        return Response()

    monkeypatch.setattr(client, "_request", fake_request)

    result = client.save_provider_checkpoint(
        701,
        {"kind": "WORKFLOW_VIDEO"},
        expected_version=2,
        trace_id="trace-701",
        claim_token="claim-701",
    )

    assert result["version"] == 3
    assert captured == {
        "method": "POST",
        "path": "/api/internal/v1/tasks/701/provider-checkpoint",
        "jsonBody": {
            "checkpoint": {"kind": "WORKFLOW_VIDEO"},
            "expectedVersion": 2,
            "claimToken": "claim-701",
        },
        "timeout": client.timeout,
        "traceId": "trace-701",
    }


def test_backend_client_retries_checkpoint_after_response_connection_loss(monkeypatch):
    class Response:
        status_code = 200
        text = "ok"

        def raise_for_status(self):
            return None

        def json(self):
            return {
                "code": "SUCCESS",
                "data": {"checkpoint": {"kind": "WORKFLOW_VIDEO"}, "version": 3},
            }

    calls = []
    client = BackendClient()

    def flaky_request(*args, **kwargs):
        calls.append((args, kwargs))
        if len(calls) == 1:
            raise requests.ConnectionError("response lost after checkpoint commit")
        return Response()

    monkeypatch.setattr(client, "_request", flaky_request)
    monkeypatch.setattr("client.backend_client.time.sleep", lambda _seconds: None)

    result = client.save_provider_checkpoint(
        701,
        {"kind": "WORKFLOW_VIDEO"},
        expected_version=2,
        trace_id="trace-701",
        claim_token="claim-701",
    )

    assert result["version"] == 3
    assert len(calls) == 2


def _execution_context(*, checkpoint: dict | None = None, checkpoint_version: int = 0) -> dict:
    return {
        "taskId": 701,
        "status": "PROCESSING",
        "providerCheckpoint": checkpoint,
        "providerCheckpointVersion": checkpoint_version,
        "params": {
            "workflowStep": True,
            "nodeDefType": "VIDEO_MODEL",
            "workflowInputs": {
                "script-planner": {
                    "scenes": [
                        {
                            "index": 1,
                            "sceneDescription": "A presenter walks into a bright studio.",
                        }
                    ]
                },
                "keyframe": {
                    "images": [
                        {
                            "sceneIndex": 1,
                            "imageUrl": "https://example.com/frame-1.png",
                        }
                    ]
                },
            },
        },
        "modelConfig": {
            "provider": "agnes_video",
            "modelName": "agnes-video-v2.0",
            "apiKey": "test-key",
            "billingUnit": "PER_SECOND",
        },
    }


def _tts_execution_context(*, checkpoint: dict | None = None, checkpoint_version: int = 0) -> dict:
    return {
        "taskId": 702,
        "status": "PROCESSING",
        "providerCheckpoint": checkpoint,
        "providerCheckpointVersion": checkpoint_version,
        "params": {
            "workflowStep": True,
            "nodeDefType": "TTS_MODEL",
            "workflowInputs": {
                "script-planner": {
                    "scenes": [
                        {"index": 1, "dialogue": "First scene"},
                        {"index": 2, "dialogue": "Second scene"},
                    ]
                }
            },
        },
        "modelConfig": {
            "provider": "dashscope_qwen_tts",
            "modelName": "qwen3-tts-flash",
            "baseUrl": "https://dashscope.aliyuncs.com",
            "apiKey": "test-key",
            "billingUnit": "PER_CHARACTER",
            "executionOptionsJson": {
                "voice": "Cherry",
                "languageType": "Chinese",
            },
        },
    }


def _tts_checkpoint(*, scene_status: str = "COMPLETED") -> dict:
    scene = {
        "status": scene_status,
        "speechText": "First scene",
        "voice": "Cherry",
        "languageType": "Chinese",
    }
    if scene_status == "COMPLETED":
        scene.update(
            {
                "audioUrl": "/generated/audio/702/voice-1.wav",
                "audioDataUrl": "",
                "providerRequestId": "qwen-request-1",
                "billableUnits": 11,
                "providerCalled": True,
            }
        )
    return {
        "kind": "WORKFLOW_TTS",
        "provider": "dashscope_qwen_tts",
        "protocol": "dashscope_qwen_tts",
        "model": "qwen3-tts-flash",
        "scenes": {"1": scene},
    }


def test_multi_scene_tts_reuses_completed_scene_and_only_calls_missing_scene():
    backend = RecordingBackendClient(
        _tts_execution_context(checkpoint=_tts_checkpoint(), checkpoint_version=4)
    )

    class SecondSceneTtsClient:
        def __init__(self):
            self.texts: list[str] = []

        def generate(self, **kwargs):
            self.texts.append(kwargs["text"])
            assert kwargs["text"] == "Second scene"
            assert backend.checkpoint_updates[-1]["checkpoint"]["scenes"]["2"]["status"] == "STARTED"
            return SpeechGenerationResult(
                audio_url="https://example.com/second.wav",
                extension="wav",
                metadata={
                    "providerRequestId": "qwen-request-2",
                    "billableUnits": 12,
                    "voice": "Cherry",
                    "languageType": "Chinese",
                },
            )

    tts_client = SecondSceneTtsClient()
    result = WorkflowStepHandler(
        backend_client=backend,
        tts_client=tts_client,
        audio_persister=FakeAudioPersister(),
    ).handle({"taskId": 702, "traceId": "trace-702", "claimToken": "claim-702"})

    assert result["status"] == "SUCCESS"
    assert tts_client.texts == ["Second scene"]
    assert [update["expectedVersion"] for update in backend.checkpoint_updates] == [4, 5]
    assert [update["checkpoint"]["scenes"]["2"]["status"] for update in backend.checkpoint_updates] == [
        "STARTED",
        "COMPLETED",
    ]
    assert all(update["claimToken"] == "claim-702" for update in backend.checkpoint_updates)
    output = json.loads(backend.success_payload["contentText"])
    assert [audio["audioUrl"] for audio in output["audios"]] == [
        "/generated/audio/702/voice-1.wav",
        "/generated/audio/702/voice-2.wav",
    ]
    assert [call["providerRequestId"] for call in output["providerCalls"]] == [
        "qwen-request-1",
        "qwen-request-2",
    ]
    assert backend.success_payload["billableUnits"] == 23
    assert backend.success_payload["providerCalled"] is True


def test_workflow_tts_started_checkpoint_is_ambiguous_and_never_replays_provider():
    backend = RecordingBackendClient(
        _tts_execution_context(checkpoint=_tts_checkpoint(scene_status="STARTED"), checkpoint_version=7)
    )

    class RejectingTtsClient:
        def generate(self, **_kwargs):
            raise AssertionError("STARTED TTS scene must not be replayed")

    result = WorkflowStepHandler(
        backend_client=backend,
        tts_client=RejectingTtsClient(),
        audio_persister=FakeAudioPersister(),
    ).handle({"taskId": 702, "traceId": "trace-702", "claimToken": "claim-702"})

    assert result["status"] == "FAILED"
    assert "ambiguous STARTED checkpoint" in result["error"]
    assert backend.checkpoint_updates == []


def test_checkpointed_tts_success_callback_failure_is_retried_by_queue():
    class FailingSuccessBackend(RecordingBackendClient):
        def mark_success(self, task_id: int, payload: dict, trace_id: str | None = None):
            raise BackendClientError("backend request failed: status=500")

    backend = FailingSuccessBackend(_tts_execution_context())

    class SingleSceneTtsClient:
        def generate(self, **kwargs):
            return SpeechGenerationResult(
                audio_url="https://example.com/audio.wav",
                extension="wav",
                metadata={
                    "providerRequestId": "qwen-request-1",
                    "billableUnits": len(kwargs["text"]),
                    "voice": "Cherry",
                    "languageType": "Chinese",
                },
            )

    backend.context["params"]["workflowInputs"]["script-planner"]["scenes"] = [
        {"index": 1, "dialogue": "First scene"}
    ]
    handler = WorkflowStepHandler(
        backend_client=backend,
        tts_client=SingleSceneTtsClient(),
        audio_persister=FakeAudioPersister(),
    )

    with pytest.raises(BackendClientError, match="status=500"):
        handler.handle({"taskId": 702, "traceId": "trace-702", "claimToken": "claim-702"})

    assert backend.failed_payload is None
    assert backend.checkpoint_updates[-1]["checkpoint"]["scenes"]["1"]["status"] == "COMPLETED"


def test_workflow_handler_persists_provider_id_before_poll_result_is_consumed(monkeypatch):
    backend = RecordingBackendClient(_execution_context())

    def fake_generator(*, prompt, image, reference_images, resume, submitted_callback):
        assert resume is None
        submitted_callback(
            {
                "taskId": "agnes-task-701-1",
                "requestId": "agnes-request-701-1",
                "videoId": "agnes-video-701-1",
            }
        )
        assert backend.checkpoint_updates[-1]["checkpoint"]["scenes"]["1"]["status"] == "SUBMITTED"
        return {
            "requestId": "agnes-request-701-1",
            "videoUrl": "https://example.com/generated-1.mp4",
        }

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert [update["expectedVersion"] for update in backend.checkpoint_updates] == [0, 1]
    assert backend.checkpoint_updates[-1]["checkpoint"]["scenes"]["1"]["status"] == "COMPLETED"
    assert backend.success_payload["billableUnits"] == 5
    assert all(update["claimToken"] == "claim-701" for update in backend.checkpoint_updates)


def test_single_scene_video_success_reports_actual_provider_accounting(monkeypatch):
    backend = RecordingBackendClient(_execution_context())

    def fake_generator(*, prompt, image, reference_images, resume, submitted_callback):
        submitted_callback(
            {
                "taskId": "agnes-task-701-1",
                "requestId": "agnes-request-701-1",
                "videoId": "agnes-video-701-1",
            }
        )
        return {
            "requestId": "agnes-request-701-1",
            "providerCostAmount": "0.125000",
            "providerCostCurrency": "usd",
            "videoUrl": "https://example.com/generated-1.mp4",
        }

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert backend.success_payload["providerRequestId"] == "agnes-request-701-1"
    assert backend.success_payload["providerCostAmount"] == "0.125000"
    assert backend.success_payload["providerCostCurrency"] == "USD"


def test_single_scene_video_uses_checkpointed_request_id_when_poll_result_omits_it(monkeypatch):
    backend = RecordingBackendClient(_execution_context())

    def fake_generator(*, prompt, image, reference_images, resume, submitted_callback):
        submitted_callback(
            {
                "taskId": "agnes-task-701-1",
                "requestId": "agnes-request-701-1",
                "videoId": "agnes-video-701-1",
            }
        )
        return {
            "providerCostAmount": "0.125000",
            "providerCostCurrency": "usd",
            "videoUrl": "https://example.com/generated-1.mp4",
        }

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert backend.success_payload["providerRequestId"] == "agnes-request-701-1"
    assert backend.success_payload["providerCostAmount"] == "0.125000"
    assert backend.checkpoint_updates[-1]["checkpoint"]["scenes"]["1"]["requestId"] == "agnes-request-701-1"


def test_multi_scene_video_success_does_not_invent_aggregate_provider_accounting(monkeypatch):
    context = _execution_context()
    context["params"]["workflowInputs"]["script-planner"]["scenes"] = [
        {"index": 1, "sceneDescription": "A presenter enters the studio."},
        {"index": 2, "sceneDescription": "The presenter starts the demonstration."},
    ]
    context["params"]["workflowInputs"]["keyframe"]["images"] = [
        {"sceneIndex": 1, "imageUrl": "https://example.com/frame-1.png"},
        {"sceneIndex": 2, "imageUrl": "https://example.com/frame-2.png"},
    ]
    backend = RecordingBackendClient(context)
    calls = {"count": 0}

    def fake_generator(*, prompt, image, reference_images, resume, submitted_callback):
        calls["count"] += 1
        index = calls["count"]
        submitted_callback(
            {
                "taskId": f"agnes-task-701-{index}",
                "requestId": f"agnes-request-701-{index}",
                "videoId": f"agnes-video-701-{index}",
            }
        )
        return {
            "requestId": f"agnes-request-701-{index}",
            "providerCostAmount": f"0.{index}25000",
            "providerCostCurrency": "usd",
            "videoUrl": f"https://example.com/generated-{index}.mp4",
        }

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert "providerRequestId" not in backend.success_payload
    assert "providerCostAmount" not in backend.success_payload
    assert "providerCostCurrency" not in backend.success_payload
    assert backend.success_payload["billableUnits"] == 10
    output = json.loads(backend.success_payload["contentText"])
    assert output["providerAccounting"] == {
        "status": "UNKNOWN",
        "reason": "MULTIPLE_PROVIDER_CALLS_REQUIRE_ITEMIZED_ACCOUNTING",
        "providerCallCount": 2,
    }
    assert [clip["providerRequestId"] for clip in output["clips"]] == [
        "agnes-request-701-1",
        "agnes-request-701-2",
    ]


def test_workflow_handler_resumes_submitted_scene_without_new_provider_create(monkeypatch):
    checkpoint = {
        "kind": "WORKFLOW_VIDEO",
        "provider": "agnes_video",
        "protocol": "agnes_video",
        "model": "agnes-video-v2.0",
        "scenes": {
            "1": {
                "status": "SUBMITTED",
                "taskId": "agnes-task-701-1",
                "requestId": "agnes-request-701-1",
                "videoId": "agnes-video-701-1",
            }
        },
    }
    backend = RecordingBackendClient(_execution_context(checkpoint=checkpoint, checkpoint_version=4))
    calls: list[dict] = []

    def fake_generator(*, prompt, image, reference_images, resume, submitted_callback):
        calls.append({"resume": resume})
        assert resume == checkpoint["scenes"]["1"]
        return {
            "requestId": "agnes-request-701-1",
            "videoUrl": "https://example.com/generated-1.mp4",
        }

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert calls == [{"resume": checkpoint["scenes"]["1"]}]
    assert [update["expectedVersion"] for update in backend.checkpoint_updates] == [4]


def test_workflow_handler_reuses_completed_scene_after_message_redelivery(monkeypatch):
    checkpoint = {
        "kind": "WORKFLOW_VIDEO",
        "provider": "agnes_video",
        "protocol": "agnes_video",
        "model": "agnes-video-v2.0",
        "scenes": {
            "1": {
                "status": "COMPLETED",
                "taskId": "agnes-task-701-1",
                "requestId": "agnes-request-701-1",
                "videoId": "agnes-video-701-1",
                "videoUrl": "/generated/video/701/video-1.mp4",
                "sourceVideoUrl": "https://example.com/generated-1.mp4",
                "referenceImages": [],
                "providerCostAmount": "0.125000",
                "providerCostCurrency": "USD",
                "billableUnits": 5,
                "providerCalled": True,
            }
        },
    }
    backend = RecordingBackendClient(_execution_context(checkpoint=checkpoint, checkpoint_version=6))

    def fail_if_called(**_kwargs):
        raise AssertionError("completed provider scene must not be created or polled again")

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fail_if_called)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 701, "traceId": "trace-701", "claimToken": "claim-701"}
    )

    assert result["status"] == "SUCCESS"
    assert backend.checkpoint_updates == []
    assert json.loads(backend.success_payload["contentText"])["videoUrl"] == "/generated/video/701/video-1.mp4"
    assert backend.success_payload["billableUnits"] == 5
    assert backend.success_payload["providerCalled"] is True
    assert backend.success_payload["providerRequestId"] == "agnes-request-701-1"
    assert backend.success_payload["providerCostAmount"] == "0.125000"
    assert backend.success_payload["providerCostCurrency"] == "USD"
