from providers import registry


def test_agnes_providers_are_worker_ready_for_their_capabilities():
    registry.require_capability("local_media_mock", "IMAGE_GENERATION")
    registry.require_capability("local_media_mock", "VIDEO_GENERATION")
    registry.require_worker_ready("local_media_mock")

    registry.require_capability("agnes_chat", "TEXT_GENERATION")
    registry.require_worker_ready("agnes_chat")

    registry.require_capability("agnes_images", "IMAGE_GENERATION")
    registry.require_worker_ready("agnes_images")
    assert registry.provider_protocol("agnes_images") == "openai_images"

    registry.require_capability("agnes_video", "VIDEO_GENERATION")
    registry.require_worker_ready("agnes_video")
    assert registry.provider_protocol("agnes_video") == "agnes_video"
