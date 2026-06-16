from __future__ import annotations

from typing import Any, Protocol

from client.kling_video_client import KlingVideoClient


class SubjectAdapterProtocol(Protocol):
    provider_code: str

    def build_create_payload(self, context: dict[str, Any], client: KlingVideoClient) -> dict[str, Any]: ...

    def extract_element_id(self, task_result: dict[str, Any]) -> str: ...


class KlingSubjectAdapter:
    provider_code = "kling_video"

    def build_create_payload(self, context: dict[str, Any], client: KlingVideoClient) -> dict[str, Any]:
        reference_type = str(context.get("referenceType") or "").strip()
        reference_json = context.get("referenceJson") or {}
        if not isinstance(reference_json, dict):
            reference_json = {}
        payload: dict[str, Any] = {
            "element_name": str(context.get("displayName") or "").strip()[:20],
            "element_description": str(context.get("description") or context.get("displayName") or "").strip()[:100],
            "reference_type": reference_type,
        }
        if reference_type == "image_refer":
            image_list = self._build_element_image_list(reference_json, client)
            payload["element_image_list"] = image_list
            return payload
        payload["element_video_list"] = self._build_element_video_list(reference_json)
        return payload

    @staticmethod
    def _build_element_image_list(reference_json: dict[str, Any], client: KlingVideoClient) -> dict[str, Any]:
        frontal = str(reference_json.get("frontalImage") or "").strip()
        refer_images = reference_json.get("referImages")
        refer_urls: list[str] = []
        if isinstance(refer_images, list):
            refer_urls = [str(item).strip() for item in refer_images if str(item).strip()]
        if not frontal:
            raise ValueError("image subject requires frontalImage")
        if not refer_urls:
            raise ValueError("image subject requires at least one referImages entry")
        return {
            "frontal_image": KlingSubjectAdapter._encode_image_value(client, frontal),
            "refer_images": [
                {"image_url": KlingSubjectAdapter._encode_image_value(client, url)}
                for url in refer_urls[:3]
            ],
        }

    @staticmethod
    def _build_element_video_list(reference_json: dict[str, Any]) -> dict[str, Any]:
        refer_videos = reference_json.get("referVideos")
        videos: list[dict[str, str]] = []
        if isinstance(refer_videos, list):
            for item in refer_videos:
                url = str(item).strip()
                if url:
                    videos.append({"video_url": url})
        if not videos:
            raise ValueError("video subject requires at least one referVideos entry")
        return {"refer_videos": videos[:1]}

    @staticmethod
    def _encode_image_value(client: KlingVideoClient, value: str) -> str:
        text = value.strip()
        if text.startswith(("http://", "https://")):
            return text
        return client._image_to_base64(text)

    def extract_element_id(self, task_result: dict[str, Any]) -> str:
        return KlingVideoClient._extract_element_id(task_result)
