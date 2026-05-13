import base64
import time
from typing import Any

import requests

from config import settings


class SiliconFlowVideoError(RuntimeError):
    pass


class SiliconFlowVideoTimeoutError(SiliconFlowVideoError):
    pass


class SiliconFlowVideoClient:
    def __init__(self) -> None:
        self.base_url = settings.siliconflow_base_url.rstrip("/")
        self.api_key = settings.siliconflow_api_key
        self.default_model = settings.siliconflow_video_model
        self.poll_interval_seconds = settings.siliconflow_video_poll_interval_seconds
        self.timeout_seconds = settings.siliconflow_video_timeout_seconds
        self.timeout = (5, 60)
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            }
        )

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        seed: int | None = None,
    ) -> dict[str, Any]:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise SiliconFlowVideoError("SILICONFLOW_API_KEY is not configured")

        request_id = self.submit_video(
            prompt=prompt,
            image_size=image_size,
            negative_prompt=negative_prompt,
            model=model,
            image=image,
            seed=seed,
        )
        return self.wait_for_video(request_id)

    def generate_image(
        self,
        *,
        prompt: str,
        model: str | None = None,
        image_size: str = "1024x1024",
    ) -> str:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise SiliconFlowVideoError("SILICONFLOW_API_KEY is not configured")

        payload = {
            "model": model or settings.siliconflow_image_model,
            "prompt": prompt,
            "image_size": image_size,
        }
        response = self._post("/v1/images/generations", payload)
        return self._extract_image_url(response)

    def generate_speech_data_url(
        self,
        *,
        input_text: str,
        model: str | None = None,
        voice: str = "FunAudioLLM/CosyVoice2-0.5B:alex",
    ) -> str:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise SiliconFlowVideoError("SILICONFLOW_API_KEY is not configured")

        payload = {
            "model": model or settings.siliconflow_voice_model,
            "input": input_text,
            "voice": voice,
            "response_format": "mp3",
        }
        try:
            response = self.session.post(
                f"{self.base_url}/v1/audio/speech",
                json=payload,
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise SiliconFlowVideoTimeoutError("siliconflow speech request timed out") from exc
        except requests.RequestException as exc:
            raise SiliconFlowVideoError(f"siliconflow speech request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise SiliconFlowVideoError(
                f"siliconflow speech request failed: status={response.status_code}, body={response.text}"
            ) from exc

        encoded = base64.b64encode(response.content).decode("ascii")
        return f"data:audio/mpeg;base64,{encoded}"

    def submit_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        seed: int | None = None,
    ) -> str:
        payload: dict[str, Any] = {
            "model": model or self.default_model,
            "prompt": prompt,
            "image_size": image_size,
        }
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if image.strip():
            payload["image"] = image.strip()
        if seed is not None:
            payload["seed"] = seed

        response = self._post("/v1/video/submit", payload)
        request_id = response.get("requestId")
        if not isinstance(request_id, str) or not request_id.strip():
            raise SiliconFlowVideoError("siliconflow submit response missing requestId")
        return request_id.strip()

    def wait_for_video(self, request_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last_payload = self.get_video_status(request_id)
            status = str(last_payload.get("status") or "")
            if status == "Succeed":
                video_url = self._extract_video_url(last_payload)
                return {
                    "requestId": request_id,
                    "status": status,
                    "videoUrl": video_url,
                    "reason": str(last_payload.get("reason") or ""),
                    "seed": (last_payload.get("results") or {}).get("seed"),
                    "timings": (last_payload.get("results") or {}).get("timings") or {},
                }
            if status == "Failed":
                reason = str(last_payload.get("reason") or "video generation failed")
                raise SiliconFlowVideoError(reason)
            if status not in {"InQueue", "InProgress"}:
                raise SiliconFlowVideoError(f"unknown siliconflow video status: {status}")
            time.sleep(self.poll_interval_seconds)

        raise SiliconFlowVideoTimeoutError(
            f"video generation timed out, requestId={request_id}, lastStatus={last_payload.get('status')}"
        )

    def get_video_status(self, request_id: str) -> dict[str, Any]:
        return self._post("/v1/video/status", {"requestId": request_id})

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = self.session.post(
                f"{self.base_url}{path}",
                json=payload,
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise SiliconFlowVideoTimeoutError("siliconflow request timed out") from exc
        except requests.RequestException as exc:
            raise SiliconFlowVideoError(f"siliconflow request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise SiliconFlowVideoError(
                f"siliconflow request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise SiliconFlowVideoError("siliconflow returned non-json response") from exc
        if not isinstance(data, dict):
            raise SiliconFlowVideoError("siliconflow returned invalid response")
        return data

    @staticmethod
    def _extract_video_url(payload: dict[str, Any]) -> str:
        results = payload.get("results")
        if not isinstance(results, dict):
            raise SiliconFlowVideoError("siliconflow success response missing results")
        videos = results.get("videos")
        if not isinstance(videos, list) or not videos:
            raise SiliconFlowVideoError("siliconflow success response missing videos")
        first_video = videos[0]
        if not isinstance(first_video, dict):
            raise SiliconFlowVideoError("siliconflow success response contains invalid video")
        video_url = first_video.get("url")
        if not isinstance(video_url, str) or not video_url.strip():
            raise SiliconFlowVideoError("siliconflow success response missing video url")
        return video_url.strip()

    @staticmethod
    def _extract_image_url(payload: dict[str, Any]) -> str:
        containers = []
        for key in ("images", "data"):
            value = payload.get(key)
            if isinstance(value, list):
                containers.append(value)
        for container in containers:
            if not container:
                continue
            first = container[0]
            if isinstance(first, dict):
                url = first.get("url") or first.get("image_url")
                if isinstance(url, str) and url.strip():
                    return url.strip()
        raise SiliconFlowVideoError("siliconflow image response missing image url")
