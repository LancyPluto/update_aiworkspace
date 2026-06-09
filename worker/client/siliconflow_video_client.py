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
    def __init__(self, *, base_url: str | None = None, api_key: str | None = None) -> None:
        self.base_url = (base_url or settings.siliconflow_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.siliconflow_api_key
        self.poll_interval_seconds = 5
        self.timeout_seconds = 600
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
        return self.generate_images(prompt=prompt, model=model, image_size=image_size, batch_size=1)[0]

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
    ) -> list[str]:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise SiliconFlowVideoError("SILICONFLOW_API_KEY is not configured")

        payload: dict[str, Any] = {
            "model": model or settings.siliconflow_image_model,
            "prompt": prompt,
        }
        if str(image_size or "").strip().lower() != "auto":
            payload["image_size"] = image_size
        if batch_size > 1:
            payload["batch_size"] = batch_size
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if seed is not None:
            payload["seed"] = seed
        if guidance_scale is not None:
            payload["guidance_scale"] = guidance_scale
        if num_inference_steps is not None:
            payload["num_inference_steps"] = num_inference_steps
        response = self._post("/v1/images/generations", payload)
        return self._extract_image_urls(response)

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
        }
        if str(image_size or "").strip().lower() != "auto":
            payload["image_size"] = image_size
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
        urls = SiliconFlowVideoClient._extract_image_urls(payload)
        if not urls:
            raise SiliconFlowVideoError("siliconflow image response missing image url")
        return urls[0]

    @staticmethod
    def _extract_image_urls(payload: dict[str, Any]) -> list[str]:
        containers = []
        for key in ("images", "data"):
            value = payload.get(key)
            if isinstance(value, list):
                containers.append(value)
        urls: list[str] = []
        for container in containers:
            if not container:
                continue
            for item in container:
                if not isinstance(item, dict):
                    continue
                url = item.get("url") or item.get("image_url")
                if isinstance(url, str) and url.strip():
                    urls.append(url.strip())
        if urls:
            return urls
        raise SiliconFlowVideoError("siliconflow image response missing image url")
