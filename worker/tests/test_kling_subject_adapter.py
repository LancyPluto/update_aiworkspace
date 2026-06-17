from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from client.kling_video_client import KlingVideoClient
from subject.kling_subject_adapter import KlingSubjectAdapter


class KlingSubjectAdapterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.adapter = KlingSubjectAdapter()
        self.client = MagicMock(spec=KlingVideoClient)
        self.client._image_to_base64.side_effect = lambda value: f"encoded:{value}"

    def test_build_image_payload(self) -> None:
        payload = self.adapter.build_create_payload(
            {
                "displayName": "Hero",
                "description": "Main character",
                "referenceType": "image_refer",
                "referenceJson": {
                    "frontalImage": "https://example.com/front.jpg",
                    "referImages": ["https://example.com/side.jpg"],
                },
            },
            self.client,
        )
        self.assertEqual(payload["element_name"], "Hero")
        self.assertEqual(payload["reference_type"], "image_refer")
        self.assertEqual(payload["element_image_list"]["frontal_image"], "https://example.com/front.jpg")
        self.assertEqual(
            payload["element_image_list"]["refer_images"],
            [{"image_url": "https://example.com/side.jpg"}],
        )

    def test_build_video_payload(self) -> None:
        payload = self.adapter.build_create_payload(
            {
                "displayName": "Actor",
                "description": "Actor",
                "referenceType": "video_refer",
                "referenceJson": {"referVideos": ["https://example.com/clip.mp4"]},
            },
            self.client,
        )
        self.assertEqual(payload["reference_type"], "video_refer")
        self.assertEqual(
            payload["element_video_list"]["refer_videos"],
            [{"video_url": "https://example.com/clip.mp4"}],
        )

    def test_build_image_payload_requires_refer_images(self) -> None:
        with self.assertRaises(ValueError):
            self.adapter.build_create_payload(
                {
                    "displayName": "Hero",
                    "description": "Main character",
                    "referenceType": "image_refer",
                    "referenceJson": {"frontalImage": "https://example.com/front.jpg"},
                },
                self.client,
            )

    def test_extract_element_id(self) -> None:
        element_id = self.adapter.extract_element_id(
            {"task_result": {"elements": [{"element_id": "elem_123"}]}}
        )
        self.assertEqual(element_id, "elem_123")


class KlingElementListEncodingTest(unittest.TestCase):
    def test_normalize_element_list_encodes_image_fields(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._image_to_base64 = MagicMock(return_value="base64-image")  # type: ignore[method-assign]
        normalized = client._normalize_element_list(
            [{"frontal_image": "https://example.com/front.jpg", "refer_images": ["https://example.com/side.jpg"]}]
        )
        self.assertEqual(normalized[0]["frontal_image"], "base64-image")
        self.assertEqual(normalized[0]["refer_images"], ["base64-image"])
        self.assertEqual(client._image_to_base64.call_count, 2)

    def test_normalize_element_list_keeps_element_id(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        normalized = client._normalize_element_list([{"element_id": "123456"}])
        self.assertEqual(normalized, [{"element_id": "123456"}])


if __name__ == "__main__":
    unittest.main()
