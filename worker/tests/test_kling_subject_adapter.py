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
        self.assertEqual(normalized, [{"element_id": 123456}])

    def test_omni_video_payload_preserves_reference_objects_and_forces_sound_off(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        payload = client._build_video_payload(
            prompt="edit this video",
            image_size="",
            negative_prompt="",
            model="kling-v3-omni",
            image="",
            image_tail="",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="on",
            callback_url="",
            external_task_id="",
            video_list=[
                {
                    "video_url": "https://example.com/source.mp4",
                    "refer_type": "base",
                    "keep_original_sound": "yes",
                }
            ],
        )

        self.assertEqual(
            payload["video_list"],
            [
                {
                    "video_url": "https://example.com/source.mp4",
                    "refer_type": "base",
                    "keep_original_sound": "yes",
                }
            ],
        )
        self.assertNotIn("refer_type", payload)
        self.assertEqual(payload["sound"], "off")
        self.assertNotIn("duration", payload)
        self.assertNotIn("aspect_ratio", payload)
        self.assertNotIn("resolution", payload)

    def test_omni_video_payload_keeps_legacy_string_video_list(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        payload = client._build_video_payload(
            prompt="edit this video",
            image_size="",
            negative_prompt="",
            model="kling-v3-omni",
            image="",
            image_tail="",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="off",
            callback_url="",
            external_task_id="",
            video_list=["https://example.com/source.mp4"],
        )

        self.assertEqual(payload["video_list"], ["https://example.com/source.mp4"])

    def test_omni_video_payload_uses_documented_subject_and_multishot_shapes(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        payload = client._build_video_payload(
            prompt="make <<<element_1>>> wave",
            image_size="",
            negative_prompt="",
            model="kling-v3-omni",
            image="",
            image_tail="",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="off",
            callback_url="",
            external_task_id="",
            element_list=[{"element_id": "313650206737230"}],
            multi_shot="false",
            shot_type="customize",
        )

        self.assertEqual(payload["element_list"], [{"element_id": 313650206737230}])
        self.assertNotIn("multi_shot", payload)
        self.assertNotIn("shot_type", payload)
        self.assertNotIn("resolution", payload)

    def test_omni_video_custom_multishot_without_multi_prompt_falls_back_to_single_shot(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        payload = client._build_video_payload(
            prompt="single prompt",
            image_size="",
            negative_prompt="",
            model="kling-v3-omni",
            image="",
            image_tail="",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="",
            mode="std",
            sound="off",
            callback_url="",
            external_task_id="",
            multi_shot="true",
            shot_type="customize",
            multi_prompt=None,
        )

        self.assertNotIn("multi_shot", payload)
        self.assertNotIn("shot_type", payload)
        self.assertNotIn("multi_prompt", payload)

    def test_omni_video_image_list_uses_documented_image_url_objects(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._image_to_base64 = MagicMock(side_effect=lambda value: f"encoded:{value}")  # type: ignore[method-assign]
        payload = client._build_video_payload(
            prompt="make <<<image_1>>> cinematic",
            image_size="",
            negative_prompt="",
            model="kling-v3-omni",
            image="",
            image_tail="",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="off",
            callback_url="",
            external_task_id="",
            image_list=[
                "http://backend:8080/generated/uploads/20260617/ref.png",
                {"image_url": "https://example.com/first.png", "type": "first_frame"},
            ],
        )

        self.assertEqual(
            payload["image_list"],
            [
                {"image_url": "encoded:http://backend:8080/generated/uploads/20260617/ref.png"},
                {"image_url": "encoded:https://example.com/first.png", "type": "first_frame"},
            ],
        )

    def test_multi_image_video_payload_uses_documented_image_objects(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._image_to_base64 = MagicMock(side_effect=lambda value: f"encoded:{value}")  # type: ignore[method-assign]
        payload = client._build_video_payload(
            prompt="two characters meet",
            image_size="",
            negative_prompt="",
            model="kling-v1-6",
            image="",
            image_tail="",
            seed=None,
            duration="10",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="off",
            callback_url="",
            external_task_id="",
            image_list=[
                "http://backend:8080/generated/uploads/20260613/a.png",
                {"image": "https://example.com/b.jpg"},
            ],
            multi_shot="false",
            api_task="multi_image2video",
        )

        self.assertEqual(
            payload["image_list"],
            [
                {"image": "encoded:http://backend:8080/generated/uploads/20260613/a.png"},
                {"image": "encoded:https://example.com/b.jpg"},
            ],
        )
        self.assertNotIn("sound", payload)
        self.assertNotIn("multi_shot", payload)
        self.assertNotIn("resolution", payload)

    def test_motion_control_payload_uses_documented_shape(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._image_to_base64 = MagicMock(side_effect=lambda value: f"encoded:{value}")  # type: ignore[method-assign]
        payload = client._build_video_payload(
            prompt="make the character dance",
            image_size="1280x720",
            negative_prompt="ignored",
            model="kling-v3",
            image="/generated/uploads/hero.png",
            image_tail="ignored-tail",
            seed=123,
            duration="10",
            aspect_ratio="16:9",
            resolution="720P",
            mode="std",
            sound="on",
            callback_url="https://example.com/callback",
            external_task_id="motion-1",
            video_url="https://cdn.example.com/dance.mp4",
            character_orientation="image",
            static_mask="/generated/uploads/mask.png",
            dynamic_masks=[{"ignored": True}],
            image_list=["ignored"],
            video_list=["ignored"],
            element_list=[{"element_id": "313650206737230"}],
            multi_shot="true",
            shot_type="customize",
            multi_prompt=["ignored"],
            cfg_scale=0.5,
            keep_original_sound="yes",
            api_task="motion_control",
        )

        self.assertEqual(
            payload,
            {
                "model_name": "kling-v3",
                "image_url": "encoded:/generated/uploads/hero.png",
                "video_url": "https://cdn.example.com/dance.mp4",
                "character_orientation": "video",
                "mode": "std",
                "prompt": "make the character dance",
                "keep_original_sound": "yes",
                "element_list": [{"element_id": 313650206737230}],
                "callback_url": "https://example.com/callback",
                "external_task_id": "motion-1",
            },
        )
        self.assertNotIn("duration", payload)
        self.assertNotIn("aspect_ratio", payload)
        self.assertNotIn("sound", payload)
        self.assertNotIn("static_mask", payload)
        self.assertNotIn("dynamic_masks", payload)

    def test_motion_control_rejects_private_video_url(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        with self.assertRaisesRegex(Exception, "public HTTP"):
            client._build_video_payload(
                prompt="",
                image_size="",
                negative_prompt="",
                model="kling-v3",
                image="/generated/uploads/hero.png",
                image_tail="",
                seed=None,
                duration="",
                aspect_ratio="",
                resolution="",
                mode="std",
                sound="",
                callback_url="",
                external_task_id="",
                video_url="http://backend:8080/generated/uploads/dance.mp4",
                character_orientation="video",
                api_task="motion_control",
            )

    def test_motion_control_rejects_temporary_subject_shapes(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._image_to_base64 = MagicMock(return_value="encoded-image")  # type: ignore[method-assign]
        with self.assertRaisesRegex(Exception, "element_id"):
            client._build_video_payload(
                prompt="",
                image_size="",
                negative_prompt="",
                model="kling-v3",
                image="/generated/uploads/hero.png",
                image_tail="",
                seed=None,
                duration="",
                aspect_ratio="",
                resolution="",
                mode="std",
                sound="",
                callback_url="",
                external_task_id="",
                video_url="https://cdn.example.com/dance.mp4",
                character_orientation="video",
                element_list=[{"frontal_image": "/generated/uploads/front.png"}],
                api_task="motion_control",
            )

    def test_extract_element_id_from_succeed_task_result(self) -> None:
        element_id = KlingVideoClient._extract_element_id(
            {
                "data": {
                    "task_status": "succeed",
                    "task_result": {
                        "elements": [
                            {
                                "element_id": 123456,
                                "element_name": "Hero",
                            }
                        ]
                    },
                }
            }
        )
        self.assertEqual(element_id, "123456")

    def test_delete_element_posts_documented_payload(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._request = MagicMock(return_value={"data": {"task_id": "delete_task"}})  # type: ignore[method-assign]

        response = client.delete_element("elem_123")

        self.assertEqual(response["data"]["task_id"], "delete_task")
        client._request.assert_called_once_with(
            "POST",
            "/v1/general/delete-elements",
            {"element_id": "elem_123"},
        )

    def test_list_element_methods_use_documented_paths(self) -> None:
        client = KlingVideoClient(api_key="test.jwt.token")
        client._request = MagicMock(return_value={"data": []})  # type: ignore[method-assign]

        client.list_custom_elements(page_num=2, page_size=50)
        client.list_preset_elements(page_num=3, page_size=60)

        self.assertEqual(
            client._request.call_args_list[0].args,
            ("GET", "/v1/general/advanced-custom-elements?pageNum=2&pageSize=50", None),
        )
        self.assertEqual(
            client._request.call_args_list[1].args,
            ("GET", "/v1/general/advanced-presets-elements?pageNum=3&pageSize=60", None),
        )


if __name__ == "__main__":
    unittest.main()
