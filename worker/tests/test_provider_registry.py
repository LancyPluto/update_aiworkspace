from providers import registry


def test_model_capability_catalog_does_not_expose_digital_human():
    assert all(
        "DIGITAL_HUMAN" not in registry.provider_capabilities(provider)
        for provider in registry.PROVIDERS
    )

    registry.require_capability("seedance", "VIDEO_GENERATION")
    registry.require_capability("infinitetalk", "VIDEO_GENERATION")


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
