import base64
import hashlib
import json
import logging
import math
import re
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from handlers.error_classifier import classify_model_error
from client.model_client import ModelClient, ModelClientError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from client.siliconflow_video_client import SiliconFlowVideoClient, SiliconFlowVideoError
from client.openai_images_client import OpenAIImagesClient
from client.agnes_video_client import AgnesVideoClient
from client.text_to_speech_client import SpeechGenerationResult, TextToSpeechClient, TextToSpeechError
from config import resolve_siliconflow_api_key
from providers import registry as provider_registry
from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from handlers.generated_image_persister import GeneratedImagePersister
from utils.model_contract import parse_response_mapping
from handlers.generated_audio_persister import GeneratedAudioPersister
from handlers.generated_video_persister import GeneratedVideoPersister
from storage.asset_storage import asset_storage
from utils.tts_config import merge_tts_params, speech_billable_units


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}

# 每个分镜固定 5 秒：30s -> 6 镜，60s -> 12 镜，90s -> 18 镜
SCENE_SECONDS = 5
MAX_SCENES = 18
DEFAULT_EPISODE_SECONDS = 30
TTS_PARAMETER_KEYS = frozenset(
    {
        "voice",
        "voiceId",
        "voice_id",
        "language",
        "languageType",
        "language_type",
        "languageBoost",
        "language_boost",
        "format",
        "audioFormat",
        "responseFormat",
        "speed",
        "volume",
        "vol",
        "pitch",
        "sampleRate",
        "sample_rate",
        "bitrate",
        "channel",
        "ttsMode",
        "minimaxMode",
        "mode",
        "pronunciationTone",
        "pronunciation_tone",
        "minimaxGroupId",
        "groupId",
        "GroupId",
        "asyncTimeoutSeconds",
        "pollTimeoutSeconds",
        "asyncPollIntervalSeconds",
        "pollIntervalSeconds",
    }
)

COMIC_CHECKPOINT_KIND = "COMIC_OPERATION_V1"
COMIC_HANDLER_KEYS = {
    "comic.script",
    "comic.storyboard",
    "comic.character_reference",
    "comic.scene_reference",
    "comic.shot_keyframe",
    "comic.shot_video",
    "comic.shot_tts",
    "comic.compose",
}
COMIC_HANDLER_ALIASES = {
    "comic_script": "comic.script",
    "comic_storyboard": "comic.storyboard",
    "comic_character_reference": "comic.character_reference",
    "comic_scene_reference": "comic.scene_reference",
    "comic_shot_keyframe": "comic.shot_keyframe",
    "comic_shot_video": "comic.shot_video",
    "comic_shot_tts": "comic.shot_tts",
    "comic_compose": "comic.compose",
}


class ComicOperationAmbiguousError(RuntimeError):
    """The provider may already have accepted a synchronous comic operation."""


class WorkflowTtsOperationAmbiguousError(RuntimeError):
    """A synchronous workflow TTS call may already have reached the provider."""


class WorkflowStepHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        model_client: ModelClient | None = None,
        image_client: SiliconFlowVideoClient | None = None,
        seedance_client: SeedanceVideoClient | None = None,
        postprocessor: DigitalHumanPostprocessor | None = None,
        tts_client: TextToSpeechClient | None = None,
        audio_persister: GeneratedAudioPersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.model_client = model_client or ModelClient()
        self.image_client = image_client or SiliconFlowVideoClient()
        self.seedance_client = seedance_client or SeedanceVideoClient()
        self.postprocessor = postprocessor or DigitalHumanPostprocessor()
        self.tts_client = tts_client or TextToSpeechClient()
        self.audio_persister = audio_persister or GeneratedAudioPersister()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        claim_token = message.get("__claimToken") or message.get("claimToken")
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
            handler_key = _resolve_comic_handler_key(params, workflow_inputs)
            checkpointed = bool(handler_key) or node_def_type in {"TTS_MODEL", "VIDEO_MODEL"}

            self.backend_client.mark_processing(task_id, progress=12, progress_message="工作流节点执行中", trace_id=trace_id)

            if handler_key:
                output = self._run_comic_operation(
                    handler_key=handler_key,
                    params=params,
                    workflow_inputs=workflow_inputs,
                    form=form,
                    model_config=model_config,
                    task_id=task_id,
                    trace_id=trace_id,
                    provider_checkpoint=context.get("providerCheckpoint"),
                    provider_checkpoint_version=context.get("providerCheckpointVersion"),
                    claim_token=claim_token,
                )
            elif node_def_type in {"LLM_TEXT", "MODEL_CALL"}:
                output = self._run_script_planner(form, workflow_inputs, model_config)
            elif node_def_type == "IMAGE_MODEL":
                output = self._run_keyframe(form, workflow_inputs, model_config, task_id, trace_id)
            elif node_def_type == "TTS_MODEL":
                output = self._run_tts(
                    form,
                    workflow_inputs,
                    model_config,
                    task_id,
                    trace_id,
                    provider_checkpoint=context.get("providerCheckpoint"),
                    provider_checkpoint_version=context.get("providerCheckpointVersion"),
                    claim_token=claim_token,
                )
            elif node_def_type == "VIDEO_MODEL":
                output = self._run_video(
                    form,
                    workflow_inputs,
                    model_config,
                    task_id,
                    trace_id,
                    provider_checkpoint=context.get("providerCheckpoint"),
                    provider_checkpoint_version=context.get("providerCheckpointVersion"),
                    claim_token=claim_token,
                )
            elif node_def_type in {"SUBTITLE", "TOOL_CALL"}:
                output = self._run_compose(form, workflow_inputs, task_id, trace_id)
            else:
                raise RuntimeError(f"unsupported workflow node type: {node_def_type}")
        except Exception as error:
            LOGGER.exception("workflow step failed taskId=%s", task_id)
            self._mark_failed_safe(task_id, error, trace_id=trace_id)
            return {"status": "FAILED", "taskId": task_id, "error": str(error)}

        success_payload = {
            "resourceType": "TEXT",
            "contentText": json.dumps(output, ensure_ascii=False),
        }
        success_payload.update(_billing_usage_payload(output))
        try:
            _validate_success_usage(model_config, success_payload)
        except Exception as error:
            LOGGER.exception("workflow step produced invalid billing usage taskId=%s", task_id)
            try:
                self._mark_failed_safe(task_id, error, trace_id=trace_id)
            except BackendClientError:
                if checkpointed:
                    raise
                LOGGER.error(
                    "non-checkpointed workflow failure callback was not accepted; avoiding provider replay taskId=%s",
                    task_id,
                )
            return {"status": "FAILED", "taskId": task_id, "error": str(error)}
        try:
            self.backend_client.mark_success(task_id, success_payload, trace_id=trace_id)
        except BackendClientError as error:
            if checkpointed:
                LOGGER.exception("checkpointed workflow success settlement will be retried taskId=%s", task_id)
                raise
            LOGGER.exception("non-checkpointed workflow success callback failed taskId=%s", task_id)
            try:
                self._mark_failed_safe(task_id, error, trace_id=trace_id)
            except BackendClientError:
                LOGGER.error(
                    "non-checkpointed workflow failure callback was not accepted; avoiding provider replay taskId=%s",
                    task_id,
                )
            return {"status": "FAILED", "taskId": task_id, "error": str(error)}
        response = {"status": "SUCCESS", "taskId": task_id, "nodeDefType": node_def_type}
        if handler_key:
            response["handlerKey"] = handler_key
        return response

    def _run_comic_operation(
        self,
        *,
        handler_key: str,
        params: dict[str, Any],
        workflow_inputs: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        operation_input = _comic_operation_input(params, workflow_inputs)
        common = {
            "operation_input": operation_input,
            "form": form,
            "model_config": model_config,
            "task_id": task_id,
            "trace_id": trace_id,
            "provider_checkpoint": provider_checkpoint,
            "provider_checkpoint_version": provider_checkpoint_version,
            "claim_token": claim_token,
        }
        if handler_key == "comic.script":
            return self._run_comic_script(**common)
        if handler_key == "comic.storyboard":
            return self._run_comic_storyboard(**common)
        if handler_key == "comic.character_reference":
            return self._run_comic_reference(handler_key=handler_key, asset_type="CHARACTER", **common)
        if handler_key == "comic.scene_reference":
            return self._run_comic_reference(handler_key=handler_key, asset_type="SCENE", **common)
        if handler_key == "comic.shot_keyframe":
            return self._run_comic_shot_keyframe(**common)
        if handler_key == "comic.shot_video":
            return self._run_comic_shot_video(**common)
        if handler_key == "comic.shot_tts":
            return self._run_comic_shot_tts(**common)
        if handler_key == "comic.compose":
            return self._run_comic_compose(**common)
        raise RuntimeError(f"unsupported comic handlerKey: {handler_key}")

    def _begin_comic_operation(
        self,
        *,
        handler_key: str,
        item_key: str,
        operation_input: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
        replay_started: bool = False,
    ) -> tuple[dict[str, Any], int, dict[str, Any] | None, bool]:
        fingerprint = _comic_input_fingerprint(handler_key, operation_input, model_config)
        checkpoint_version = max(0, int(provider_checkpoint_version or 0))
        existing = provider_checkpoint if isinstance(provider_checkpoint, dict) else None
        if existing:
            matches = (
                existing.get("kind") == COMIC_CHECKPOINT_KIND
                and existing.get("handlerKey") == handler_key
                and existing.get("itemKey") == item_key
                and existing.get("inputFingerprint") == fingerprint
            )
            if not matches:
                raise ComicOperationAmbiguousError(
                    f"comic operation checkpoint does not match current input: {handler_key}/{item_key}"
                )
            status = str(existing.get("status") or "").upper()
            result = existing.get("result")
            if status == "COMPLETED" and isinstance(result, dict):
                return json.loads(json.dumps(existing)), checkpoint_version, result, False
            if status == "SUBMITTED" and isinstance(existing.get("providerState"), dict):
                return json.loads(json.dumps(existing)), checkpoint_version, None, False
            if status == "STARTED" and replay_started:
                return json.loads(json.dumps(existing)), checkpoint_version, None, False
            raise ComicOperationAmbiguousError(
                f"comic operation may already have reached provider: {handler_key}/{item_key}"
            )

        checkpoint = {
            "kind": COMIC_CHECKPOINT_KIND,
            "schemaVersion": 1,
            "handlerKey": handler_key,
            "itemKey": item_key,
            "inputFingerprint": fingerprint,
            "provider": str(model_config.get("provider") or ""),
            "protocol": _provider_protocol(model_config),
            "model": str(model_config.get("modelName") or ""),
            "status": "STARTED",
        }
        checkpoint_version = self._save_comic_checkpoint(
            task_id=task_id,
            checkpoint=checkpoint,
            expected_version=checkpoint_version,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return checkpoint, checkpoint_version, None, True

    def _complete_comic_operation(
        self,
        *,
        task_id: int,
        checkpoint: dict[str, Any],
        checkpoint_version: int,
        result: dict[str, Any],
        trace_id: str | None,
        claim_token: str | None,
    ) -> int:
        checkpoint["status"] = "COMPLETED"
        checkpoint["result"] = result
        accounting = _provider_accounting_payload(result)
        if accounting:
            checkpoint["providerAccounting"] = accounting
        return self._save_comic_checkpoint(
            task_id=task_id,
            checkpoint=checkpoint,
            expected_version=checkpoint_version,
            trace_id=trace_id,
            claim_token=claim_token,
        )

    def _save_comic_checkpoint(
        self,
        *,
        task_id: int,
        checkpoint: dict[str, Any],
        expected_version: int,
        trace_id: str | None,
        claim_token: str | None,
    ) -> int:
        saved = self.backend_client.save_provider_checkpoint(
            task_id,
            checkpoint,
            expected_version=expected_version,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return int(saved.get("version") or expected_version + 1)

    def _run_comic_script(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        script_payload = _comic_script_payload(operation_input)
        imported_text = _comic_script_text(script_payload)
        requested_version_id = str(
            script_payload.get("scriptVersionId")
            or script_payload.get("versionId")
            or operation_input.get("scriptVersionId")
            or ""
        ).strip()
        if imported_text:
            script_version_id = requested_version_id or f"script:{_short_hash(imported_text)}"
            normalized = {
                **script_payload,
                "scriptVersionId": script_version_id,
                "title": str(script_payload.get("title") or operation_input.get("title") or "导入剧本"),
                "screenplay": imported_text,
                "sourceMode": str(script_payload.get("sourceMode") or operation_input.get("sourceMode") or "IMPORT"),
            }
            return {
                "handlerKey": "comic.script",
                "itemKey": script_version_id,
                "scriptVersionId": script_version_id,
                "script": normalized,
                **_empty_usage(provider_called=False),
            }

        item_key = requested_version_id or str(operation_input.get("projectId") or "script")
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key="comic.script",
            item_key=item_key,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached
        script_form = {**form}
        for key in (
            "storyTheme",
            "plotOutline",
            "visualStyle",
            "genre",
            "episodeLength",
            "episodeDuration",
        ):
            if operation_input.get(key) is not None:
                script_form[key] = operation_input[key]
        prompt = _build_comic_script_prompt(script_form)
        parsed: dict[str, Any] | None = None
        errors: list[str] = []
        previous = ""
        usage_total = _empty_usage()
        for attempt in range(2):
            attempt_prompt = prompt
            if attempt:
                attempt_prompt += (
                    "\n上一次输出不符合完整剧本 JSON 契约，请完整重写。"
                    f"\n错误：{'；'.join(errors)}\n上一次输出：{previous[:12000]}"
                )
            previous, call_usage = _text_generation_with_usage(
                self.model_client,
                attempt_prompt,
                system_prompt="你是专业漫剧编剧。只输出合法 JSON，不要 Markdown，不要提前拆分镜头。",
                provider=model_config.get("provider"),
                model_name=model_config.get("modelName"),
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=max(180, int(model_config.get("timeoutSeconds") or 0)),
                max_tokens=12000,
                response_mapping=parse_response_mapping(model_config),
            )
            _accumulate_usage(usage_total, _provider_call_usage(call_usage, model_config))
            parsed = _extract_json(previous)
            errors = _comic_script_errors(parsed)
            if not errors:
                break
        if parsed is None or errors:
            raise ModelClientError("剧本模型连续两次未返回有效结构: " + "；".join(errors or ["无法解析 JSON"]))
        generated = {
            **parsed,
            "characters": _normalize_assets(parsed.get("characters"), "character"),
            "props": _normalize_assets(parsed.get("props"), "prop"),
            "locations": _normalize_assets(parsed.get("locations"), "location"),
        }
        script_version_id = requested_version_id or f"script:{_short_hash(generated.get('screenplay') or '')}"
        script = {
            key: generated.get(key)
            for key in ("title", "synopsis", "screenplay", "genre", "characters", "props", "locations")
        }
        script.update({"scriptVersionId": script_version_id, "sourceMode": "GENERATED"})
        result = {
            "handlerKey": "comic.script",
            "itemKey": script_version_id,
            "scriptVersionId": script_version_id,
            "script": script,
            **usage_total,
            "providerCalls": [
                _comic_provider_call(
                    "comic.script",
                    script_version_id,
                    model_config,
                    {**generated, **usage_total},
                )
            ],
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _run_comic_storyboard(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        direct_storyboard = _comic_storyboard_payload(operation_input)
        if direct_storyboard:
            return {
                **_normalize_comic_storyboard(direct_storyboard, form=form),
                **_empty_usage(provider_called=False),
            }

        script_payload = _comic_script_payload(operation_input)
        script_text = _comic_script_text(script_payload)
        if not script_text:
            raise ModelClientError("comic.storyboard requires script text or a structured storyboard")
        item_key = str(
            script_payload.get("scriptVersionId")
            or script_payload.get("versionId")
            or operation_input.get("scriptVersionId")
            or "script"
        )
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key="comic.storyboard",
            item_key=item_key,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached

        requested_shots = _positive_int(
            operation_input.get("shotCount") or operation_input.get("targetShotCount"),
            fallback=_resolve_scene_count(form),
            maximum=60,
        )
        prompt = _build_comic_storyboard_prompt(
            script_text=script_text,
            shot_count=requested_shots,
            visual_style=str(operation_input.get("visualStyle") or form.get("visualStyle") or "电影感漫剧"),
        )
        parsed: dict[str, Any] | None = None
        errors: list[str] = []
        previous = ""
        usage_total = _empty_usage()
        for attempt in range(2):
            attempt_prompt = prompt
            if attempt:
                attempt_prompt += (
                    "\n上一次输出不符合分镜 JSON 契约，请完整重写。"
                    f"\n错误：{'；'.join(errors)}\n上一次输出：{previous[:12000]}"
                )
            previous, call_usage = _text_generation_with_usage(
                self.model_client,
                attempt_prompt,
                system_prompt="你是专业漫剧分镜导演。只输出合法 JSON，不要 Markdown。",
                provider=model_config.get("provider"),
                model_name=model_config.get("modelName"),
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=max(180, int(model_config.get("timeoutSeconds") or 0)),
                max_tokens=min(16000, max(5000, 700 * requested_shots)),
                response_mapping=parse_response_mapping(model_config),
            )
            _accumulate_usage(usage_total, _provider_call_usage(call_usage, model_config))
            parsed = _extract_json(previous)
            errors = _comic_storyboard_errors(parsed)
            if not errors:
                break
        if parsed is None or errors:
            raise ModelClientError("分镜模型连续两次未返回有效结构: " + "；".join(errors or ["无法解析 JSON"]))

        result = _normalize_comic_storyboard(parsed, form=form)
        result["handlerKey"] = "comic.storyboard"
        result["scriptVersionId"] = item_key
        result.update(usage_total)
        result["providerCalls"] = [
            _comic_provider_call("comic.storyboard", item_key, model_config, result)
        ]
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _run_comic_reference(
        self,
        *,
        handler_key: str,
        asset_type: str,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        asset = _comic_asset_payload(operation_input, asset_type)
        asset_id = str(asset.get("assetId") or asset.get("id") or "").strip()
        if not asset_id:
            raise RuntimeError(f"{handler_key} requires assetId")
        asset_version_id = str(
            asset.get("assetVersionId")
            or asset.get("versionId")
            or operation_input.get("assetVersionId")
            or f"{asset_id}:v1"
        ).strip()
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key=handler_key,
            item_key=asset_version_id,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached

        spec = {
            "assetType": "location" if asset_type == "SCENE" else "character",
            "assetId": asset_id,
            "name": str(asset.get("name") or asset.get("title") or asset_id),
            "description": str(
                asset.get("appearance")
                or asset.get("description")
                or asset.get("visualDescription")
                or ""
            ),
        }
        prompt = _build_three_view_reference_prompt(spec, {**form, **operation_input})
        generator = _resolve_image_generator(model_config)
        source_url = generator(prompt, reference_images=[])
        call_usage = _provider_call_usage(generator, model_config)
        if not source_url:
            raise RuntimeError(f"{handler_key} returned no image")
        persisted = GeneratedImagePersister().persist_images(task_id=task_id, urls=[source_url])
        image_url = persisted[0]["url"] if persisted else source_url
        view_role = "three_view_board" if asset_type == "CHARACTER" else "scene_anchor_board"
        asset_version = {
            "assetId": asset_id,
            "assetType": asset_type,
            "assetVersionId": asset_version_id,
            "status": "GENERATED",
            "views": [{"role": view_role, "url": image_url}],
            "referenceImages": [image_url],
            "prompt": prompt,
            "modelSnapshot": _comic_model_snapshot(model_config),
        }
        result = {
            "handlerKey": handler_key,
            "itemKey": asset_version_id,
            "referenceAssetVersion": asset_version,
            "referenceAssetVersions": [asset_version],
            **call_usage,
            "providerCalls": [
                _comic_provider_call(handler_key, asset_version_id, model_config, call_usage)
            ],
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _run_comic_shot_keyframe(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        shot = _comic_shot_payload(operation_input)
        shot_id = str(shot.get("shotId") or "").strip()
        shot_version_id = str(shot.get("shotVersionId") or "").strip()
        if not shot_id or not shot_version_id:
            raise RuntimeError("comic.shot_keyframe requires shotId and shotVersionId")
        reference_images, reference_version_ids = _comic_reference_images_for_shot(operation_input, shot)
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key="comic.shot_keyframe",
            item_key=shot_version_id,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached

        prompt = _build_comic_shot_image_prompt(shot, form)
        generator = _resolve_image_generator(model_config)
        source_url = generator(prompt, reference_images=reference_images)
        call_usage = _provider_call_usage(generator, model_config)
        if not source_url:
            raise RuntimeError("comic.shot_keyframe returned no image")
        persisted = GeneratedImagePersister().persist_images(task_id=task_id, urls=[source_url])
        image_url = persisted[0]["url"] if persisted else source_url
        keyframe_version_id = str(
            operation_input.get("keyframeVersionId")
            or f"{shot_version_id}:keyframe:{_short_hash(prompt)}"
        )
        keyframe_version = {
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "keyframeVersionId": keyframe_version_id,
            "imageUrl": image_url,
            "referenceAssetVersionIds": reference_version_ids,
            "referenceImages": reference_images,
            "prompt": prompt,
            "modelSnapshot": _comic_model_snapshot(model_config),
        }
        result = {
            "handlerKey": "comic.shot_keyframe",
            "itemKey": shot_version_id,
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "keyframeVersion": keyframe_version,
            "imageUrl": image_url,
            "referenceImages": reference_images,
            **call_usage,
            "providerCalls": [
                _comic_provider_call("comic.shot_keyframe", shot_version_id, model_config, call_usage)
            ],
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _run_comic_shot_video(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        shot = _comic_shot_payload(operation_input)
        shot_id = str(shot.get("shotId") or "").strip()
        shot_version_id = str(shot.get("shotVersionId") or "").strip()
        if not shot_id or not shot_version_id:
            raise RuntimeError("comic.shot_video requires shotId and shotVersionId")
        keyframe = _comic_keyframe_payload(operation_input, shot_id)
        image_url = str(keyframe.get("imageUrl") or keyframe.get("url") or "").strip()
        keyframe_version_id = str(keyframe.get("keyframeVersionId") or "").strip()
        if not image_url or not keyframe_version_id:
            raise SeedanceVideoError("comic.shot_video requires a selected keyframe version")

        reference_images, reference_version_ids = _comic_reference_images_for_shot(operation_input, shot)
        reference_images = _dedupe([
            *reference_images,
            *_as_string_list(keyframe.get("referenceImages")),
        ])
        item_key = f"{shot_version_id}@{keyframe_version_id}"
        checkpoint, checkpoint_version, cached, created = self._begin_comic_operation(
            handler_key="comic.shot_video",
            item_key=item_key,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached

        provider_state = checkpoint.get("providerState")
        resume = provider_state if not created and isinstance(provider_state, dict) else None
        generator = _resolve_video_generator(model_config)
        prompt = _build_comic_shot_video_prompt(shot, form)
        duration_seconds = _comic_shot_duration_seconds(shot)
        aspect_ratio = str(operation_input.get("aspectRatio") or form.get("aspectRatio") or "16:9")

        def submitted_callback(submission: dict[str, Any]) -> None:
            nonlocal checkpoint_version
            checkpoint["status"] = "SUBMITTED"
            checkpoint["providerState"] = {
                **(provider_state if isinstance(provider_state, dict) else {}),
                **(submission or {}),
            }
            checkpoint_version = self._save_comic_checkpoint(
                task_id=task_id,
                checkpoint=checkpoint,
                expected_version=checkpoint_version,
                trace_id=trace_id,
                claim_token=claim_token,
            )

        result_from_provider = generator(
            prompt=prompt,
            image=image_url,
            reference_images=reference_images,
            duration=duration_seconds,
            aspect_ratio=aspect_ratio,
            resume=resume,
            submitted_callback=submitted_callback,
        )
        provider_video_url = str(result_from_provider.get("videoUrl") or "").strip()
        if not provider_video_url:
            raise SeedanceVideoError("comic.shot_video returned no video")
        persisted = GeneratedVideoPersister().persist_video_url(
            task_id=task_id,
            source_url=provider_video_url,
            index=1,
        )
        accounting = _provider_accounting_payload({
            **(checkpoint.get("providerState") or {}),
            **result_from_provider,
        })
        call_usage = _provider_call_usage(
            result_from_provider,
            model_config,
            duration_seconds=duration_seconds,
        )
        clip_version_id = str(
            operation_input.get("clipVersionId")
            or f"{shot_version_id}:clip:{_short_hash(keyframe_version_id + prompt)}"
        )
        clip_version = {
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "keyframeVersionId": keyframe_version_id,
            "clipVersionId": clip_version_id,
            "videoUrl": persisted["url"],
            "sourceVideoUrl": provider_video_url,
            "durationSeconds": duration_seconds,
            "referenceAssetVersionIds": reference_version_ids,
            "referenceImages": reference_images,
            "prompt": prompt,
            "modelSnapshot": _comic_model_snapshot(model_config),
            **accounting,
        }
        result = {
            "handlerKey": "comic.shot_video",
            "itemKey": item_key,
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "clipVersion": clip_version,
            "videoUrl": clip_version["videoUrl"],
            "sourceVideoUrl": provider_video_url,
            **call_usage,
            "providerCalls": [
                _comic_provider_call(
                    "comic.shot_video",
                    item_key,
                    model_config,
                    {**clip_version, **call_usage},
                )
            ],
        }
        if accounting:
            result["providerAccounting"] = accounting
        else:
            result["providerAccounting"] = {
                "status": "UNKNOWN",
                "reason": "PROVIDER_DID_NOT_REPORT_ITEMIZED_ACCOUNTING",
                "providerCallCount": 1,
            }
        checkpoint["providerState"] = {
            **(checkpoint.get("providerState") or {}),
            "requestId": (
                result_from_provider.get("requestId")
                or (checkpoint.get("providerState") or {}).get("requestId")
            ),
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _run_comic_shot_tts(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        shot = _comic_shot_payload(operation_input)
        shot_id = str(shot.get("shotId") or "").strip()
        shot_version_id = str(shot.get("shotVersionId") or "").strip()
        if not shot_id or not shot_version_id:
            raise RuntimeError("comic.shot_tts requires shotId and shotVersionId")
        audio = shot.get("audio") if isinstance(shot.get("audio"), dict) else {}
        speech_text = str(
            audio.get("dialogue")
            or shot.get("dialogue")
            or audio.get("narration")
            or shot.get("narration")
            or ""
        ).strip()
        if not speech_text:
            return {
                "handlerKey": "comic.shot_tts",
                "itemKey": shot_version_id,
                "shotId": shot_id,
                "shotVersionId": shot_version_id,
                "skipped": True,
                "reason": "SHOT_HAS_NO_SPEECH",
                "audioVersion": None,
                **_empty_usage(provider_called=False),
            }

        tts_params = merge_tts_params(
            model_config,
            _tts_param_overrides(form),
            _tts_param_overrides(audio),
            _tts_param_overrides(operation_input),
        )
        provider = str(model_config.get("provider") or "").strip().lower()
        provider_registry.require_capability(provider, "TEXT_TO_SPEECH")
        provider_registry.require_worker_ready(provider)
        checkpoint_input = {
            **operation_input,
            "_ttsParametersFingerprint": _short_hash(tts_params),
        }
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key="comic.shot_tts",
            item_key=shot_version_id,
            operation_input=checkpoint_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
        )
        if cached is not None:
            return cached

        speech_result = self.tts_client.generate(
            provider=provider,
            model=str(model_config.get("modelName") or ""),
            text=speech_text,
            base_url=model_config.get("baseUrl"),
            api_key=model_config.get("apiKey"),
            params=tts_params,
        )
        call_metadata = dict(speech_result.metadata or {})
        call_metadata["billableUnits"] = speech_billable_units(
            model_config,
            text=speech_text,
            metadata=call_metadata,
        )
        call_usage = _provider_call_usage(call_metadata, model_config)
        accounting = _provider_accounting_payload(call_metadata)
        persisted = self._persist_speech_result(task_id, speech_result, index=1)
        voice = _tts_param_value(tts_params, "voice", "voiceId", "voice_id") or _tts_param_value(
            call_metadata,
            "voice",
        )
        language_type = _tts_param_value(
            tts_params,
            "languageType",
            "language_type",
            "language",
        ) or _tts_param_value(call_metadata, "languageType", "language_type", "language")
        audio_version_id = str(
            operation_input.get("audioVersionId")
            or f"{shot_version_id}:audio:{_short_hash({'voice': voice, 'languageType': language_type, 'text': speech_text})}"
        )
        audio_version = {
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "audioVersionId": audio_version_id,
            "audioUrl": persisted["url"],
            "speechText": speech_text,
            "voice": voice,
            "languageType": language_type,
            "modelSnapshot": _comic_model_snapshot(model_config),
        }
        result = {
            "handlerKey": "comic.shot_tts",
            "itemKey": shot_version_id,
            "shotId": shot_id,
            "shotVersionId": shot_version_id,
            "audioVersion": audio_version,
            "audioUrl": audio_version["audioUrl"],
            "speechText": speech_text,
            "voice": voice,
            "languageType": language_type,
            **accounting,
            **call_usage,
            "providerCalls": [
                _comic_provider_call(
                    "comic.shot_tts",
                    shot_version_id,
                    model_config,
                    {**call_metadata, **call_usage},
                )
            ],
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

    def _persist_speech_result(
        self,
        task_id: int,
        result: SpeechGenerationResult,
        *,
        index: int,
    ) -> dict[str, str]:
        if result.audio_url:
            return self.audio_persister.persist_audio_url(
                task_id=task_id,
                source_url=result.audio_url,
                extension=result.extension,
                index=index,
            )
        if result.audio_bytes:
            return self.audio_persister.persist_audio_bytes(
                task_id=task_id,
                audio_bytes=result.audio_bytes,
                content_type=result.content_type,
                extension=result.extension,
                index=index,
            )
        raise TextToSpeechError("speech provider returned empty audio")

    def _run_comic_compose(
        self,
        *,
        operation_input: dict[str, Any],
        form: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None,
        provider_checkpoint_version: int | None,
        claim_token: str | None,
    ) -> dict[str, Any]:
        selected = _comic_selected_shot_versions(operation_input)
        manifest_key = str(
            operation_input.get("compositionVersionId")
            or "compose:"
            + _short_hash(
                "|".join(
                    f"{item['shotVersionId']}@{item['clipVersionId']}@{item.get('audioVersionId') or ''}"
                    for item in selected
                )
            )
        )
        checkpoint, checkpoint_version, cached, _ = self._begin_comic_operation(
            handler_key="comic.compose",
            item_key=manifest_key,
            operation_input=operation_input,
            model_config=model_config,
            task_id=task_id,
            trace_id=trace_id,
            provider_checkpoint=provider_checkpoint,
            provider_checkpoint_version=provider_checkpoint_version,
            claim_token=claim_token,
            replay_started=True,
        )
        if cached is not None:
            return cached

        segment_paths = []
        segments: list[dict[str, Any]] = []
        total = len(selected)
        for position, item in enumerate(selected, start=1):
            self.backend_client.mark_processing(
                task_id,
                progress=70 + int(20 * position / max(total, 1)),
                progress_message=f"正在合成已选镜头 {position}/{total}",
                trace_id=trace_id,
            )
            segment = self.postprocessor.process(
                task_id=task_id,
                video_url=item["videoUrl"],
                audio_url=item.get("audioUrl") or "",
                subtitle_text=item.get("subtitleZh") or "",
                segment=f"shot-{position}-{_safe_segment_name(item['shotId'])}",
            )
            segment_paths.append(segment.video_path)
            segments.append(
                {
                    **item,
                    "renderedVideoUrl": segment.video_url,
                    "subtitleUrl": segment.subtitle_url,
                }
            )

        if total > 1:
            self.backend_client.mark_processing(
                task_id,
                progress=94,
                progress_message="正在拼接已选镜头版本",
                trace_id=trace_id,
            )
            _, final_video_url = self.postprocessor.concat_videos(
                task_id=task_id,
                video_paths=segment_paths,
                output_name="comic-final.mp4",
            )
        else:
            final_video_url = segments[0]["renderedVideoUrl"]

        _, subtitle_url = self.postprocessor.concat_subtitles(
            task_id=task_id,
            video_paths=segment_paths,
            subtitle_texts=[item.get("subtitleZh") or "" for item in segments],
            output_name="comic-final.srt",
        )

        title = str(operation_input.get("title") or form.get("storyTheme") or "AI 漫剧成片")
        markdown = _build_delivery_markdown(
            title=title,
            final_video_url=final_video_url,
            subtitle_zh=segments[0].get("subtitleZh") or "",
            subtitle_en=segments[0].get("subtitleEn") or "",
            image_url=segments[0].get("keyframeImageUrl") or None,
            scenes=[
                {
                    "index": item["order"],
                    "subtitleZh": item.get("subtitleZh") or "",
                    "subtitleEn": item.get("subtitleEn") or "",
                }
                for item in segments
            ] if total > 1 else None,
        )
        manifest = {
            "compositionVersionId": manifest_key,
            "selectedShotVersions": [
                {
                    key: item.get(key)
                    for key in (
                        "shotId",
                        "shotVersionId",
                        "order",
                        "clipVersionId",
                        "audioVersionId",
                    )
                }
                for item in selected
            ],
            "finalVideoUrl": final_video_url,
        }
        result = {
            "handlerKey": "comic.compose",
            "itemKey": manifest_key,
            "compositionManifest": manifest,
            "finalVideoUrl": final_video_url,
            "videoUrl": final_video_url,
            "subtitleUrl": subtitle_url,
            "segments": segments,
            "shotCount": total,
            "markdown": markdown,
            **_empty_usage(provider_called=False),
        }
        self._complete_comic_operation(
            task_id=task_id,
            checkpoint=checkpoint,
            checkpoint_version=checkpoint_version,
            result=result,
            trace_id=trace_id,
            claim_token=claim_token,
        )
        return result

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

        node_parameters = workflow_inputs.get("parameters") or {}
        node_prompt = str(node_parameters.get("prompt") or "").strip()
        prompt = _build_script_prompt(
            story_theme=story_theme,
            plot_outline=plot_outline,
            visual_style=visual_style,
            genre=genre,
            scene_count=scene_count,
            feedback_lines=feedback_lines,
            node_prompt=node_prompt,
        )
        parsed: dict[str, Any] | None = None
        validation_errors: list[str] = []
        previous_output = ""
        usage_total = _empty_usage()
        for attempt in range(2):
            attempt_prompt = prompt
            if attempt > 0:
                attempt_prompt += (
                    "\n\n上一次输出未通过交付校验，请完整重写，不要只修补局部。\n"
                    f"校验错误：{'；'.join(validation_errors)}\n"
                    f"上一次输出：{previous_output[:12000]}"
                )
            try:
                raw, call_usage = _text_generation_with_usage(
                    self.model_client,
                    attempt_prompt,
                    system_prompt="你是专业短剧编剧和分镜导演。严格遵守字段、数量、内容唯一性要求，只输出合法 JSON 对象。",
                    provider=model_config.get("provider"),
                    model_name=model_config.get("modelName"),
                    base_url=model_config.get("baseUrl"),
                    api_key=model_config.get("apiKey"),
                    timeout_seconds=max(180, int(model_config.get("timeoutSeconds") or 0)),
                    max_tokens=min(16000, max(5000, 900 * scene_count)),
                    response_mapping=parse_response_mapping(model_config),
                )
                _accumulate_usage(usage_total, _provider_call_usage(call_usage, model_config))
            except ModelClientError:
                LOGGER.exception(
                    "script planner model call failed provider=%s model=%s",
                    model_config.get("provider"),
                    model_config.get("modelName"),
                )
                raise
            previous_output = raw or ""
            parsed = _extract_json(previous_output)
            validation_errors = _script_validation_errors(parsed, scene_count)
            if not validation_errors:
                break
            LOGGER.warning(
                "script planner output rejected attempt=%s errors=%s",
                attempt + 1,
                validation_errors,
            )
        if validation_errors or parsed is None:
            raise ModelClientError(
                "剧本模型连续两次未返回可交付内容: " + "；".join(validation_errors or ["无法解析 JSON"])
            )

        scenes = _normalize_scenes(parsed, scene_count, form)
        title = str(parsed.get("title") or story_theme).strip()
        synopsis = str(parsed.get("synopsis") or "").strip()
        screenplay = str(parsed.get("screenplay") or "").strip()
        characters = _normalize_assets(parsed.get("characters"), "character")
        props = _normalize_assets(parsed.get("props"), "prop")
        locations = _normalize_assets(parsed.get("locations"), "location")

        output: dict[str, Any] = {
            "title": title,
            "synopsis": synopsis,
            "screenplay": screenplay,
            "genre": genre or parsed.get("genre") or "",
            "characters": characters,
            "props": props,
            "locations": locations,
            "sceneCount": len(scenes),
            "sceneSeconds": SCENE_SECONDS,
            "totalSeconds": len(scenes) * SCENE_SECONDS,
            "scenes": scenes,
            **usage_total,
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
        reference_specs = _build_reference_asset_specs(script, form)
        source_urls: list[str] = []
        prompt_entries: list[dict[str, Any]] = []
        usage_total = _empty_usage()
        for spec in reference_specs:
            prompt = _build_three_view_reference_prompt(spec, form)
            source_urls.append(gen_image(prompt))
            _accumulate_usage(usage_total, _provider_call_usage(gen_image, model_config))
            prompt_entries.append({**spec, "prompt": prompt, "kind": "reference"})
        for position, scene in enumerate(scenes, start=1):
            scene_description = scene.get("sceneDescription") or scene.get("narration") or form.get("plotOutline") or ""
            merged = {**form, "plotOutline": scene_description, "mainCharacters": scene.get("dialogue") or form.get("mainCharacters")}
            feedback_parts = [part for part in (script_global, storyboard_global, storyboard_per_scene.get(position)) if part]
            reference_asset_ids = _scene_reference_asset_ids(scene, reference_specs)
            reference_hint = _reference_prompt_hint(reference_asset_ids, reference_specs)
            prompt = (
                f"{DigitalHumanVideoHandler._build_comic_image_prompt(merged)}, "
                f"cinematic close-up, warm indoor lighting, shallow depth of field, emotional expression, "
                f"scene detail: {scene_description}, {reference_hint} no text, no watermark, 16:9 composition."
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
            _accumulate_usage(usage_total, _provider_call_usage(gen_image, model_config))
            prompt_entries.append(
                {
                    "kind": "scene",
                    "sceneIndex": position,
                    "prompt": prompt,
                    "referenceAssetIds": reference_asset_ids,
                }
            )

        persisted = GeneratedImagePersister().persist_images(task_id=task_id, urls=source_urls)
        reference_assets: list[dict[str, Any]] = []
        images: list[dict[str, Any]] = []
        for index, entry in enumerate(prompt_entries):
            source_url = source_urls[index]
            stable_url = persisted[index]["url"] if len(persisted) > index else source_url
            if entry.get("kind") == "reference":
                reference_assets.append(
                    {
                        "assetType": entry.get("assetType") or "",
                        "assetId": entry.get("assetId") or "",
                        "name": entry.get("name") or "",
                        "imageUrl": stable_url,
                        "sourceImageUrl": source_url,
                        "prompt": entry.get("prompt") or "",
                    }
                )
                continue
            images.append(
                {
                    "sceneIndex": entry.get("sceneIndex") or len(images) + 1,
                    "imageUrl": stable_url,
                    "sourceImageUrl": source_url,
                    "prompt": entry.get("prompt") or "",
                    "referenceAssetIds": entry.get("referenceAssetIds") or [],
                }
            )
        return {
            "images": images,
            "referenceAssets": reference_assets,
            "sceneCount": total,
            # 向后兼容字段
            "imageUrl": images[0]["imageUrl"] if images else "",
            "sourceImageUrl": images[0]["sourceImageUrl"] if images else "",
            "prompt": images[0]["prompt"] if images else "",
            **usage_total,
        }

    def _run_tts(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None = None,
        provider_checkpoint_version: int | None = None,
        claim_token: str | None = None,
    ) -> dict[str, Any]:
        script = _find_script_payload(workflow_inputs)
        scenes = _scenes_from_script(script, form)
        provider = str(model_config.get("provider") or "").strip().lower()
        provider_registry.require_capability(provider, "TEXT_TO_SPEECH")
        provider_registry.require_worker_ready(provider)
        protocol = _provider_protocol(model_config) or provider
        model = str(model_config.get("modelName") or "")
        if _matches_workflow_tts_checkpoint(provider_checkpoint, provider, protocol, model):
            checkpoint = json.loads(json.dumps(provider_checkpoint))
        else:
            checkpoint = {
                "kind": "WORKFLOW_TTS",
                "provider": provider,
                "protocol": protocol,
                "model": model,
                "scenes": {},
            }
        checkpoint_version = max(0, int(provider_checkpoint_version or 0))
        scene_checkpoints = checkpoint.setdefault("scenes", {})
        total = len(scenes)
        audios: list[dict[str, Any]] = []
        provider_calls: list[dict[str, Any]] = []
        usage_total = _empty_usage()
        for position, scene in enumerate(scenes, start=1):
            scene_key = str(position)
            speech_text = str(
                scene.get("dialogue")
                or scene.get("narration")
                or form.get("plotOutline")
                or "奶就放心了。"
            )
            tts_params = merge_tts_params(
                model_config,
                _tts_param_overrides(form),
                _tts_param_overrides(scene),
            )
            scene_checkpoint = scene_checkpoints.get(scene_key)
            if not isinstance(scene_checkpoint, dict):
                scene_checkpoint = {}
            scene_fingerprint = _short_hash({"speechText": speech_text, "ttsParams": tts_params})
            checkpoint_fingerprint = str(scene_checkpoint.get("inputFingerprint") or "")
            if checkpoint_fingerprint and checkpoint_fingerprint != scene_fingerprint:
                raise WorkflowTtsOperationAmbiguousError(
                    f"workflow TTS scene {position} checkpoint does not match current input"
                )
            scene_status = str(scene_checkpoint.get("status") or "").upper()
            if scene_status == "COMPLETED" and scene_checkpoint.get("audioUrl"):
                cached_usage = _provider_call_usage(scene_checkpoint, model_config)
                _accumulate_usage(usage_total, cached_usage)
                provider_calls.append(
                    _comic_provider_call("workflow.tts", scene_key, model_config, scene_checkpoint)
                )
                audios.append(
                    {
                        "sceneIndex": position,
                        "audioUrl": scene_checkpoint["audioUrl"],
                        "audioDataUrl": scene_checkpoint.get("audioDataUrl") or "",
                        "speechText": scene_checkpoint.get("speechText") or speech_text,
                        "voice": scene_checkpoint.get("voice") or "",
                        "languageType": scene_checkpoint.get("languageType") or "",
                    }
                )
                continue
            if scene_status == "STARTED":
                raise WorkflowTtsOperationAmbiguousError(
                    f"workflow TTS scene {position} has ambiguous STARTED checkpoint; refusing provider replay"
                )
            self.backend_client.mark_processing(
                task_id,
                progress=48 + int(15 * position / max(total, 1)),
                progress_message=f"正在生成角色配音 {position}/{total}",
                trace_id=trace_id,
            )
            scene_checkpoints[scene_key] = {
                "status": "STARTED",
                "speechText": speech_text,
                "inputFingerprint": scene_fingerprint,
            }
            saved = self.backend_client.save_provider_checkpoint(
                task_id,
                checkpoint,
                expected_version=checkpoint_version,
                trace_id=trace_id,
                claim_token=claim_token,
            )
            checkpoint_version = int(saved.get("version") or checkpoint_version + 1)
            speech_result = self.tts_client.generate(
                provider=provider,
                model=model,
                text=speech_text,
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                params=tts_params,
            )
            call_metadata = dict(speech_result.metadata or {})
            call_metadata["billableUnits"] = speech_billable_units(
                model_config,
                text=speech_text,
                metadata=call_metadata,
            )
            voice = _tts_param_value(tts_params, "voice", "voiceId", "voice_id") or _tts_param_value(
                call_metadata,
                "voice",
            )
            language_type = _tts_param_value(
                tts_params,
                "languageType",
                "language_type",
                "language",
            ) or _tts_param_value(call_metadata, "languageType", "language_type", "language")
            call_usage = _provider_call_usage(call_metadata, model_config)
            _accumulate_usage(usage_total, call_usage)
            persisted = self._persist_speech_result(task_id, speech_result, index=position)
            audio_data_url = (
                speech_result.audio_url
                if str(speech_result.audio_url or "").startswith("data:")
                else ""
            )
            completed = {
                **scene_checkpoints[scene_key],
                "status": "COMPLETED",
                "audioUrl": persisted["url"],
                "audioDataUrl": audio_data_url,
                "speechText": speech_text,
                "voice": voice,
                "languageType": language_type,
                **_provider_accounting_payload(call_metadata),
                **call_usage,
            }
            scene_checkpoints[scene_key] = completed
            saved = self.backend_client.save_provider_checkpoint(
                task_id,
                checkpoint,
                expected_version=checkpoint_version,
                trace_id=trace_id,
                claim_token=claim_token,
            )
            checkpoint_version = int(saved.get("version") or checkpoint_version + 1)
            provider_calls.append(_comic_provider_call("workflow.tts", scene_key, model_config, completed))
            audios.append(
                {
                    "sceneIndex": position,
                    "audioUrl": completed["audioUrl"],
                    "audioDataUrl": completed["audioDataUrl"],
                    "speechText": speech_text,
                    "voice": voice,
                    "languageType": language_type,
                }
            )
        first = audios[0] if audios else {}
        output = {
            "audios": [{key: value for key, value in item.items() if key != "audioDataUrl"} for item in audios],
            "audioDataUrls": [item.get("audioDataUrl") or "" for item in audios],
            "sceneCount": total,
            "providerCalls": provider_calls,
            # 向后兼容字段
            "audioUrl": first.get("audioUrl") or "",
            "audioDataUrl": first.get("audioDataUrl") or "",
            "speechText": first.get("speechText") or "",
            "voice": first.get("voice") or "",
            **usage_total,
        }
        if total == 1:
            output.update(_provider_accounting_payload(provider_calls[0]))
        elif total > 1:
            output["providerAccounting"] = {
                "status": "UNKNOWN",
                "reason": "MULTIPLE_PROVIDER_CALLS_REQUIRE_ITEMIZED_ACCOUNTING",
                "providerCallCount": total,
            }
        return output

    def _run_video(
        self,
        form: dict[str, Any],
        workflow_inputs: dict[str, Any],
        model_config: dict[str, Any],
        task_id: int,
        trace_id: str | None,
        provider_checkpoint: dict[str, Any] | None = None,
        provider_checkpoint_version: int | None = None,
        claim_token: str | None = None,
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
        reference_assets = keyframe.get("referenceAssets") if isinstance(keyframe.get("referenceAssets"), list) else []
        scene_global, scene_per_scene = _parse_per_scene_feedback(form.get("sceneFeedback"))

        gen_video = _resolve_video_generator(model_config)
        persister = GeneratedVideoPersister()
        provider = str(model_config.get("provider") or "seedance")
        protocol = _provider_protocol(model_config) or provider.lower()
        model = str(model_config.get("modelName") or "")
        if _matches_workflow_video_checkpoint(provider_checkpoint, provider, protocol, model):
            checkpoint = json.loads(json.dumps(provider_checkpoint))
        else:
            checkpoint = {
                "kind": "WORKFLOW_VIDEO",
                "provider": provider,
                "protocol": protocol,
                "model": model,
                "scenes": {},
            }
        checkpoint_version = max(0, int(provider_checkpoint_version or 0))
        scene_checkpoints = checkpoint.setdefault("scenes", {})
        total = len(scenes)
        clips: list[dict[str, Any]] = []
        usage_total = _empty_usage()
        for position, scene in enumerate(scenes, start=1):
            scene_key = str(position)
            scene_checkpoint = scene_checkpoints.get(scene_key)
            if not isinstance(scene_checkpoint, dict):
                scene_checkpoint = {}
            if (
                str(scene_checkpoint.get("status") or "").upper() == "COMPLETED"
                and scene_checkpoint.get("videoUrl")
                and scene_checkpoint.get("sourceVideoUrl")
            ):
                cached_usage = _provider_call_usage(
                    scene_checkpoint,
                    model_config,
                    duration_seconds=SCENE_SECONDS,
                )
                _accumulate_usage(usage_total, cached_usage)
                clips.append({
                    "sceneIndex": position,
                    "videoUrl": scene_checkpoint["videoUrl"],
                    "sourceVideoUrl": scene_checkpoint["sourceVideoUrl"],
                    "referenceImages": scene_checkpoint.get("referenceImages") or [],
                    **_provider_accounting_payload(scene_checkpoint),
                    **cached_usage,
                })
                continue
            image_entry = keyframe_images[position - 1] if position <= len(keyframe_images) else keyframe_images[-1]
            image_url = (image_entry or {}).get("imageUrl")
            if not image_url:
                raise SeedanceVideoError(f"keyframe image missing for scene {position}")
            reference_images = _reference_images_for_scene(scene, image_entry, reference_assets)
            prompt = DigitalHumanVideoHandler._build_comic_video_prompt(form)
            if scene.get("sceneDescription"):
                prompt = f"{prompt}\nScene focus: {scene.get('sceneDescription')}"
            if reference_images:
                prompt = (
                    f"{prompt}\nUse the injected reference boards to preserve character, prop, "
                    f"and location consistency for this scene."
                )
            feedback_parts = [part for part in (scene_global, scene_per_scene.get(position)) if part]
            if feedback_parts:
                prompt = f"{prompt}\nUser revision notes: {'；'.join(feedback_parts)}"
            self.backend_client.mark_processing(
                task_id,
                progress=48 + int(15 * position / max(total, 1)),
                progress_message=f"正在生成分镜视频 {position}/{total}",
                trace_id=trace_id,
            )
            resume = scene_checkpoint if str(scene_checkpoint.get("status") or "").upper() == "SUBMITTED" else None

            def submitted_callback(submission: dict[str, Any]) -> None:
                nonlocal checkpoint_version
                submitted = {
                    **scene_checkpoint,
                    **(submission or {}),
                    "status": "SUBMITTED",
                }
                scene_checkpoints[scene_key] = submitted
                saved = self.backend_client.save_provider_checkpoint(
                    task_id,
                    checkpoint,
                    expected_version=checkpoint_version,
                    trace_id=trace_id,
                    claim_token=claim_token,
                )
                checkpoint_version = int(saved.get("version") or checkpoint_version + 1)

            result = gen_video(
                prompt=prompt,
                image=image_url,
                reference_images=reference_images,
                resume=resume,
                submitted_callback=submitted_callback,
            )
            persisted = persister.persist_video_url(task_id=task_id, source_url=result["videoUrl"], index=position)
            provider_accounting = _provider_accounting_payload({
                **scene_checkpoint,
                **scene_checkpoints.get(scene_key, {}),
            })
            provider_accounting.update(_provider_accounting_payload(result))
            call_usage = _provider_call_usage(
                result,
                model_config,
                duration_seconds=SCENE_SECONDS,
            )
            _accumulate_usage(usage_total, call_usage)
            clip = {
                "sceneIndex": position,
                "videoUrl": persisted["url"],
                "sourceVideoUrl": result["videoUrl"],
                "referenceImages": reference_images,
                **provider_accounting,
                **call_usage,
            }
            clips.append(clip)
            scene_checkpoints[scene_key] = {
                **scene_checkpoint,
                **scene_checkpoints.get(scene_key, {}),
                "requestId": (
                    result.get("requestId")
                    or scene_checkpoints.get(scene_key, {}).get("requestId")
                    or scene_checkpoint.get("requestId")
                ),
                "status": "COMPLETED",
                "videoUrl": clip["videoUrl"],
                "sourceVideoUrl": clip["sourceVideoUrl"],
                "referenceImages": reference_images,
                **provider_accounting,
                **call_usage,
            }
            saved = self.backend_client.save_provider_checkpoint(
                task_id,
                checkpoint,
                expected_version=checkpoint_version,
                trace_id=trace_id,
                claim_token=claim_token,
            )
            checkpoint_version = int(saved.get("version") or checkpoint_version + 1)
        first = clips[0] if clips else {}
        output = {
            "clips": clips,
            "sceneCount": total,
            "provider": provider,
            # 向后兼容字段
            "videoUrl": first.get("videoUrl") or "",
            "sourceVideoUrl": first.get("sourceVideoUrl") or "",
            **usage_total,
        }
        if total == 1:
            output["providerAccounting"] = _provider_accounting_payload(first)
        else:
            output["providerAccounting"] = {
                "status": "UNKNOWN",
                "reason": "MULTIPLE_PROVIDER_CALLS_REQUIRE_ITEMIZED_ACCOUNTING",
                "providerCallCount": total,
            }
        return output
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
                raise DigitalHumanPostprocessError("video clips are required for compose")
            clips = [{"sceneIndex": 1, "videoUrl": video.get("videoUrl")}]
        has_tts = bool(tts and (tts.get("audios") or tts.get("audioUrl") or tts.get("audioDataUrl")))
        audios: list[dict[str, Any]] = []
        audio_data_urls: list[str] = []
        if has_tts:
            audios = tts.get("audios") or []
            audio_data_urls = tts.get("audioDataUrls") if isinstance(tts.get("audioDataUrls"), list) else []
            if not audios:
                audios = [{"sceneIndex": 1, "audioUrl": tts.get("audioUrl") or "", "speechText": tts.get("speechText") or ""}]
                audio_data_urls = [tts.get("audioDataUrl") or ""]

        total = len(clips)
        segment_paths = []
        segments: list[dict[str, Any]] = []
        for position, clip in enumerate(clips, start=1):
            scene = scenes[position - 1] if position <= len(scenes) else (scenes[-1] if scenes else {})
            audio_entry = audios[position - 1] if position <= len(audios) else {}
            audio_data_url = audio_data_urls[position - 1] if position <= len(audio_data_urls) else ""
            video_url = (clip or {}).get("videoUrl")
            audio_url = (audio_entry or {}).get("audioUrl") or ""
            if not video_url:
                raise DigitalHumanPostprocessError(f"video clip missing for scene {position}")
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
            **_empty_usage(provider_called=False),
        }

    def _mark_failed_safe(self, task_id: int, error: Exception, trace_id: str | None = None) -> None:
        try:
            failure_payload = {
                "errorCode": classify_model_error(str(error)),
                "errorMessage": str(error),
            }
            failure_payload.update(_provider_failure_accounting_payload(error))
            self.backend_client.mark_failed(
                task_id,
                failure_payload,
                trace_id=trace_id,
            )
        except BackendClientError:
            LOGGER.exception("failed to report workflow step failure taskId=%s", task_id)
            raise


def _resolve_comic_handler_key(
    params: dict[str, Any],
    workflow_inputs: dict[str, Any],
) -> str | None:
    containers = [
        params,
        params.get("parameters") if isinstance(params.get("parameters"), dict) else {},
        workflow_inputs.get("parameters") if isinstance(workflow_inputs.get("parameters"), dict) else {},
    ]
    for container in containers:
        raw = container.get("handlerKey") or container.get("operation")
        if raw is None:
            continue
        normalized = str(raw).strip().lower()
        normalized = COMIC_HANDLER_ALIASES.get(normalized, normalized)
        if normalized in COMIC_HANDLER_KEYS:
            return normalized
        if normalized.startswith("comic.") or normalized.startswith("comic_"):
            raise RuntimeError(f"unsupported comic handlerKey: {raw}")
    return None


def _comic_operation_input(
    params: dict[str, Any],
    workflow_inputs: dict[str, Any],
) -> dict[str, Any]:
    parameter_block = params.get("parameters") if isinstance(params.get("parameters"), dict) else {}
    for container in (params, parameter_block, workflow_inputs):
        for key in ("operationInput", "comicInput", "item"):
            value = container.get(key) if isinstance(container, dict) else None
            if isinstance(value, dict):
                return value
    return workflow_inputs if isinstance(workflow_inputs, dict) else {}


def _comic_input_fingerprint(
    handler_key: str,
    operation_input: dict[str, Any],
    model_config: dict[str, Any],
) -> str:
    payload = {
        "handlerKey": handler_key,
        "operationInput": operation_input,
        "model": _comic_model_snapshot(model_config),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _comic_model_snapshot(model_config: dict[str, Any]) -> dict[str, str]:
    return {
        "provider": str(model_config.get("provider") or ""),
        "protocol": _provider_protocol(model_config),
        "model": str(model_config.get("modelName") or ""),
        "baseUrl": str(model_config.get("baseUrl") or ""),
    }


def _short_hash(value: Any) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def _find_named_value(value: Any, names: tuple[str, ...]) -> Any:
    if isinstance(value, dict):
        for name in names:
            if name in value and value[name] is not None:
                return value[name]
        for nested in value.values():
            found = _find_named_value(nested, names)
            if found is not None:
                return found
    elif isinstance(value, list):
        for nested in value:
            found = _find_named_value(nested, names)
            if found is not None:
                return found
    return None


def _find_named_dict(value: Any, names: tuple[str, ...]) -> dict[str, Any] | None:
    if isinstance(value, dict):
        for name in names:
            nested = value.get(name)
            if isinstance(nested, dict):
                return nested
        for nested in value.values():
            found = _find_named_dict(nested, names)
            if found is not None:
                return found
    elif isinstance(value, list):
        for nested in value:
            found = _find_named_dict(nested, names)
            if found is not None:
                return found
    return None


def _collect_named_list_items(value: Any, names: tuple[str, ...]) -> list[Any]:
    collected: list[Any] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            if key in names and isinstance(nested, list):
                collected.extend(nested)
            else:
                collected.extend(_collect_named_list_items(nested, names))
    elif isinstance(value, list):
        for nested in value:
            collected.extend(_collect_named_list_items(nested, names))
    return collected


def _comic_script_payload(operation_input: dict[str, Any]) -> dict[str, Any]:
    value = _find_named_dict(operation_input, ("script", "scriptVersion"))
    if value is not None:
        return value
    if isinstance(value, str) and value.strip():
        return {"text": value.strip()}
    if any(operation_input.get(key) is not None for key in ("scriptText", "screenplay", "rawText")):
        return operation_input
    return {}


def _comic_script_text(script_payload: dict[str, Any]) -> str:
    for key in ("screenplay", "text", "scriptText", "rawText", "content"):
        value = script_payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _positive_int(value: Any, *, fallback: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = fallback
    return max(1, min(parsed, maximum))


def _comic_provider_call(
    handler_key: str,
    item_key: str,
    model_config: dict[str, Any],
    source: dict[str, Any],
) -> dict[str, Any]:
    call = {
        "handlerKey": handler_key,
        "itemKey": item_key,
        "status": "COMPLETED",
        **_comic_model_snapshot(model_config),
    }
    call.update(_provider_accounting_payload(source))
    for field in ("promptTokens", "completionTokens", "billableUnits"):
        value = _non_negative_int(source.get(field))
        if value > 0:
            call[field] = value
    if "providerRequestId" not in call:
        call["accountingStatus"] = "UNKNOWN"
    return call


def _comic_storyboard_payload(operation_input: dict[str, Any]) -> dict[str, Any] | None:
    value = _find_named_value(operation_input, ("storyboard", "storyboardVersion"))
    if isinstance(value, dict) and isinstance(value.get("shots") or value.get("scenes"), list):
        return value
    if isinstance(operation_input.get("shots") or operation_input.get("scenes"), list):
        return operation_input
    return None


def _comic_storyboard_errors(value: dict[str, Any] | None) -> list[str]:
    if not isinstance(value, dict):
        return ["无法解析 JSON 对象"]
    shots = value.get("shots") or value.get("scenes")
    if not isinstance(shots, list) or not shots:
        return ["shots 必须是非空数组"]
    errors: list[str] = []
    seen_ids: set[str] = set()
    for index, raw in enumerate(shots, start=1):
        if not isinstance(raw, dict):
            errors.append(f"分镜{index}不是对象")
            continue
        shot_id = str(raw.get("shotId") or raw.get("id") or f"shot-{index:03}").strip()
        if shot_id in seen_ids:
            errors.append(f"分镜 ID 重复: {shot_id}")
        seen_ids.add(shot_id)
        description = str(raw.get("visualDescription") or raw.get("sceneDescription") or "").strip()
        if not description:
            errors.append(f"分镜{index}缺少 visualDescription")
    return errors


def _normalize_comic_storyboard(
    payload: dict[str, Any],
    *,
    form: dict[str, Any],
) -> dict[str, Any]:
    nested = payload.get("storyboard") if isinstance(payload.get("storyboard"), dict) else payload
    raw_shots = nested.get("shots") or nested.get("scenes")
    if not isinstance(raw_shots, list) or not raw_shots:
        raise ModelClientError("storyboard shots must be a non-empty array")
    shots: list[dict[str, Any]] = []
    cursor_ms = 0
    seen_ids: set[str] = set()
    for index, raw in enumerate(raw_shots, start=1):
        if not isinstance(raw, dict):
            raise ModelClientError(f"storyboard shot {index} must be an object")
        shot = _normalize_comic_shot(raw, index=index, cursor_ms=cursor_ms)
        if shot["shotId"] in seen_ids:
            raise ModelClientError(f"duplicate storyboard shotId: {shot['shotId']}")
        seen_ids.add(shot["shotId"])
        shots.append(shot)
        cursor_ms = shot["timecode"]["endMs"]
    source_version = str(
        nested.get("storyboardVersionId")
        or payload.get("storyboardVersionId")
        or ""
    ).strip()
    version_id = source_version or f"storyboard:{_short_hash(shots)}"
    return {
        "handlerKey": "comic.storyboard",
        "storyboardVersionId": version_id,
        "title": str(nested.get("title") or payload.get("title") or form.get("storyTheme") or "AI 漫剧"),
        "shotCount": len(shots),
        "durationMs": shots[-1]["timecode"]["endMs"],
        "shots": shots,
    }


def _normalize_comic_shot(raw: dict[str, Any], *, index: int, cursor_ms: int) -> dict[str, Any]:
    shot_id = str(raw.get("shotId") or raw.get("id") or f"shot-{index:03}").strip()
    shot_version_id = str(raw.get("shotVersionId") or raw.get("versionId") or f"{shot_id}:v1").strip()
    timecode = raw.get("timecode") if isinstance(raw.get("timecode"), dict) else {}
    start_ms = _int_or_none(timecode.get("startMs") if timecode else raw.get("startMs"))
    end_ms = _int_or_none(timecode.get("endMs") if timecode else raw.get("endMs"))
    duration_ms = _int_or_none(
        timecode.get("targetDurationMs")
        if timecode
        else raw.get("targetDurationMs") or raw.get("durationMs")
    )
    if duration_ms is None:
        seconds = _float_or_none(raw.get("durationSeconds"))
        if seconds is not None:
            duration_ms = max(1, int(round(seconds * 1000)))
    start_ms = max(0, start_ms if start_ms is not None else cursor_ms)
    if duration_ms is None and end_ms is not None and end_ms > start_ms:
        duration_ms = end_ms - start_ms
    duration_ms = max(1, duration_ms or SCENE_SECONDS * 1000)
    end_ms = max(start_ms + 1, end_ms if end_ms is not None else start_ms + duration_ms)

    camera_raw = raw.get("camera") if isinstance(raw.get("camera"), dict) else {}
    performance_raw = raw.get("performance") if isinstance(raw.get("performance"), dict) else {}
    audio_raw = raw.get("audio") if isinstance(raw.get("audio"), dict) else {}
    prompts_raw = raw.get("prompts") if isinstance(raw.get("prompts"), dict) else {}
    references_raw = raw.get("references") if isinstance(raw.get("references"), dict) else {}
    visual_description = str(
        raw.get("visualDescription")
        or raw.get("sceneDescription")
        or prompts_raw.get("image")
        or ""
    ).strip()
    if not visual_description:
        raise ModelClientError(f"storyboard shot {index} requires visualDescription")
    dialogue = str(audio_raw.get("dialogue") if "dialogue" in audio_raw else raw.get("dialogue") or "")
    narration = str(audio_raw.get("narration") if "narration" in audio_raw else raw.get("narration") or "")
    subtitle_zh = str(audio_raw.get("subtitleZh") or raw.get("subtitleZh") or dialogue or narration)
    subtitle_en = str(audio_raw.get("subtitleEn") or raw.get("subtitleEn") or "")
    references = {
        **references_raw,
        "characters": _normalize_comic_entity_refs(
            references_raw.get("characters")
            or references_raw.get("characterRefs")
            or raw.get("characters")
            or raw.get("character")
        ),
        "scenes": _normalize_comic_entity_refs(
            references_raw.get("scenes")
            or references_raw.get("locations")
            or references_raw.get("sceneRefs")
            or references_raw.get("locationRefs")
            or raw.get("scenes")
            or raw.get("scene")
            or raw.get("locations")
            or raw.get("location")
        ),
        "characterVersionIds": _dedupe(
            _as_string_list(references_raw.get("characterVersionIds"))
            + _as_string_list(raw.get("characterVersionIds"))
        ),
        "sceneVersionIds": _dedupe(
            _as_string_list(references_raw.get("sceneVersionIds") or references_raw.get("sceneVersionId"))
            + _as_string_list(raw.get("sceneVersionIds") or raw.get("sceneVersionId"))
        ),
        "propVersionIds": _dedupe(
            _as_string_list(references_raw.get("propVersionIds"))
            + _as_string_list(raw.get("propVersionIds"))
        ),
        "assetVersionIds": _dedupe(
            _as_string_list(references_raw.get("assetVersionIds"))
            + _as_string_list(raw.get("referenceAssetVersionIds"))
        ),
    }
    return {
        "shotId": shot_id,
        "shotVersionId": shot_version_id,
        "order": _positive_int(raw.get("order") or raw.get("index") or index, fallback=index, maximum=10000),
        "timecode": {
            "startMs": start_ms,
            "endMs": end_ms,
            "targetDurationMs": duration_ms,
        },
        "camera": {
            "shotSize": str(camera_raw.get("shotSize") or raw.get("shotSize") or ""),
            "angle": str(camera_raw.get("angle") or raw.get("cameraAngle") or ""),
            "movement": str(camera_raw.get("movement") or raw.get("cameraMovement") or ""),
            "composition": str(camera_raw.get("composition") or raw.get("composition") or ""),
            "notes": str(camera_raw.get("notes") or raw.get("cameraLanguage") or ""),
        },
        "performance": {
            "emotion": str(performance_raw.get("emotion") or raw.get("emotion") or ""),
            "action": str(performance_raw.get("action") or raw.get("action") or ""),
        },
        "visualDescription": visual_description,
        "audio": {
            **audio_raw,
            "dialogue": dialogue,
            "narration": narration,
            "subtitleZh": subtitle_zh,
            "subtitleEn": subtitle_en,
            "sfx": _as_string_list(audio_raw.get("sfx") or raw.get("sfx")),
            "bgmMood": str(audio_raw.get("bgmMood") or raw.get("bgmMood") or ""),
        },
        "references": references,
        "prompts": {
            **prompts_raw,
            "image": str(prompts_raw.get("image") or raw.get("imagePrompt") or visual_description),
            "video": str(
                prompts_raw.get("video")
                or raw.get("imageToVideoPrompt")
                or raw.get("textToVideoPrompt")
                or visual_description
            ),
            "negative": str(prompts_raw.get("negative") or raw.get("negativePrompt") or ""),
        },
        "continuity": raw.get("continuity") if isinstance(raw.get("continuity"), dict) else {},
        # Compatibility fields for existing result viewers and legacy adapters.
        "index": index,
        "sceneTitle": str(raw.get("sceneTitle") or f"分镜{index}"),
        "sceneDescription": visual_description,
        "cameraLanguage": str(raw.get("cameraLanguage") or camera_raw.get("notes") or ""),
        "dialogue": dialogue,
        "narration": narration,
        "subtitleZh": subtitle_zh,
        "subtitleEn": subtitle_en,
        "presenterGender": str(raw.get("presenterGender") or audio_raw.get("speakerGender") or ""),
        "durationSeconds": max(1, int(round(duration_ms / 1000))),
    }


def _normalize_comic_entity_refs(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, dict):
        values = [value]
    elif isinstance(value, list):
        values = value
    else:
        return []
    entities: list[dict[str, Any]] = []
    for raw in values:
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or raw.get("title") or raw.get("label") or "").strip()
        if not name:
            continue
        entities.append({**raw, "name": name})
    return entities


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value) if value is not None and str(value).strip() else None
    except (TypeError, ValueError):
        return None


def _float_or_none(value: Any) -> float | None:
    try:
        return float(value) if value is not None and str(value).strip() else None
    except (TypeError, ValueError):
        return None


def _comic_script_errors(value: dict[str, Any] | None) -> list[str]:
    if not isinstance(value, dict):
        return ["无法解析 JSON 对象"]
    errors: list[str] = []
    for field in ("title", "synopsis", "screenplay"):
        if not str(value.get(field) or "").strip():
            errors.append(f"缺少 {field}")
    screenplay = re.sub(r"\s+", "", str(value.get("screenplay") or ""))
    if screenplay and len(screenplay) < 240:
        errors.append("screenplay 过短，至少需要 240 字")
    return errors


def _build_comic_script_prompt(form: dict[str, Any]) -> str:
    return (
        "请创作一份完整、可继续拆分镜头的 AI 漫剧剧本。只输出合法 JSON 对象，不要 Markdown。\n"
        "此步骤只负责完整剧本，不要输出分镜表。剧本必须有开场钩子、冲突升级、关键转折、高潮和结尾。\n"
        "输出结构：{\"title\":\"...\",\"synopsis\":\"...\",\"screenplay\":\"不少于240字的完整剧本\","
        "\"genre\":\"...\",\"characters\":[{\"id\":\"...\",\"name\":\"...\",\"appearance\":\"...\","
        "\"personality\":\"...\"}],\"locations\":[{\"id\":\"...\",\"name\":\"...\","
        "\"description\":\"...\"}],\"props\":[]}。\n"
        f"主题：{form.get('storyTheme') or '请自行设计'}\n"
        f"剧情梗概：{form.get('plotOutline') or '请自行设计'}\n"
        f"题材：{form.get('genre') or '未指定'}\n"
        f"视觉风格：{form.get('visualStyle') or '电影感漫剧'}\n"
        f"目标时长：{form.get('episodeLength') or form.get('episodeDuration') or '60秒'}"
    )


def _build_comic_storyboard_prompt(*, script_text: str, shot_count: int, visual_style: str) -> str:
    return (
        "请把完整剧本拆成可直接生产的漫剧分镜。只输出合法 JSON 对象。\n"
        f"目标约 {shot_count} 镜；允许根据剧情在 1 到 60 镜内调整。视觉风格：{visual_style}。\n"
        "每镜必须包含 shotId、shotVersionId、order、timecode(startMs/endMs/targetDurationMs)、"
        "camera(shotSize/angle/movement/composition)、performance(emotion/action)、"
        "visualDescription、audio(dialogue/narration/subtitleZh/subtitleEn/sfx/bgmMood)、"
        "references(characters[{name,description}]/scenes[{name,description}]/"
        "characterVersionIds/sceneVersionIds/propVersionIds)、"
        "prompts(image/video/negative)、continuity。\n"
        "references.characters/scenes 只列本镜实际出现的可命名实体，description 至少 5 字并写明可复用视觉特征；"
        "不要把 versionId 当成名称。\n"
        "时间码必须连续；每镜画面必须具体且推动剧情，不得写“同上”或“保持不变”。\n"
        "输出结构：{\"title\":\"...\",\"shots\":[{...}]}。\n\n"
        f"完整剧本：\n{script_text}"
    )


def _comic_shot_payload(operation_input: dict[str, Any]) -> dict[str, Any]:
    raw = _find_named_dict(operation_input, ("shot", "shotSpec"))
    if raw is None and operation_input.get("shotId"):
        raw = operation_input
    if raw is None:
        raise RuntimeError("comic shot operation requires a single shot object")
    return _normalize_comic_shot(raw, index=_positive_int(raw.get("order") or raw.get("index"), fallback=1, maximum=10000), cursor_ms=0)


def _comic_asset_payload(operation_input: dict[str, Any], asset_type: str) -> dict[str, Any]:
    names = ("character", "asset") if asset_type == "CHARACTER" else ("scene", "location", "asset")
    raw = _find_named_dict(operation_input, names)
    if raw is not None:
        return raw
    if operation_input.get("assetId") or operation_input.get("id"):
        return operation_input
    raise RuntimeError(f"comic {asset_type.lower()} reference operation requires one asset")


def _comic_keyframe_payload(operation_input: dict[str, Any], shot_id: str) -> dict[str, Any]:
    raw = _find_named_dict(
        operation_input,
        ("selectedKeyframeVersion", "keyframeVersion"),
    )
    if raw is not None:
        raw_shot_id = str(raw.get("shotId") or "").strip()
        if raw_shot_id and raw_shot_id != shot_id:
            raise RuntimeError(f"selected keyframe belongs to another shot: {raw_shot_id}")
        return raw
    if operation_input.get("keyframeVersionId") and operation_input.get("imageUrl"):
        return operation_input
    raise RuntimeError("comic.shot_video requires selectedKeyframeVersion")


def _comic_reference_images_for_shot(
    operation_input: dict[str, Any],
    shot: dict[str, Any],
) -> tuple[list[str], list[str]]:
    raw_versions = _collect_named_list_items(operation_input, ("referenceAssetVersions",))
    versions = [item for item in raw_versions if isinstance(item, dict)]
    single = _find_named_value(operation_input, ("referenceAssetVersion",))
    if isinstance(single, dict) and not versions:
        versions = [single]
    references = shot.get("references") if isinstance(shot.get("references"), dict) else {}
    requested_ids = _dedupe([
        *_as_string_list(references.get("characterVersionIds")),
        *_as_string_list(references.get("sceneVersionIds") or references.get("sceneVersionId")),
        *_as_string_list(references.get("propVersionIds")),
        *_as_string_list(references.get("assetVersionIds")),
    ])
    direct_urls = _dedupe(
        _as_string_list(
            _collect_named_list_items(
                operation_input,
                (
                    "characterReferenceAssetUrls",
                    "sceneReferenceAssetUrls",
                    "referenceAssetUrls",
                    "referenceImages",
                ),
            )
        )
    )
    by_version: dict[str, dict[str, Any]] = {}
    for version in versions:
        version_id = str(version.get("assetVersionId") or version.get("versionId") or "").strip()
        if version_id:
            by_version[version_id] = version
    if requested_ids:
        missing = [version_id for version_id in requested_ids if version_id not in by_version]
        if missing and not versions and direct_urls:
            return direct_urls, requested_ids
        if missing:
            raise RuntimeError("missing reference asset versions: " + ", ".join(missing))
        selected_ids = requested_ids
    else:
        selected_ids = list(by_version)

    urls: list[str] = []
    for version_id in selected_ids:
        version = by_version[version_id]
        status = str(version.get("status") or "").upper()
        if status and status not in {"APPROVED", "LOCKED", "READY", "PUBLISHED"}:
            raise RuntimeError(f"reference asset version is not approved: {version_id}/{status}")
        urls.extend(_comic_media_urls(version))
    if not versions:
        urls.extend(direct_urls)
    urls = _dedupe([url for url in urls if url])
    if requested_ids and not urls:
        raise RuntimeError("selected reference asset versions contain no image URLs")
    return urls, selected_ids


def _comic_media_urls(value: Any) -> list[str]:
    urls: list[str] = []
    if isinstance(value, str):
        candidate = value.strip()
        if candidate.startswith(("http://", "https://", "/", "data:image/")):
            urls.append(candidate)
    elif isinstance(value, list):
        for item in value:
            urls.extend(_comic_media_urls(item))
    elif isinstance(value, dict):
        for key, nested in value.items():
            normalized = str(key).lower()
            if normalized in {"url", "imageurl", "resourceurl", "referenceimages", "views", "anchors"}:
                urls.extend(_comic_media_urls(nested))
            elif isinstance(nested, (dict, list)) and normalized in {"front", "side", "back", "board"}:
                urls.extend(_comic_media_urls(nested))
    return _dedupe(urls)


def _build_comic_shot_image_prompt(shot: dict[str, Any], form: dict[str, Any]) -> str:
    prompts = shot.get("prompts") if isinstance(shot.get("prompts"), dict) else {}
    camera = shot.get("camera") if isinstance(shot.get("camera"), dict) else {}
    performance = shot.get("performance") if isinstance(shot.get("performance"), dict) else {}
    continuity = shot.get("continuity") if isinstance(shot.get("continuity"), dict) else {}
    base = str(prompts.get("image") or shot.get("visualDescription") or "").strip()
    details = ", ".join(
        value
        for value in (
            str(camera.get("shotSize") or "").strip(),
            str(camera.get("angle") or "").strip(),
            str(camera.get("movement") or "").strip(),
            str(camera.get("composition") or "").strip(),
            str(performance.get("emotion") or "").strip(),
            str(performance.get("action") or "").strip(),
        )
        if value
    )
    negative = str(prompts.get("negative") or "").strip()
    prompt = (
        f"{base}. {details}. Visual style: {form.get('visualStyle') or 'cinematic comic'}. "
        "Use every supplied reference image to preserve exact character identity, wardrobe, props, and scene layout. "
        f"Continuity state: {json.dumps(continuity, ensure_ascii=False)}. No text, no watermark."
    )
    if negative:
        prompt += f" Negative prompt: {negative}."
    return prompt


def _build_comic_shot_video_prompt(shot: dict[str, Any], form: dict[str, Any]) -> str:
    prompts = shot.get("prompts") if isinstance(shot.get("prompts"), dict) else {}
    camera = shot.get("camera") if isinstance(shot.get("camera"), dict) else {}
    performance = shot.get("performance") if isinstance(shot.get("performance"), dict) else {}
    base = str(prompts.get("video") or shot.get("visualDescription") or "").strip()
    return (
        f"{base}. Camera movement: {camera.get('movement') or 'natural controlled motion'}. "
        f"Performance: {performance.get('action') or ''}; emotion: {performance.get('emotion') or ''}. "
        "Preserve the selected keyframe and every supplied character/scene reference exactly. "
        f"Aspect ratio: {form.get('aspectRatio') or '16:9'}."
    )


def _comic_shot_duration_seconds(shot: dict[str, Any]) -> int:
    timecode = shot.get("timecode") if isinstance(shot.get("timecode"), dict) else {}
    duration_ms = _int_or_none(timecode.get("targetDurationMs"))
    if duration_ms is None:
        duration_ms = _int_or_none(timecode.get("endMs")) or SCENE_SECONDS * 1000
        duration_ms -= _int_or_none(timecode.get("startMs")) or 0
    return max(1, min(15, int(round(max(duration_ms, 1) / 1000))))


def _comic_selected_shot_versions(operation_input: dict[str, Any]) -> list[dict[str, Any]]:
    raw_items = operation_input.get("selectedShotVersions")
    if not isinstance(raw_items, list):
        found = _find_named_value(operation_input, ("selectedShotVersions",))
        raw_items = found if isinstance(found, list) else None
    if not raw_items:
        raise DigitalHumanPostprocessError("comic.compose requires selectedShotVersions")

    selected: list[dict[str, Any]] = []
    seen_shots: set[str] = set()
    for position, raw in enumerate(raw_items, start=1):
        if not isinstance(raw, dict) or raw.get("selected") is False:
            continue
        shot = raw.get("shot") if isinstance(raw.get("shot"), dict) else {}
        clip = (
            raw.get("selectedClipVersion")
            if isinstance(raw.get("selectedClipVersion"), dict)
            else raw.get("clipVersion") if isinstance(raw.get("clipVersion"), dict) else {}
        )
        audio = (
            raw.get("selectedAudioVersion")
            if isinstance(raw.get("selectedAudioVersion"), dict)
            else raw.get("audioVersion") if isinstance(raw.get("audioVersion"), dict) else {}
        )
        keyframe = (
            raw.get("selectedKeyframeVersion")
            if isinstance(raw.get("selectedKeyframeVersion"), dict)
            else raw.get("keyframeVersion") if isinstance(raw.get("keyframeVersion"), dict) else {}
        )
        shot_id = str(raw.get("shotId") or shot.get("shotId") or "").strip()
        shot_version_id = str(raw.get("shotVersionId") or shot.get("shotVersionId") or "").strip()
        clip_version_id = str(raw.get("clipVersionId") or clip.get("clipVersionId") or "").strip()
        video_url = str(raw.get("videoUrl") or clip.get("videoUrl") or "").strip()
        if not shot_id or not shot_version_id or not clip_version_id or not video_url:
            raise DigitalHumanPostprocessError(
                f"selected shot {position} requires shotId, shotVersionId, clipVersionId and videoUrl"
            )
        if shot_id in seen_shots:
            raise DigitalHumanPostprocessError(f"duplicate selected shot: {shot_id}")
        seen_shots.add(shot_id)
        shot_audio = shot.get("audio") if isinstance(shot.get("audio"), dict) else {}
        selected.append(
            {
                "shotId": shot_id,
                "shotVersionId": shot_version_id,
                "order": _positive_int(raw.get("order") or shot.get("order") or position, fallback=position, maximum=10000),
                "clipVersionId": clip_version_id,
                "videoUrl": video_url,
                "audioVersionId": str(raw.get("audioVersionId") or audio.get("audioVersionId") or ""),
                "audioUrl": str(raw.get("audioUrl") or audio.get("audioUrl") or ""),
                "keyframeVersionId": str(raw.get("keyframeVersionId") or keyframe.get("keyframeVersionId") or ""),
                "keyframeImageUrl": str(raw.get("keyframeImageUrl") or keyframe.get("imageUrl") or ""),
                "subtitleZh": str(
                    raw.get("subtitleZh")
                    or shot_audio.get("subtitleZh")
                    or shot.get("subtitleZh")
                    or shot_audio.get("dialogue")
                    or shot.get("dialogue")
                    or ""
                ),
                "subtitleEn": str(raw.get("subtitleEn") or shot_audio.get("subtitleEn") or shot.get("subtitleEn") or ""),
            }
        )
    if not selected:
        raise DigitalHumanPostprocessError("comic.compose has no selected shot versions")
    return sorted(selected, key=lambda item: (item["order"], item["shotId"]))


def _safe_segment_name(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_-]+", "-", value).strip("-")
    return safe[:80] or "shot"


def _normalize_assets(value: Any, asset_type: str) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    assets: list[dict[str, Any]] = []
    for index, raw in enumerate(value, start=1):
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or raw.get("title") or raw.get("label") or "").strip()
        if not name:
            continue
        asset_id = str(raw.get("id") or raw.get("assetId") or f"{asset_type}-{index}").strip()
        item = {**raw, "id": asset_id, "assetId": asset_id, "assetType": asset_type, "name": name}
        assets.append(item)
    return assets


def _build_reference_asset_specs(script: dict[str, Any], form: dict[str, Any]) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    for asset_type, field in (("character", "characters"), ("prop", "props"), ("location", "locations")):
        for asset in _normalize_assets(script.get(field), asset_type):
            description = (
                asset.get("appearance")
                or asset.get("description")
                or asset.get("personality")
                or form.get("visualStyle")
                or asset.get("name")
            )
            specs.append(
                {
                    "assetType": asset_type,
                    "assetId": str(asset.get("assetId") or asset.get("id") or asset.get("name")),
                    "name": str(asset.get("name") or ""),
                    "description": str(description or ""),
                }
            )
    return specs


def _build_three_view_reference_prompt(spec: dict[str, Any], form: dict[str, Any]) -> str:
    asset_type = str(spec.get("assetType") or "asset").strip()
    name = str(spec.get("name") or "").strip()
    description = str(spec.get("description") or "").strip()
    visual_style = str(form.get("visualStyle") or "cinematic comic style").strip()
    if asset_type == "location":
        views = "wide establishing view, side angle view, top-down layout view"
    else:
        views = "front view, side view, back view"
    return (
        f"Create a single three-view reference board for the {asset_type} '{name}'. "
        f"Show {views} in one image, clean separation between views, pure white background, "
        f"no text, no labels, no watermark, full-body/object/location consistency, {visual_style}. "
        f"Stable reusable visual details: {description}."
    )


def _scene_reference_asset_ids(scene: dict[str, Any], reference_specs: list[dict[str, Any]]) -> list[str]:
    explicit: list[str] = []
    for key in ("characterRefs", "characterRef", "propRefs", "propRef", "locationRefs", "locationRef", "referenceAssetIds"):
        explicit.extend(_as_string_list(scene.get(key)))
    if explicit:
        return _dedupe(explicit)

    searchable = " ".join(
        str(scene.get(key) or "")
        for key in ("characterScene", "sceneDescription", "plot", "dialogue", "narration")
    ).lower()
    inferred: list[str] = []
    for spec in reference_specs:
        name = str(spec.get("name") or "").strip()
        asset_id = str(spec.get("assetId") or "").strip()
        if name and name.lower() in searchable:
            inferred.append(asset_id)
    return _dedupe(inferred)


def _reference_prompt_hint(reference_asset_ids: list[str], reference_specs: list[dict[str, Any]]) -> str:
    if not reference_asset_ids:
        return ""
    by_id = {str(item.get("assetId") or ""): item for item in reference_specs}
    names = [str(by_id.get(asset_id, {}).get("name") or asset_id) for asset_id in reference_asset_ids]
    return "Preserve these reference assets exactly: " + ", ".join(names) + "."


def _reference_images_for_scene(
    scene: dict[str, Any],
    image_entry: dict[str, Any],
    reference_assets: list[Any],
) -> list[str]:
    asset_ids = _as_string_list((image_entry or {}).get("referenceAssetIds"))
    if not asset_ids:
        asset_ids = _scene_reference_asset_ids(scene, [item for item in reference_assets if isinstance(item, dict)])
    by_id: dict[str, str] = {}
    for raw in reference_assets:
        if not isinstance(raw, dict):
            continue
        asset_id = str(raw.get("assetId") or raw.get("id") or "").strip()
        image_url = str(raw.get("imageUrl") or raw.get("url") or "").strip()
        if asset_id and image_url:
            by_id[asset_id] = image_url
    return [by_id[asset_id] for asset_id in _dedupe(asset_ids) if asset_id in by_id]


def _as_string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        raw_items = value
    else:
        raw_items = re.split(r"[,，、/|]\s*", str(value))
    return [str(item).strip() for item in raw_items if str(item).strip()]


def _dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def _persist_audio_data_url(task_id: int, audio_data_url: str, index: int = 1) -> str:
    prefix = "base64,"
    if prefix not in audio_data_url:
        return audio_data_url
    encoded = audio_data_url.split(prefix, 1)[1]
    audio_bytes = base64.b64decode(encoded)
    suffix = "" if index <= 1 else f"-{index}"
    relative_key = f"audio/{task_id}/voice{suffix}.mp3"
    return asset_storage.put_bytes(relative_key, audio_bytes, "audio/mpeg")


def _matches_workflow_video_checkpoint(
    checkpoint: dict[str, Any] | None,
    provider: str,
    protocol: str,
    model: str,
) -> bool:
    return bool(
        isinstance(checkpoint, dict)
        and checkpoint.get("kind") == "WORKFLOW_VIDEO"
        and str(checkpoint.get("provider") or "") == provider
        and str(checkpoint.get("protocol") or "") == protocol
        and str(checkpoint.get("model") or "") == model
        and isinstance(checkpoint.get("scenes"), dict)
    )


def _matches_workflow_tts_checkpoint(
    checkpoint: dict[str, Any] | None,
    provider: str,
    protocol: str,
    model: str,
) -> bool:
    return bool(
        isinstance(checkpoint, dict)
        and checkpoint.get("kind") == "WORKFLOW_TTS"
        and str(checkpoint.get("provider") or "") == provider
        and str(checkpoint.get("protocol") or "") == protocol
        and str(checkpoint.get("model") or "") == model
        and isinstance(checkpoint.get("scenes"), dict)
    )


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


def _tts_param_overrides(source: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(source, dict):
        return {}
    overrides: dict[str, Any] = {}
    nested = source.get("ttsParams")
    if isinstance(nested, dict):
        overrides.update(nested)
    for key in TTS_PARAMETER_KEYS:
        value = source.get(key)
        if value is not None and value != "":
            overrides[key] = value
    return overrides


def _tts_param_value(params: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


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


def _billing_usage_payload(output: Any) -> dict[str, Any]:
    payload = _provider_accounting_payload(output)
    if not isinstance(output, dict):
        return payload
    accounting = output.get("providerAccounting")
    usage = output.get("usage")
    sources = [
        output,
        usage if isinstance(usage, dict) else {},
        accounting if isinstance(accounting, dict) else {},
    ]
    fields = {
        "promptTokens": ("promptTokens", "prompt_tokens", "inputTokens", "input_tokens"),
        "completionTokens": ("completionTokens", "completion_tokens", "outputTokens", "output_tokens"),
        "billableUnits": ("billableUnits", "billable_units", "units"),
    }
    for target, aliases in fields.items():
        value = None
        for source in sources:
            value = _first_defined(source, *aliases)
            if value is not None:
                break
        if value is not None:
            payload[target] = _non_negative_int(value)
    provider_called = None
    for source in sources:
        provider_called = _first_defined(source, "providerCalled", "provider_called")
        if provider_called is not None:
            break
    if provider_called is not None:
        payload["providerCalled"] = _boolean_value(provider_called)
    return payload


def _empty_usage(*, provider_called: bool = False) -> dict[str, Any]:
    return {
        "promptTokens": 0,
        "completionTokens": 0,
        "billableUnits": 0,
        "providerCalled": provider_called,
    }


def _accumulate_usage(total: dict[str, Any], usage: dict[str, Any]) -> None:
    total["providerCalled"] = bool(total.get("providerCalled")) or bool(usage.get("providerCalled"))
    for field in ("promptTokens", "completionTokens", "billableUnits"):
        total[field] = _non_negative_int(total.get(field)) + _non_negative_int(usage.get(field))


def _text_generation_with_usage(model_client: Any, prompt: str, **kwargs: Any) -> tuple[str, dict[str, Any]]:
    generate_with_usage = getattr(model_client, "generate_with_usage", None)
    if callable(generate_with_usage):
        result = generate_with_usage(prompt, **kwargs)
        content = result.get("content") if isinstance(result, dict) else getattr(result, "content", "")
        usage = _empty_usage(provider_called=True)
        usage["promptTokens"] = _non_negative_int(
            result.get("promptTokens") if isinstance(result, dict) else getattr(result, "prompt_tokens", 0)
        )
        usage["completionTokens"] = _non_negative_int(
            result.get("completionTokens") if isinstance(result, dict) else getattr(result, "completion_tokens", 0)
        )
        return str(content or ""), usage
    return str(model_client.generate(prompt, **kwargs) or ""), _empty_usage(provider_called=True)


def _model_billing_unit(model_config: dict[str, Any]) -> str:
    return str(model_config.get("billingUnit") or "").strip().upper()


def _validate_success_usage(model_config: dict[str, Any], payload: dict[str, Any]) -> None:
    if payload.get("providerCalled") is not True:
        return
    unit = _model_billing_unit(model_config)
    if unit in {"TOKEN_PER_M", "IMAGE_TOKEN"}:
        if _non_negative_int(payload.get("promptTokens")) + _non_negative_int(payload.get("completionTokens")) <= 0:
            raise RuntimeError(f"provider usage is missing token counts for {unit}")
        return
    if unit in {"PER_CALL", "PER_SECOND", "PER_CHARACTER"}:
        if _non_negative_int(payload.get("billableUnits")) <= 0:
            raise RuntimeError(f"provider usage is missing billable units for {unit}")
        return
    raise RuntimeError(f"provider usage has unsupported billing unit: {unit or 'EMPTY'}")


def _default_billable_units(
    model_config: dict[str, Any],
    *,
    call_count: int = 1,
    duration_seconds: int | float | None = None,
) -> int:
    unit = _model_billing_unit(model_config)
    if unit == "PER_CALL":
        return max(0, int(call_count))
    if unit == "PER_SECOND" and duration_seconds is not None:
        return max(0, int(math.ceil(float(duration_seconds))))
    return 0


def _provider_call_usage(
    source: Any,
    model_config: dict[str, Any],
    *,
    call_count: int = 1,
    duration_seconds: int | float | None = None,
) -> dict[str, Any]:
    raw = source if isinstance(source, dict) else getattr(source, "last_usage", {})
    usage = _empty_usage(provider_called=True)
    if isinstance(raw, dict):
        reported = _billing_usage_payload(raw)
        for field in ("promptTokens", "completionTokens", "billableUnits"):
            if field in reported:
                usage[field] = _non_negative_int(reported[field])
    if usage["billableUnits"] <= 0:
        usage["billableUnits"] = _default_billable_units(
            model_config,
            call_count=call_count,
            duration_seconds=duration_seconds,
        )
    return usage


def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def _boolean_value(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return bool(value)


def _provider_accounting_payload(output: Any) -> dict[str, Any]:
    if not isinstance(output, dict):
        return {}
    accounting = output.get("providerAccounting")
    source = accounting if isinstance(accounting, dict) else output
    request_id = _first_defined(source, "providerRequestId", "provider_request_id", "requestId")
    cost_amount = _first_defined(source, "providerCostAmount", "provider_cost_amount")
    currency = _first_defined(source, "providerCostCurrency", "provider_cost_currency")
    payload: dict[str, Any] = {}
    if request_id is not None and str(request_id).strip():
        payload["providerRequestId"] = str(request_id).strip()
    if cost_amount is not None and str(cost_amount).strip():
        payload["providerCostAmount"] = cost_amount
        if currency is not None and str(currency).strip():
            payload["providerCostCurrency"] = str(currency).strip().upper()
    return payload


def _first_defined(source: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in source and source[key] is not None:
            return source[key]
    return None


def _provider_failure_accounting_payload(error: Exception) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    attribute_map = {
        "provider_charged": "providerCharged",
        "provider_cost_amount": "providerCostAmount",
        "provider_error_code": "providerErrorCode",
        "provider_request_id": "providerRequestId",
        "failure_stage": "failureStage",
    }
    for attribute, field in attribute_map.items():
        value = getattr(error, attribute, None)
        if value is not None and (not isinstance(value, str) or value.strip()):
            payload[field] = value.strip() if isinstance(value, str) else value
    currency = getattr(error, "provider_cost_currency", None)
    if currency is not None and str(currency).strip():
        payload["providerCostCurrency"] = str(currency).strip().upper()
    return payload


def _provider_protocol(model_config: dict[str, Any]) -> str:
    provider = str(model_config.get("provider") or "").lower()
    try:
        return str(provider_registry.provider_protocol(provider) or "").lower()
    except Exception:
        return ""


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
            model_config=model_config,
        )

        def _gen(prompt: str, reference_images: list[str] | None = None) -> str:
            urls = client.generate_images(
                prompt=prompt,
                model=model_name,
                image_size="1024x576",
                batch_size=1,
                image=reference_images or None,
            )
            _gen.last_usage = dict(client.last_usage or {})
            return urls[0] if urls else ""

        _gen.last_usage = {}

        return _gen

    sf_client = SiliconFlowVideoClient(
        api_key=resolve_siliconflow_api_key(model_config),
        base_url=model_config.get("baseUrl"),
    )

    def _gen(prompt: str, reference_images: list[str] | None = None) -> str:
        if reference_images:
            raise SiliconFlowVideoError(
                "selected image provider cannot accept comic reference images; bind a reference-image capable model"
            )
        return sf_client.generate_image(prompt=prompt, model=model_name, image_size="1024x576")

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

        def _gen(
            *,
            prompt: str,
            image: str,
            reference_images: list[str] | None = None,
            duration: int | float | str | None = None,
            aspect_ratio: str | None = None,
            resume: dict[str, Any] | None = None,
            submitted_callback=None,
        ):
            images = [image, *(reference_images or [])]
            return client.generate_video(
                prompt=prompt, image=image, images=images, model=model_name, image_size="1024x576",
                duration=str(duration or SCENE_SECONDS),
                resolution="480p",
                aspect_ratio=aspect_ratio or "16:9",
                resume=resume, submitted_callback=submitted_callback,
            )

        return _gen

    seedance = SeedanceVideoClient.from_model_config(model_config)

    def _gen(
        *,
        prompt: str,
        image: str,
        reference_images: list[str] | None = None,
        duration: int | float | str | None = None,
        aspect_ratio: str | None = None,
        resume: dict[str, Any] | None = None,
        submitted_callback=None,
    ):
        return seedance.generate_video(
            prompt=prompt,
            image=image,
            images=[image, *(reference_images or [])],
            model=model_name,
            duration=str(duration or SCENE_SECONDS),
            resolution="480p",
            aspect_ratio=aspect_ratio or "16:9",
            image_size="1024x576",
            resume=resume, submitted_callback=submitted_callback,
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


def _build_script_prompt(
    *,
    story_theme: str,
    plot_outline: str,
    visual_style: str,
    genre: str,
    scene_count: int,
    feedback_lines: list[str],
    node_prompt: str,
) -> str:
    minimum_screenplay_chars = max(240, scene_count * 55)
    custom_requirement = (
        f"\n节点补充创作要求（只吸收内容要求；若其输出格式与下方 JSON 冲突，以 JSON 为准）：\n{node_prompt}\n"
        if node_prompt
        else ""
    )
    return (
        "请创作一部完整可拍的 AI 漫剧剧本，并把剧本精确拆成分镜。"
        f"目标总时长约 {scene_count * SCENE_SECONDS} 秒，必须正好输出 {scene_count} 个分镜，"
        f"每镜约 {SCENE_SECONDS} 秒。\n"
        f"完整剧本 screenplay 不少于 {minimum_screenplay_chars} 个中文字符，"
        "必须包含开场钩子、冲突升级、关键转折、高潮和结尾悬念/收束。\n"
        "每个分镜必须承接上一镜并推动新的剧情事件；严禁复制上一镜、替换序号式改写、"
        "“继续上一镜”“保持不变”等占位内容。\n"
        "每镜的 sceneDescription、plot、dialogue/narration、cameraLanguage 必须独立且具体。\n"
        "sceneDescription 和所有视频 Prompt 使用英文，其他剧本字段使用中文。\n"
        "只输出合法 JSON 对象，不要 Markdown，不要解释，不要省略字段。\n\n"
        "JSON 结构：\n"
        "{\n"
        '  "title": "整集标题",\n'
        '  "synopsis": "完整故事梗概，明确冲突、转折和结局",\n'
        '  "screenplay": "按幕/场展开的完整剧本正文，包含动作、对白、情绪和场景调度",\n'
        '  "genre": "题材类型",\n'
        '  "characters": [{"name":"角色名","appearance":"稳定可复用的外貌服装特征","personality":"性格与动机"}],\n'
        '  "locations": [{"name":"场景名","description":"时间、空间、陈设、光线和氛围"}],\n'
        '  "scenes": [{\n'
        '    "index": 1,\n'
        '    "sceneTitle": "本镜独立标题",\n'
        f'    "durationSeconds": {SCENE_SECONDS},\n'
        '    "characterScene": "本镜角色 / 场景",\n'
        '    "cameraLanguage": "景别 + 机位 + 运镜 + 构图重点",\n'
        '    "sceneDescription": "Detailed unique cinematic image prompt in English",\n'
        '    "plot": "本镜发生的具体新事件及其叙事作用",\n'
        '    "dialogue": "5秒内可说完的自然台词，无台词时留空",\n'
        '    "narration": "必要旁白，无旁白时留空",\n'
        '    "voiceDirection": "【说话人=角色名｜性别｜年龄段｜情绪】台词",\n'
        '    "textToVideoPrompt": "Unique text-to-video prompt in English",\n'
        '    "imageToVideoPrompt": "Unique motion instructions in English",\n'
        '    "multiImageVideoPrompt": "Unique transition instructions in English",\n'
        '    "keyframeTransitionPrompt": "Unique keyframe continuity instructions in English",\n'
        '    "subtitleZh": "中文字幕",\n'
        '    "subtitleEn": "English subtitle",\n'
        '    "presenterGender": "female 或 male"\n'
        "  }]\n"
        "}\n"
        f"{custom_requirement}\n"
        f"主题：{story_theme}\n"
        f"题材：{genre or '未指定'}\n"
        f"用户梗概：{plot_outline or '请围绕主题自行设计完整故事线'}\n"
        f"视觉风格：{visual_style}\n"
        + (f"修改意见：\n{chr(10).join(feedback_lines)}\n" if feedback_lines else "")
    )


def _script_validation_errors(parsed: dict[str, Any] | None, expected_scene_count: int) -> list[str]:
    if not isinstance(parsed, dict):
        return ["无法解析 JSON 对象"]
    errors: list[str] = []
    for field in ("title", "synopsis", "screenplay"):
        if not str(parsed.get(field) or "").strip():
            errors.append(f"缺少 {field}")
    screenplay = re.sub(r"\s+", "", str(parsed.get("screenplay") or ""))
    minimum_screenplay_chars = max(240, expected_scene_count * 55)
    if screenplay and len(screenplay) < minimum_screenplay_chars:
        errors.append(f"screenplay 过短，至少需要 {minimum_screenplay_chars} 字")

    scenes = parsed.get("scenes")
    if not isinstance(scenes, list):
        return errors + ["scenes 必须是数组"]
    if len(scenes) != expected_scene_count:
        errors.append(f"分镜数量应为 {expected_scene_count}，实际为 {len(scenes)}")

    signatures: dict[str, int] = {}
    for position, raw_scene in enumerate(scenes, start=1):
        if not isinstance(raw_scene, dict):
            errors.append(f"分镜{position}不是对象")
            continue
        required = ("sceneTitle", "characterScene", "cameraLanguage", "sceneDescription", "plot")
        for field in required:
            if not str(raw_scene.get(field) or "").strip():
                errors.append(f"分镜{position}缺少 {field}")
        if not str(raw_scene.get("dialogue") or "").strip() and not str(raw_scene.get("narration") or "").strip():
            errors.append(f"分镜{position}缺少 dialogue/narration")
        description = str(raw_scene.get("sceneDescription") or "").strip()
        plot = str(raw_scene.get("plot") or "").strip()
        if description and len(description) < 45:
            errors.append(f"分镜{position} sceneDescription 过短")
        if plot and len(plot) < 10:
            errors.append(f"分镜{position} plot 过短")
        signature = re.sub(
            r"[\W_]+",
            "",
            "|".join(
                str(raw_scene.get(field) or "").lower()
                for field in ("sceneDescription", "plot", "dialogue", "narration")
            ),
        )
        if signature:
            previous = signatures.get(signature)
            if previous is not None:
                errors.append(f"分镜{position}与分镜{previous}内容重复")
            else:
                signatures[signature] = position
    return errors


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
        narration = str(source.get("narration") or "")
        if not dialogue and not narration:
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
                "narration": narration,
                "presenterGender": str(source.get("presenterGender") or fallback["presenterGender"]),
                "durationSeconds": int(source.get("durationSeconds") or SCENE_SECONDS),
                "characterScene": str(source.get("characterScene") or ""),
                "cameraLanguage": str(source.get("cameraLanguage") or ""),
                "plot": str(source.get("plot") or ""),
                "voiceDirection": str(source.get("voiceDirection") or ""),
                "textToVideoPrompt": str(source.get("textToVideoPrompt") or scene_desc),
                "imageToVideoPrompt": str(
                    source.get("imageToVideoPrompt")
                    or f"Animate this scene with natural character and camera motion: {scene_desc}"
                ),
                "multiImageVideoPrompt": str(
                    source.get("multiImageVideoPrompt")
                    or "Create a smooth transition to the next scene while preserving character identity and continuity."
                ),
                "keyframeTransitionPrompt": str(
                    source.get("keyframeTransitionPrompt")
                    or "Maintain character identity, outfit, spatial continuity, and cinematic pacing between keyframes."
                ),
                "characterRefs": _as_string_list(source.get("characterRefs") or source.get("characterRef")),
                "propRefs": _as_string_list(source.get("propRefs") or source.get("propRef")),
                "locationRefs": _as_string_list(source.get("locationRefs") or source.get("locationRef")),
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
