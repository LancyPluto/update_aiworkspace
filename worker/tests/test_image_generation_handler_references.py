from handlers.image_generation_handler import _resolve_reference_image_sources


def test_openai_image_reference_sources_put_base_before_references():
    params = {
        "base_image_url": "https://example.com/base.png",
        "reference_images": [
            "https://example.com/face.png",
            "https://example.com/style.png",
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/base.png",
        "https://example.com/face.png",
        "https://example.com/style.png",
    ]


def test_openai_image_reference_sources_keep_legacy_image_compatibility():
    params = {
        "image": [
            "https://example.com/legacy-base.png",
            "https://example.com/legacy-ref.png",
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/legacy-base.png",
        "https://example.com/legacy-ref.png",
    ]


def test_openai_image_reference_sources_dedupe_base_and_references():
    params = {
        "baseImageUrl": "https://example.com/a.png",
        "referenceImageUrls": [
            "https://example.com/a.png",
            "https://example.com/b.png",
        ],
        "image": "https://example.com/b.png",
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/a.png",
        "https://example.com/b.png",
    ]
