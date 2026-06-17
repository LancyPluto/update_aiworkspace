from __future__ import annotations

import base64
import unittest

from client.kling_video_client import KlingVideoClient
from config import settings
from utils.kling_config import (
    resolve_kling_api_task,
    resolve_kling_image_api_task,
    resolve_kling_image_paths,
    resolve_kling_model_name,
    resolve_kling_video_paths,
)


class KlingConfigTests(unittest.TestCase):
    def test_resolve_model_from_params(self) -> None:
        params = {"model": "kling-v2-6"}
        model_config = {"modelName": "kling-v3"}
        self.assertEqual(resolve_kling_model_name(params, model_config), "kling-v2-6")

    def test_resolve_model_fallback_to_config(self) -> None:
        self.assertEqual(resolve_kling_model_name({}, {"modelName": "kling-v3"}), "kling-v3")

    def test_resolve_motion_control_paths(self) -> None:
        model_config = {
            "extraAuthJson": '{"apiTask":"motion_control"}',
        }
        create_path, result_path = resolve_kling_video_paths(model_config)
        self.assertEqual(create_path, "/v1/videos/motion-control")
        self.assertEqual(result_path, "/v1/videos/motion-control/{task_id}")
        self.assertEqual(resolve_kling_api_task(model_config), "motion_control")

    def test_execution_task_routes_omni_video_before_legacy_extra_auth(self) -> None:
        model_config = {
            "executionTask": "omni_video",
            "extraAuthJson": '{"apiTask":"text2video"}',
        }
        create_path, result_path = resolve_kling_video_paths(model_config)
        self.assertEqual(create_path, "/v1/videos/omni-video")
        self.assertEqual(result_path, "/v1/videos/omni-video/{task_id}")
        self.assertEqual(resolve_kling_api_task(model_config), "omni_video")

    def test_execution_options_override_paths(self) -> None:
        model_config = {
            "executionTask": "text2video",
            "executionOptionsJson": (
                '{"createPath":"/v1/videos/omni-video","resultPath":"/v1/videos/omni-video/{task_id}"}'
            ),
        }
        create_path, result_path = resolve_kling_video_paths(model_config)
        self.assertEqual(create_path, "/v1/videos/omni-video")
        self.assertEqual(result_path, "/v1/videos/omni-video/{task_id}")

    def test_resolve_custom_paths_from_extra_auth(self) -> None:
        model_config = {
            "extraAuthJson": (
                '{"createPath":"/v1/videos/omni-video","resultPath":"/v1/videos/omni-video/{task_id}"}'
            ),
        }
        create_path, result_path = resolve_kling_video_paths(model_config)
        self.assertEqual(create_path, "/v1/videos/omni-video")
        self.assertEqual(result_path, "/v1/videos/omni-video/{task_id}")

    def test_resolve_omni_image_paths(self) -> None:
        model_config = {
            "extraAuthJson": '{"apiTask":"omni_image"}',
        }
        create_path, result_path = resolve_kling_image_paths(model_config)
        self.assertEqual(create_path, "/v1/images/omni-image")
        self.assertEqual(result_path, "/v1/images/omni-image/{task_id}")
        self.assertEqual(resolve_kling_api_task(model_config), "omni_image")

    def test_resolve_image_generation_paths(self) -> None:
        model_config = {
            "extraAuthJson": '{"apiTask":"image_generation"}',
        }
        create_path, result_path = resolve_kling_image_paths(model_config)
        self.assertEqual(create_path, "/v1/images/generations")
        self.assertEqual(result_path, "/v1/images/generations/{task_id}")

    def test_resolve_omni_image_api_task_from_model_param(self) -> None:
        model_config = {
            "extraAuthJson": '{"apiTask":"image_generation"}',
            "modelName": "kling-v3",
        }
        params = {"model": "kling-image-o1"}
        self.assertEqual(resolve_kling_image_api_task(model_config, params), "omni_image")
        create_path, result_path = resolve_kling_image_paths(model_config, params)
        self.assertEqual(create_path, "/v1/images/omni-image")
        self.assertEqual(result_path, "/v1/images/omni-image/{task_id}")

    def test_kling_local_backend_generated_image_is_encoded(self) -> None:
        previous_dir = settings.generated_media_dir
        previous_public_base = settings.generated_media_public_base_url
        try:
            import tempfile
            from pathlib import Path

            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                image_path = root / "uploads" / "20260617" / "ref.png"
                image_path.parent.mkdir(parents=True)
                image_path.write_bytes(b"fake-image")
                settings.generated_media_dir = str(root)
                settings.generated_media_public_base_url = "/generated"

                client = KlingVideoClient(api_key="fake")
                payload = client._build_video_payload(
                    prompt="动起来",
                    image_size="1280x720",
                    negative_prompt="",
                    model="kling-v3",
                    image="http://backend:8080/generated/uploads/20260617/ref.png",
                    image_tail="",
                    seed=None,
                    duration="5",
                    aspect_ratio="16:9",
                    resolution="",
                    mode="",
                    sound="",
                    callback_url="",
                    external_task_id="",
                )

            self.assertEqual(payload["image"], base64.b64encode(b"fake-image").decode("ascii"))
        finally:
            settings.generated_media_dir = previous_dir
            settings.generated_media_public_base_url = previous_public_base


if __name__ == "__main__":
    unittest.main()
