from __future__ import annotations

import unittest

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


if __name__ == "__main__":
    unittest.main()
