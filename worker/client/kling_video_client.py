import json
import base64
import hashlib
import hmac
import ipaddress
import logging
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests

from client.provider_error import (
    ProviderCallError,
    rejected_response_metadata,
    request_was_not_sent,
    transport_failure_metadata,
)
from config import settings
from utils.outbound_http import OutboundRequestsClient


LOGGER = logging.getLogger(__name__)
SUCCESS_STATUSES = {"succeeded", "succeed", "success", "completed", "done", "finish", "finished"}
FAILED_STATUSES = {"failed", "fail", "failure", "error", "cancelled", "canceled", "timeout", "timed_out"}


class KlingVideoError(ProviderCallError):
    pass


class KlingVideoTimeoutError(KlingVideoError):
    pass


class KlingVideoClient:
    """Kling-compatible video and image adapter.

    Defaults follow the public Kling-compatible REST shape:
    POST /v1/videos/text2video or /v1/videos/image2video, then poll the
    corresponding task query endpoint. Base URL and model are intentionally
    configurable so official or proxy gateways can share this adapter.
    """

    _MAX_TRANSPORT_ATTEMPTS = 3
    _RETRY_BASE_DELAY_SECONDS = 0.5

    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        access_key: str | None = None,
        secret_key: str | None = None,
        text_path: str | None = None,
        image_path: str | None = None,
        text_result_path: str | None = None,
        image_result_path: str | None = None,
        image_generation_path: str | None = None,
        image_generation_result_path: str | None = None,
        poll_interval_seconds: float | None = None,
        timeout_seconds: int | None = None,
    ) -> None:
        self.base_url = (base_url or settings.kling_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.kling_api_key
        self.access_key = access_key if access_key is not None else settings.kling_access_key
        self.secret_key = secret_key if secret_key is not None else settings.kling_secret_key
        self.text_path = text_path or settings.kling_video_text_path
        self.image_path = image_path or settings.kling_video_image_path
        self.text_result_path = text_result_path or settings.kling_video_text_result_path
        self.image_result_path = image_result_path or settings.kling_video_image_result_path
        self.image_generation_path = image_generation_path or settings.kling_image_generation_path
        self.image_generation_result_path = image_generation_result_path or settings.kling_image_result_path
        self.poll_interval_seconds = poll_interval_seconds or settings.kling_poll_interval_seconds
        self.timeout_seconds = timeout_seconds or settings.kling_timeout_seconds
        self.timeout = (10, 300)
        self.session = OutboundRequestsClient()
        self.max_input_image_bytes = 20 * 1024 * 1024

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        image_tail: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "",
        mode: str = "",
        sound: str = "",
        callback_url: str = "",
        external_task_id: str = "",
        create_path: str = "",
        result_path_template: str = "",
        video_url: str = "",
        character_orientation: str = "",
        static_mask: str = "",
        dynamic_masks: Any = None,
        image_list: Any = None,
        video_list: Any = None,
        element_list: Any = None,
        multi_shot: str = "",
        shot_type: str = "",
        multi_prompt: Any = None,
        cfg_scale: float | None = None,
        keep_original_sound: str = "",
        api_task: str = "",
    ) -> dict[str, Any]:
        if not self._has_auth():
            raise KlingVideoError("Kling credentials are not configured")

        normalized_api_task = self._normalize_api_task(api_task)
        payload = self._build_video_payload(
            prompt=prompt,
            image_size=image_size,
            negative_prompt=negative_prompt,
            model=model or settings.kling_video_model,
            image=image,
            image_tail=image_tail,
            seed=seed,
            duration=duration,
            aspect_ratio=aspect_ratio,
            resolution=resolution,
            mode=mode,
            sound=sound,
            callback_url=callback_url,
            external_task_id=external_task_id,
            video_url=video_url,
            character_orientation=character_orientation,
            static_mask=static_mask,
            dynamic_masks=dynamic_masks,
            image_list=image_list,
            video_list=video_list,
            element_list=element_list,
            multi_shot=multi_shot,
            shot_type=shot_type,
            multi_prompt=multi_prompt,
            cfg_scale=cfg_scale,
            keep_original_sound=keep_original_sound,
            api_task=api_task,
        )
        if normalized_api_task == "motion_control":
            resolved_create_path = create_path.strip() or "/v1/videos/motion-control"
            resolved_result_path = result_path_template.strip() or "/v1/videos/motion-control/{task_id}"
        else:
            resolved_create_path, resolved_result_path = self._resolve_video_paths(
                create_path=create_path,
                result_path_template=result_path_template,
                image=image,
                video_url=video_url,
                image_list=image_list,
                video_list=video_list,
            )
        LOGGER.info(
            "kling video request path=%s payload=%s",
            resolved_create_path,
            _json_for_log(payload),
        )
        created = self._request("POST", resolved_create_path, payload)
        task_id = self._extract_task_id(created)
        finished = self.wait_for_video(task_id, result_path_template=resolved_result_path)
        return {
            "requestId": task_id,
            "status": self._extract_status(finished),
            "videoUrl": self._extract_video_url(finished),
            "reason": str(finished.get("reason") or finished.get("message") or ""),
            "seed": seed,
            "timings": {},
            "provider": "kling_video",
            "model": model or settings.kling_video_model,
            "resolution": resolution,
        }

    def generate_images(
        self,
        *,
        prompt: str,
        model: str | None = None,
        image_size: str = "1024x1024",
        batch_size: int = 1,
        negative_prompt: str = "",
        seed: int | None = None,
        guidance_scale: float | None = None,
        num_inference_steps: int | None = None,
        image: str = "",
        aspect_ratio: str = "",
        image_reference: str = "",
        image_fidelity: float | None = None,
        human_fidelity: float | None = None,
        image_list: Any = None,
        resolution: str = "",
        result_type: str = "",
    ) -> list[str]:
        if not self._has_auth():
            raise KlingVideoError("Kling credentials are not configured")

        omni_image = "omni-image" in str(self.image_generation_path or "")
        resolved_aspect_ratio = self._aspect_ratio(aspect_ratio, image_size)
        payload: dict[str, Any] = {
            "model_name": model or settings.kling_image_model,
            "prompt": prompt,
            "n": max(1, min(9 if omni_image else 4, batch_size)),
        }
        if resolved_aspect_ratio:
            payload["aspect_ratio"] = resolved_aspect_ratio
        if omni_image:
            encoded_image_list = self._encode_omni_image_list(image_list)
            if encoded_image_list:
                payload["image_list"] = encoded_image_list
            if resolution.strip():
                payload["resolution"] = resolution.strip()
            if result_type.strip():
                payload["result_type"] = result_type.strip()
        else:
            if image.strip():
                payload["image"] = self._image_to_base64(image.strip())
            if negative_prompt.strip():
                payload["negative_prompt"] = negative_prompt.strip()
            if image_reference.strip():
                payload["image_reference"] = image_reference.strip()
            if image_fidelity is not None:
                payload["image_fidelity"] = image_fidelity
            if human_fidelity is not None:
                payload["human_fidelity"] = human_fidelity
            if seed is not None:
                payload["seed"] = seed
            if guidance_scale is not None:
                payload["guidance_scale"] = guidance_scale
            if num_inference_steps is not None:
                payload["num_inference_steps"] = num_inference_steps
            if resolution.strip():
                payload["resolution"] = resolution.strip()
        LOGGER.info(
            "kling image generation request path=%s payload=%s",
            self.image_generation_path,
            _json_for_log(payload),
        )
        response = self._request("POST", self.image_generation_path, payload)
        urls = self._extract_image_urls_or_empty(response)
        if urls:
            return urls
        task_id = self._extract_task_id(response)
        finished = self.wait_for_images(task_id)
        return self._extract_image_urls(finished)

    def wait_for_video(self, task_id: str, *, result_path_template: str | None = None) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = self._task_result_path(task_id, result_path_template or self.image_result_path)
        while time.monotonic() < deadline:
            last_payload = self._request("GET", path, None)
            if self._extract_video_url_or_empty(last_payload):
                return last_payload
            status = self._extract_status(last_payload).lower()
            if status in SUCCESS_STATUSES:
                raise KlingVideoError(
                    self._describe_response_problem(
                        "kling video response reached terminal success but no video url",
                        last_payload,
                    )
                )
            if status in FAILED_STATUSES:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "kling video generation failed")
                if reason.strip().lower() in SUCCESS_STATUSES or self._has_success_indicator(last_payload):
                    raise KlingVideoError(
                        self._describe_response_problem(
                            "kling video response reached terminal success but no video url",
                            last_payload,
                        )
                    )
                raise KlingVideoError(self._describe_response_problem("kling video generation failed", last_payload))
            time.sleep(self.poll_interval_seconds)
        raise KlingVideoTimeoutError(
            self._describe_response_problem(
                f"kling video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}",
                last_payload,
            )
        )

    def wait_for_images(self, task_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = self._task_result_path(task_id, self.image_generation_result_path)
        while time.monotonic() < deadline:
            last_payload = self._request("GET", path, None)
            if self._extract_image_urls_or_empty(last_payload):
                return last_payload
            status = self._extract_status(last_payload).lower()
            if status in SUCCESS_STATUSES:
                raise KlingVideoError(
                    self._describe_response_problem(
                        "kling image response reached terminal success but no image url",
                        last_payload,
                    )
                )
            if status in FAILED_STATUSES:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "kling image generation failed")
                if reason.strip().lower() in SUCCESS_STATUSES or self._has_success_indicator(last_payload):
                    raise KlingVideoError(
                        self._describe_response_problem(
                            "kling image response reached terminal success but no image url",
                            last_payload,
                        )
                    )
                raise KlingVideoError(self._describe_response_problem("kling image generation failed", last_payload))
            time.sleep(self.poll_interval_seconds)
        raise KlingVideoTimeoutError(
            self._describe_response_problem(
                f"kling image generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}",
                last_payload,
            )
        )

    def _task_result_path(self, task_id: str, template: str) -> str:
        cleaned = str(template or "").strip() or "/v1/videos/image2video/{task_id}"
        if "{task_id}" in cleaned:
            return cleaned.replace("{task_id}", task_id)
        if "{taskId}" in cleaned:
            return cleaned.replace("{taskId}", task_id)
        return f"{cleaned.rstrip('/')}/{task_id}"

    def _resolve_video_paths(
        self,
        *,
        create_path: str,
        result_path_template: str,
        image: str,
        video_url: str,
        image_list: Any,
        video_list: Any,
    ) -> tuple[str, str]:
        if create_path.strip() and result_path_template.strip():
            return create_path.strip(), result_path_template.strip()
        if image.strip() or self._has_media_list(image_list):
            return self.image_path, self.image_result_path
        if video_url.strip() or self._has_media_list(video_list):
            return self.image_path, self.image_result_path
        return self.text_path, self.text_result_path

    @staticmethod
    def _has_media_list(value: Any) -> bool:
        if isinstance(value, list):
            return any(str(item).strip() for item in value)
        if isinstance(value, str):
            return bool(value.strip())
        return False

    def _build_video_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        image_tail: str,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
        resolution: str,
        mode: str,
        sound: str,
        callback_url: str,
        external_task_id: str,
        video_url: str = "",
        character_orientation: str = "",
        static_mask: str = "",
        dynamic_masks: Any = None,
        image_list: Any = None,
        video_list: Any = None,
        element_list: Any = None,
        multi_shot: str = "",
        shot_type: str = "",
        multi_prompt: Any = None,
        cfg_scale: float | None = None,
        keep_original_sound: str = "",
        api_task: str = "",
    ) -> dict[str, Any]:
        normalized_api_task = self._normalize_api_task(api_task)
        if normalized_api_task == "motion_control":
            return self._build_motion_control_payload(
                prompt=prompt,
                model=model,
                image=image,
                video_url=video_url,
                callback_url=callback_url,
                external_task_id=external_task_id,
                character_orientation=character_orientation,
                element_list=element_list,
                mode=mode,
                keep_original_sound=keep_original_sound,
            )
        is_omni_video = self._is_omni_video_model(model)
        is_multi_image2video = normalized_api_task == "multi_image2video"
        payload: dict[str, Any] = {
            "model_name": model,
        }
        if prompt.strip():
            payload["prompt"] = prompt.strip()
        duration_seconds = self._duration_seconds(duration)
        if duration_seconds is not None:
            payload["duration"] = str(duration_seconds)
        ratio = self._aspect_ratio(aspect_ratio, image_size)
        if ratio:
            payload["aspect_ratio"] = ratio
        if mode.strip():
            payload["mode"] = mode.strip()
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if image.strip():
            image_value = image.strip()
            encoded_image = self._image_payload_value(image_value)
            if video_url.strip() or character_orientation.strip():
                payload["image_url"] = encoded_image
            else:
                payload["image"] = encoded_image
        if image_tail.strip():
            payload["image_tail"] = self._image_to_base64(image_tail.strip())
        if resolution.strip() and not is_omni_video and not is_multi_image2video:
            payload["resolution"] = resolution.strip()
        if sound.strip() and not is_multi_image2video:
            payload["sound"] = sound.strip()
        if callback_url.strip():
            payload["callback_url"] = callback_url.strip()
        if external_task_id.strip():
            payload["external_task_id"] = external_task_id.strip()
        if video_url.strip():
            payload["video_url"] = video_url.strip()
        if character_orientation.strip():
            payload["character_orientation"] = character_orientation.strip()
        if static_mask.strip():
            payload["static_mask"] = self._image_to_base64(static_mask.strip())
        if dynamic_masks not in (None, "", []):
            payload["dynamic_masks"] = self._parse_json_array(dynamic_masks)
        if is_omni_video:
            encoded_image_list = self._encode_omni_video_image_list(image_list)
        elif is_multi_image2video:
            encoded_image_list = self._encode_multi_image_video_image_list(image_list)
        else:
            encoded_image_list = self._encode_media_list(image_list)
        if encoded_image_list:
            payload["image_list"] = encoded_image_list
        encoded_video_list = self._encode_video_list(video_list)
        if encoded_video_list:
            payload["video_list"] = encoded_video_list
            if self._has_base_video_reference(encoded_video_list):
                payload.pop("duration", None)
                payload.pop("aspect_ratio", None)
            if payload.get("sound") and payload["sound"] != "off":
                payload["sound"] = "off"
        normalized_element_list = self._normalize_element_list(element_list)
        if normalized_element_list not in (None, "", []):
            payload["element_list"] = normalized_element_list
        has_multi_prompt = multi_prompt not in (None, "", [])
        if multi_shot.strip() and not is_multi_image2video:
            if is_omni_video:
                multi_shot_value = self._optional_bool(multi_shot)
                if multi_shot_value:
                    normalized_shot_type = shot_type.strip() or "customize"
                    if normalized_shot_type == "customize" and not has_multi_prompt:
                        LOGGER.warning(
                            "kling omni multi_shot=true with customize shot_type requires multi_prompt; falling back to single-shot payload"
                        )
                    else:
                        payload["multi_shot"] = True
            else:
                payload["multi_shot"] = multi_shot.strip()
        if (
            shot_type.strip()
            and shot_type.strip().lower() not in {"auto", "智能", "default", "adaptive"}
            and (not is_omni_video or payload.get("multi_shot") is True)
        ):
            payload["shot_type"] = shot_type.strip()
        if has_multi_prompt and (not is_omni_video or payload.get("multi_shot") is True):
            payload["multi_prompt"] = multi_prompt
        if cfg_scale is not None:
            payload["cfg_scale"] = cfg_scale
        if keep_original_sound.strip():
            payload["keep_original_sound"] = keep_original_sound.strip()
        if seed is not None:
            payload["seed"] = seed
        return payload

    def _build_motion_control_payload(
        self,
        *,
        prompt: str,
        model: str,
        image: str,
        video_url: str,
        callback_url: str,
        external_task_id: str,
        character_orientation: str,
        element_list: Any,
        mode: str,
        keep_original_sound: str,
    ) -> dict[str, Any]:
        image_value = image.strip()
        if not image_value:
            raise KlingVideoError("image_url is required for Kling motion control")
        video_value = video_url.strip()
        if not video_value:
            raise KlingVideoError("video_url is required for Kling motion control")
        if not self._is_public_http_url(video_value):
            raise KlingVideoError(
                "Kling motion control video_url must be a public HTTP(S) URL; configure public asset storage or use an external MP4/MOV URL"
            )

        if self._is_public_http_url(image_value):
            encoded_image = image_value
        else:
            encoded_image = self._image_to_base64(image_value)

        orientation = character_orientation.strip() or "video"
        if orientation not in {"image", "video"}:
            raise KlingVideoError("character_orientation must be image or video for Kling motion control")

        normalized_elements = self._normalize_element_list(element_list)
        element_rows: list[dict[str, Any]] = []
        if normalized_elements not in (None, "", []):
            rows = normalized_elements if isinstance(normalized_elements, list) else [normalized_elements]
            for row in rows:
                if not isinstance(row, dict) or row.get("element_id") in (None, ""):
                    raise KlingVideoError("Kling motion control element_list only supports existing element_id references")
                element_rows.append({"element_id": row["element_id"]})
            if len(element_rows) > 1:
                raise KlingVideoError("Kling motion control supports at most one subject element")
            orientation = "video"

        payload: dict[str, Any] = {
            "model_name": model,
            "image_url": encoded_image,
            "video_url": video_value,
            "character_orientation": orientation,
            "mode": mode.strip() or "std",
        }
        if prompt.strip():
            payload["prompt"] = prompt.strip()
        sound_value = keep_original_sound.strip() or "yes"
        payload["keep_original_sound"] = sound_value if sound_value in {"yes", "no"} else "yes"
        if element_rows:
            payload["element_list"] = element_rows
        if callback_url.strip():
            payload["callback_url"] = callback_url.strip()
        if external_task_id.strip():
            payload["external_task_id"] = external_task_id.strip()
        return payload

    @staticmethod
    def _is_public_http_url(value: str) -> bool:
        parsed = urlparse(value.strip())
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return False
        host = parsed.hostname.lower()
        if host in {"localhost", "backend"} or host.endswith(".local"):
            return False
        try:
            ip = ipaddress.ip_address(host)
            return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast)
        except ValueError:
            return True

    @staticmethod
    def _is_omni_video_model(model: str) -> bool:
        normalized = str(model or "").strip().lower()
        return normalized in {"kling-video-o1", "kling-v3-omni"} or "omni" in normalized

    @staticmethod
    def _normalize_api_task(value: Any) -> str:
        return str(value or "").strip().lower().replace("-", "_")

    @staticmethod
    def _optional_bool(value: Any) -> bool | None:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return bool(value)
        text = str(value or "").strip().lower()
        if text in {"true", "1", "yes", "y", "on"}:
            return True
        if text in {"false", "0", "no", "n", "off"}:
            return False
        return None

    @staticmethod
    def _has_base_video_reference(value: list[Any]) -> bool:
        return any(
            isinstance(item, dict)
            and str(item.get("refer_type") or item.get("referType") or "base").strip().lower() == "base"
            for item in value
        )

    def _parse_json_array(self, value: Any) -> Any:
        if value in (None, "", []):
            return value
        if isinstance(value, str):
            text = value.strip()
            if not text:
                return value
            if text.startswith("[") or text.startswith("{"):
                try:
                    return json.loads(text)
                except json.JSONDecodeError:
                    return value
        return value

    def _encode_video_list(self, value: Any) -> list[Any]:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return []
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[Any] = []
        for item in items:
            if isinstance(item, dict):
                video_url = ""
                for key in ("video_url", "video", "url"):
                    raw = item.get(key)
                    if isinstance(raw, str) and raw.strip():
                        video_url = raw.strip()
                        break
                if not video_url:
                    continue
                row: dict[str, str] = {"video_url": video_url}
                refer_type = str(item.get("refer_type") or item.get("referType") or "base").strip()
                keep_original_sound = str(
                    item.get("keep_original_sound") or item.get("keepOriginalSound") or "no"
                ).strip()
                row["refer_type"] = refer_type if refer_type in {"base", "feature"} else "base"
                row["keep_original_sound"] = keep_original_sound if keep_original_sound in {"yes", "no"} else "no"
                encoded.append(row)
                continue
            text = str(item).strip()
            if text:
                encoded.append(text)
        return encoded

    def _normalize_element_list(self, value: Any) -> Any:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return parsed
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[Any] = []
        for item in items:
            if not isinstance(item, dict):
                encoded.append(item)
                continue
            row = dict(item)
            if row.get("element_id") is not None:
                row["element_id"] = self._normalize_element_id(row.get("element_id"))
                encoded.append(row)
                continue
            frontal = row.get("frontal_image")
            if isinstance(frontal, str) and frontal.strip():
                row["frontal_image"] = self._image_to_base64(frontal.strip())
            refer_images = row.get("refer_images")
            if isinstance(refer_images, list):
                row["refer_images"] = [
                    self._image_to_base64(str(image).strip())
                    for image in refer_images
                    if isinstance(image, str) and str(image).strip()
                ]
            encoded.append(row)
        return encoded

    @staticmethod
    def _normalize_element_id(value: Any) -> Any:
        if isinstance(value, int):
            return value
        text = str(value or "").strip()
        if text.isdigit():
            try:
                return int(text)
            except ValueError:
                return text
        return value

    def _encode_media_list(self, value: Any) -> list[Any]:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return []
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[Any] = []
        for item in items:
            if isinstance(item, dict):
                row = dict(item)
                for key in ("image", "url", "image_url"):
                    raw = row.get(key)
                    if isinstance(raw, str) and raw.strip():
                        row[key] = self._image_payload_value(raw.strip())
                encoded.append(row)
                continue
            text = str(item).strip()
            if not text:
                continue
            encoded.append(self._image_payload_value(text))
        return encoded

    def _encode_omni_video_image_list(self, value: Any) -> list[dict[str, str]]:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return []
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[dict[str, str]] = []
        for item in items:
            raw = ""
            frame_type = ""
            if isinstance(item, dict):
                for key in ("image_url", "imageUrl", "image", "url"):
                    candidate = item.get(key)
                    if isinstance(candidate, str) and candidate.strip():
                        raw = candidate.strip()
                        break
                frame_type = str(item.get("type") or "").strip()
            else:
                raw = str(item).strip()
            if not raw:
                continue
            row = {"image_url": self._image_to_base64(raw)}
            if frame_type in {"first_frame", "end_frame"}:
                row["type"] = frame_type
            encoded.append(row)
        return encoded

    def _encode_multi_image_video_image_list(self, value: Any) -> list[dict[str, str]]:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return []
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[dict[str, str]] = []
        for item in items[:4]:
            raw = ""
            if isinstance(item, dict):
                for key in ("image", "url", "image_url", "imageUrl"):
                    candidate = item.get(key)
                    if isinstance(candidate, str) and candidate.strip():
                        raw = candidate.strip()
                        break
            else:
                raw = str(item).strip()
            if not raw:
                continue
            encoded.append({"image": self._image_to_base64(raw)})
        return encoded

    def _encode_omni_image_list(self, value: Any) -> list[dict[str, str]]:
        parsed = self._parse_json_array(value)
        if parsed in (None, "", []):
            return []
        items = parsed if isinstance(parsed, list) else [parsed]
        encoded: list[dict[str, str]] = []
        for item in items:
            raw = ""
            if isinstance(item, dict):
                for key in ("image", "url", "image_url", "imageUrl"):
                    candidate = item.get(key)
                    if isinstance(candidate, str) and candidate.strip():
                        raw = candidate.strip()
                        break
            else:
                raw = str(item).strip()
            if not raw:
                continue
            encoded.append({"image": self._image_to_base64(raw)})
        return encoded

    def _image_payload_value(self, value: str) -> str:
        raw = value.strip()
        if not raw:
            return ""
        if raw.startswith(("http://", "https://")) and self._local_media_path(raw) is None:
            return raw
        return self._image_to_base64(raw)

    def _image_to_base64(self, value: str) -> str:
        raw = value.strip()
        if not raw:
            return ""
        if raw.startswith("data:"):
            header, separator, encoded = raw.partition(",")
            if separator and ";base64" in header:
                return self._validate_base64(encoded)
            raise KlingVideoError("kling image data url is not base64 encoded")
        if self._is_base64(raw):
            return raw
        local_path = self._local_media_path(raw)
        if local_path is not None:
            return self._file_to_base64(local_path)
        if raw.startswith(("http://", "https://")) or raw.startswith("/"):
            return self._download_to_base64(raw)
        possible_path = Path(raw)
        if possible_path.exists() and possible_path.is_file():
            return self._file_to_base64(possible_path)
        raise KlingVideoError("kling image must be base64, data url, URL, or readable local file")

    def _local_media_path(self, value: str) -> Path | None:
        parsed_path = value
        if value.startswith(("http://", "https://")):
            parsed_path = urlparse(value).path
        configured_base = settings.generated_media_public_base_url.rstrip("/") or "/generated"
        public_base = urlparse(configured_base).path.rstrip("/") if configured_base.startswith(("http://", "https://")) else configured_base
        public_base = public_base or "/generated"
        if not parsed_path.startswith(public_base + "/"):
            return None
        relative = parsed_path.removeprefix(public_base + "/")
        candidate = Path(settings.generated_media_dir).resolve().joinpath(relative).resolve()
        media_root = Path(settings.generated_media_dir).resolve()
        if candidate.is_file() and candidate.is_relative_to(media_root):
            return candidate
        return None

    def _file_to_base64(self, path: Path) -> str:
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise KlingVideoError(f"could not read kling input image: {path}") from exc
        return self._bytes_to_base64(data)

    def _download_to_base64(self, value: str) -> str:
        url = value
        if value.startswith("/"):
            url = f"{settings.backend_internal_base_url.rstrip('/')}{value}"
        try:
            with self.session.get(url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                chunks: list[bytes] = []
                total = 0
                for chunk in response.iter_content(chunk_size=1024 * 256):
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > self.max_input_image_bytes:
                        raise KlingVideoError("kling input image exceeds 20MB")
                    chunks.append(chunk)
        except KlingVideoError:
            raise
        except requests.RequestException as exc:
            raise KlingVideoError(f"could not download kling input image: {value}") from exc
        return self._bytes_to_base64(b"".join(chunks))

    def _bytes_to_base64(self, data: bytes) -> str:
        if not data:
            raise KlingVideoError("kling input image is empty")
        if len(data) > self.max_input_image_bytes:
            raise KlingVideoError("kling input image exceeds 20MB")
        return base64.b64encode(data).decode("ascii")

    @staticmethod
    def _is_base64(value: str) -> bool:
        compact = "".join(value.split())
        if len(compact) < 16 or compact.startswith(("http://", "https://", "/")):
            return False
        try:
            base64.b64decode(compact, validate=True)
            return True
        except Exception:
            return False

    @staticmethod
    def _validate_base64(value: str) -> str:
        compact = "".join(value.split())
        try:
            base64.b64decode(compact, validate=True)
        except Exception as exc:
            raise KlingVideoError("kling image is not valid base64") from exc
        return compact

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            try:
                response = self.session.request(
                    method,
                    f"{self.base_url}{path}",
                    data=body.encode("utf-8") if body else None,
                    headers=self._headers(),
                    timeout=self.timeout,
                )
                break
            except requests.Timeout as exc:
                raise KlingVideoTimeoutError(
                    "kling request timed out",
                    **transport_failure_metadata(exc),
                ) from exc
            except requests.ConnectionError as exc:
                if not request_was_not_sent(exc) or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise KlingVideoError(
                        f"kling request failed: {exc}",
                        **transport_failure_metadata(exc),
                    ) from exc
                delay = self._RETRY_BASE_DELAY_SECONDS * attempt
                LOGGER.warning(
                    "kling transient connection failure, retrying attempt=%s/%s delay=%.1fs: %s",
                    attempt,
                    self._MAX_TRANSPORT_ATTEMPTS,
                    delay,
                    exc,
                )
                time.sleep(delay)
            except requests.RequestException as exc:
                raise KlingVideoError(
                    f"kling request failed: {exc}",
                    **transport_failure_metadata(exc),
                ) from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise KlingVideoError(
                f"kling request failed: status={response.status_code}, body={response.text}",
                **rejected_response_metadata(response),
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise KlingVideoError("kling returned non-json response") from exc
        if not isinstance(data, dict):
            raise KlingVideoError("kling returned invalid response")
        return data

    def _headers(self) -> dict[str, str]:
        token = self._bearer_token()
        return {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {token}",
        }

    def _has_auth(self) -> bool:
        if self._has_ak_sk_auth():
            return True
        api_key = (self.api_key or "").strip()
        return bool(api_key and not api_key.startswith("replace-with-") and self._looks_like_jwt(api_key))

    def _bearer_token(self) -> str:
        if self._has_ak_sk_auth():
            return self._jwt_token()
        api_key = (self.api_key or "").strip()
        if api_key and not api_key.startswith("replace-with-"):
            if self._looks_like_jwt(api_key):
                return api_key
            raise KlingVideoError(
                "Kling auth is misconfigured: API Key must be a JWT token, or configure "
                'extraAuthJson as {"accessKey":"...","secretKey":"..."} (official AK/SK).'
            )
        raise KlingVideoError(
            'Kling credentials are not configured. Set extraAuthJson to '
            '{"accessKey":"...","secretKey":"..."} on the model config.'
        )

    @staticmethod
    def _looks_like_jwt(value: str) -> bool:
        parts = value.split(".")
        return len(parts) == 3 and all(part.strip() for part in parts)

    def _has_ak_sk_auth(self) -> bool:
        access_key = (self.access_key or "").strip()
        secret_key = (self.secret_key or "").strip()
        return bool(
            access_key
            and secret_key
            and not access_key.startswith("replace-with-")
            and not secret_key.startswith("replace-with-")
        )

    def _jwt_token(self) -> str:
        now = int(time.time())
        header = {"alg": "HS256", "typ": "JWT"}
        payload = {
            "iss": self.access_key.strip(),
            "exp": now + 1800,
            "nbf": now - 5,
        }
        signing_input = ".".join([
            self._base64url_json(header),
            self._base64url_json(payload),
        ])
        digest = hmac.new(
            self.secret_key.strip().encode("utf-8"),
            signing_input.encode("utf-8"),
            hashlib.sha256,
        ).digest()
        signature = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
        return f"{signing_input}.{signature}"

    @staticmethod
    def _base64url_json(payload: dict[str, Any]) -> str:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    @classmethod
    def _extract_task_id(cls, payload: dict[str, Any]) -> str:
        for key in ("task_id", "taskId", "id", "request_id", "requestId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_task_id(data)
        raise KlingVideoError("kling create response missing task id")

    @classmethod
    def _extract_status(cls, payload: dict[str, Any]) -> str:
        for key in ("status", "state", "task_status", "taskStatus"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_status(data)
        for key in ("message", "reason", "status_msg", "statusMsg", "task_status_msg", "taskStatusMsg"):
            value = payload.get(key)
            if not isinstance(value, str) or not value.strip():
                continue
            normalized = value.strip().lower()
            if normalized in SUCCESS_STATUSES or normalized in FAILED_STATUSES:
                return value.strip()
        return "processing"

    @classmethod
    def _has_success_indicator(cls, payload: dict[str, Any]) -> bool:
        for key in ("message", "reason", "status_msg", "statusMsg", "task_status_msg", "taskStatusMsg"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip().lower() in SUCCESS_STATUSES:
                return True
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._has_success_indicator(data)
        return False

    @classmethod
    def _extract_video_url(cls, payload: dict[str, Any]) -> str:
        url = cls._extract_video_url_or_empty(payload)
        if url:
            return url
        raise KlingVideoError(cls._describe_response_problem("kling response missing video url", payload))

    @classmethod
    def _extract_video_url_or_empty(cls, payload: dict[str, Any]) -> str:
        for value in cls._collect_video_urls(payload):
            return value
        return ""

    @classmethod
    def _collect_video_urls(cls, value: Any, parent_key: str = "") -> list[str]:
        urls: list[str] = []
        if isinstance(value, dict):
            for key, nested in value.items():
                urls.extend(cls._collect_video_urls(nested, str(key)))
            return urls
        if isinstance(value, list):
            for item in value:
                urls.extend(cls._collect_video_urls(item, parent_key))
            return urls
        if not isinstance(value, str):
            return urls
        candidate = value.strip()
        if cls._looks_like_video_url(candidate):
            return [candidate]
        if cls._looks_like_video_url_field(parent_key, candidate):
            return [candidate]
        return urls

    @classmethod
    def _extract_image_urls(cls, payload: dict[str, Any]) -> list[str]:
        urls = cls._extract_image_urls_or_empty(payload)
        if urls:
            return urls
        message = cls._describe_response_problem("kling image response missing image url", payload)
        LOGGER.warning(message)
        raise KlingVideoError(message)

    @classmethod
    def _extract_image_urls_or_empty(cls, payload: dict[str, Any]) -> list[str]:
        urls = cls._collect_image_urls(payload)
        if urls:
            return list(dict.fromkeys(urls))
        return []

    @classmethod
    def _collect_image_urls(cls, value: Any, parent_key: str = "") -> list[str]:
        urls: list[str] = []
        if isinstance(value, dict):
            for key, nested in value.items():
                urls.extend(cls._collect_image_urls(nested, str(key)))
            return urls
        if isinstance(value, list):
            for item in value:
                urls.extend(cls._collect_image_urls(item, parent_key))
            return urls
        if not isinstance(value, str):
            return urls
        candidate = value.strip()
        if cls._looks_like_image_url(candidate):
            return [candidate]
        if cls._looks_like_image_url_field(parent_key, candidate):
            return [candidate]
        return urls

    @classmethod
    def _describe_response_problem(cls, prefix: str, payload: dict[str, Any]) -> str:
        diagnostics = cls._response_diagnostics(payload)
        diagnostic_text = "; ".join(diagnostics) if diagnostics else "none"
        payload_text = _json_for_log(payload)
        if len(payload_text) > 1600:
            payload_text = payload_text[:1600] + "...<truncated>"
        return f"{prefix}; diagnostics={diagnostic_text}; payload={payload_text}"

    @classmethod
    def _response_diagnostics(cls, value: Any) -> list[str]:
        fields: list[str] = []
        cls._collect_response_diagnostics(value, "", fields)
        return fields

    @classmethod
    def _collect_response_diagnostics(cls, value: Any, path: str, fields: list[str]) -> None:
        if len(fields) >= 16:
            return
        if isinstance(value, dict):
            for key, nested in value.items():
                nested_path = f"{path}.{key}" if path else str(key)
                normalized = str(key).replace("-", "_").lower()
                compact = normalized.replace("_", "")
                if normalized in {
                    "code",
                    "error_code",
                    "message",
                    "msg",
                    "reason",
                    "status",
                    "state",
                    "status_msg",
                    "task_status",
                    "task_status_msg",
                    "fail_reason",
                } or compact in {
                    "errorcode",
                    "statusmsg",
                    "taskstatus",
                    "taskstatusmsg",
                    "failreason",
                }:
                    fields.append(f"{nested_path}={cls._diagnostic_value(nested)}")
                    if len(fields) >= 16:
                        return
                cls._collect_response_diagnostics(nested, nested_path, fields)
            return
        if isinstance(value, list):
            for index, nested in enumerate(value[:8]):
                cls._collect_response_diagnostics(nested, f"{path}[{index}]", fields)
                if len(fields) >= 16:
                    return

    @staticmethod
    def _diagnostic_value(value: Any) -> str:
        sanitized = _sanitize_for_log(value)
        if isinstance(sanitized, str):
            text = sanitized
        else:
            try:
                text = json.dumps(sanitized, ensure_ascii=False, separators=(",", ":"))
            except Exception:
                text = str(sanitized)
        return text[:240] + ("...<truncated>" if len(text) > 240 else "")

    @classmethod
    def _walk(cls, value: Any) -> list[Any]:
        values = [value]
        if isinstance(value, dict):
            for nested in value.values():
                values.extend(cls._walk(nested))
        elif isinstance(value, list):
            for item in value:
                values.extend(cls._walk(item))
        return values

    @staticmethod
    def _looks_like_video_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".mp4", ".mov", ".webm"))

    @staticmethod
    def _looks_like_video_url_field(key: str, value: str) -> bool:
        lowered_key = key.lower()
        lowered_value = value.lower().split("?", 1)[0]
        if not lowered_value.startswith(("http://", "https://")):
            return False
        if lowered_value.endswith((".jpg", ".jpeg", ".png", ".webp", ".gif")):
            return False
        return lowered_key in {
            "url",
            "video",
            "video_url",
            "videourl",
            "origin_video_url",
            "originvideourl",
            "generated_video_url",
            "generatedvideourl",
            "result_url",
            "resulturl",
            "download_url",
            "downloadurl",
            "resource_url",
            "resourceurl",
            "signed_url",
            "signedurl",
        }
        return (
            ("video" in lowered_key or "url" in lowered_key)
            and any(token in lowered_key for token in ("url", "download", "resource", "signed"))
        )

    @staticmethod
    def _looks_like_image_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".jpg", ".jpeg", ".png", ".webp", ".gif"))

    @staticmethod
    def _looks_like_image_url_field(key: str, value: str) -> bool:
        lowered_key = key.lower()
        lowered_value = value.lower().split("?", 1)[0]
        if not lowered_value.startswith(("http://", "https://")):
            return False
        if lowered_value.endswith((".mp4", ".mov", ".webm", ".m3u8")):
            return False
        return lowered_key in {
            "url",
            "image",
            "image_url",
            "imageurl",
            "origin_image_url",
            "originimageurl",
            "generated_image_url",
            "generatedimageurl",
            "result_url",
            "resulturl",
            "urls",
            "image_urls",
            "imageurls",
            "origin_image_urls",
            "originimageurls",
            "generated_image_urls",
            "generatedimageurls",
            "result_urls",
            "resulturls",
            "download_url",
            "downloadurl",
            "download_urls",
            "downloadurls",
            "resource_url",
            "resourceurl",
            "resource_urls",
            "resourceurls",
            "signed_url",
            "signedurl",
            "signed_urls",
            "signedurls",
        }
        return (
            ("image" in lowered_key or "url" in lowered_key)
            and any(token in lowered_key for token in ("url", "download", "resource", "signed"))
        )

    @staticmethod
    def _duration_seconds(duration: str) -> int | None:
        digits = "".join(char for char in str(duration) if char.isdigit())
        if not digits:
            return None
        return max(int(digits), 1)

    @staticmethod
    def _aspect_ratio(aspect_ratio: str, image_size: str) -> str:
        raw = str(aspect_ratio or "").strip().replace("：", ":").lower()
        size = str(image_size or "").strip().lower()
        if raw in {"", "auto", "智能", "adaptive", "default"} or size == "auto":
            return ""
        for ratio in ("21:9", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "1:1"):
            if ratio in raw:
                return ratio
        if size in {"2560x1080", "1920x810"}:
            return "21:9"
        if size in {"1280x720", "1920x1080"}:
            return "16:9"
        if size in {"480x854", "720x1280", "1080x1920"}:
            return "9:16"
        if size in {"1024x768", "1280x960"}:
            return "4:3"
        if size in {"768x1024", "960x1280"}:
            return "3:4"
        if size in {"1152x768", "1536x1024"}:
            return "3:2"
        if size in {"768x1152", "1024x1536"}:
            return "2:3"
        if size in {"480x480", "960x960", "1024x1024"}:
            return "1:1"
        return "16:9"

    def create_element(
        self,
        *,
        element_name: str,
        element_description: str,
        reference_type: str,
        element_image_list: dict[str, Any] | None = None,
        element_video_list: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        from utils.kling_config import KLING_ELEMENT_PATHS

        payload: dict[str, Any] = {
            "element_name": element_name.strip(),
            "element_description": element_description.strip(),
            "reference_type": reference_type.strip(),
        }
        if element_image_list:
            payload["element_image_list"] = element_image_list
        if element_video_list:
            payload["element_video_list"] = element_video_list
        response = self._request("POST", KLING_ELEMENT_PATHS["create"], payload)
        return response

    def get_element_task(self, task_id: str) -> dict[str, Any]:
        from utils.kling_config import KLING_ELEMENT_PATHS

        path = KLING_ELEMENT_PATHS["get"].format(task_id=task_id)
        return self._request("GET", path, None)

    def wait_for_element(self, task_id: str) -> str:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last_payload = self.get_element_task(task_id)
            element_id = self._extract_element_id(last_payload)
            if element_id:
                return element_id
            status = self._extract_status(last_payload).lower()
            if status in SUCCESS_STATUSES:
                raise KlingVideoError(
                    self._describe_response_problem(
                        "kling element response reached terminal success but no element id",
                        last_payload,
                    )
                )
            if status in FAILED_STATUSES:
                raise KlingVideoError(
                    self._describe_response_problem("kling element creation failed", last_payload)
                )
            time.sleep(self.poll_interval_seconds)
        raise KlingVideoTimeoutError(
            self._describe_response_problem(
                f"kling element creation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}",
                last_payload,
            )
        )

    def list_custom_elements(self, *, page_num: int = 1, page_size: int = 30) -> dict[str, Any]:
        from utils.kling_config import KLING_ELEMENT_PATHS

        page_num = max(1, min(1000, int(page_num)))
        page_size = max(1, min(500, int(page_size)))
        path = f"{KLING_ELEMENT_PATHS['list']}?pageNum={page_num}&pageSize={page_size}"
        return self._request("GET", path, None)

    def list_preset_elements(self, *, page_num: int = 1, page_size: int = 30) -> dict[str, Any]:
        from utils.kling_config import KLING_ELEMENT_PATHS

        page_num = max(1, min(1000, int(page_num)))
        page_size = max(1, min(500, int(page_size)))
        path = f"{KLING_ELEMENT_PATHS['preset_list']}?pageNum={page_num}&pageSize={page_size}"
        return self._request("GET", path, None)

    def delete_element(self, element_id: str) -> dict[str, Any]:
        from utils.kling_config import KLING_ELEMENT_PATHS

        element_id = str(element_id or "").strip()
        if not element_id:
            raise KlingVideoError("element_id is required")
        return self._request("POST", KLING_ELEMENT_PATHS["delete"], {"element_id": element_id})

    @classmethod
    def _extract_element_id(cls, payload: dict[str, Any]) -> str:
        for key in ("element_id", "elementId"):
            value = payload.get(key)
            if isinstance(value, (str, int)) and str(value).strip():
                return str(value).strip()
        task_result = payload.get("task_result")
        if isinstance(task_result, dict):
            elements = task_result.get("elements")
            if isinstance(elements, list):
                for item in elements:
                    if isinstance(item, dict):
                        element_id = cls._extract_element_id(item)
                        if element_id:
                            return element_id
            nested = cls._extract_element_id(task_result)
            if nested:
                return nested
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_element_id(data)
        return ""


def _json_for_log(value: Any) -> str:
    try:
        return json.dumps(_sanitize_for_log(value), ensure_ascii=False, separators=(",", ":"))[:4000]
    except Exception:
        return "<unserializable>"


def _sanitize_for_log(value: Any) -> Any:
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, nested in value.items():
            key_text = str(key)
            lowered = key_text.lower()
            if any(secret in lowered for secret in ("key", "secret", "token", "authorization")):
                sanitized[key_text] = "***"
                continue
            if key_text in {"image", "image_tail"} and isinstance(nested, str) and len(nested) > 120:
                sanitized[key_text] = f"<image-bytes:{len(nested)} chars>"
                continue
            sanitized[key_text] = _sanitize_for_log(nested)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_for_log(item) for item in value]
    if isinstance(value, str) and len(value) > 800:
        return value[:800] + f"...<{len(value)} chars>"
    return value
