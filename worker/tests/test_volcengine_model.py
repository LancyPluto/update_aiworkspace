from volcengine_model import (
    normalize_volcengine_openai_base_url,
    resolve_volcengine_images_paths,
    resolve_volcengine_model_name,
)


def test_normalize_root_ark_base_url_to_api_v3() -> None:
    assert (
        normalize_volcengine_openai_base_url("https://ark.cn-beijing.volces.com")
        == "https://ark.cn-beijing.volces.com/api/v3"
    )


def test_resolve_images_paths_upgrades_default_ark_paths() -> None:
    endpoint_path, edit_endpoint_path = resolve_volcengine_images_paths(
        "https://ark.cn-beijing.volces.com",
        "/images/generations",
        "/images/edits",
    )
    assert endpoint_path == "/api/v3/images/generations"
    assert edit_endpoint_path == "/api/v3/images/edits"


def test_resolve_images_paths_keeps_custom_paths() -> None:
    endpoint_path, edit_endpoint_path = resolve_volcengine_images_paths(
        "https://ark.cn-beijing.volces.com",
        "/custom/images",
        "/custom/edits",
    )
    assert endpoint_path == "/custom/images"
    assert edit_endpoint_path == "/custom/edits"


def test_resolve_images_paths_skips_api_v3_when_base_already_contains_it() -> None:
    endpoint_path, edit_endpoint_path = resolve_volcengine_images_paths(
        "https://ark.cn-beijing.volces.com/api/v3",
        "/images/generations",
        "/images/edits",
    )
    assert endpoint_path == "/images/generations"
    assert edit_endpoint_path == "/images/edits"


def test_resolve_media_model_aliases_for_ark() -> None:
    base_url = "https://ark.cn-beijing.volces.com/api/v3"

    assert resolve_volcengine_model_name("doubao-seedream-4.5", base_url) == "doubao-seedream-4-5-251128"
    assert resolve_volcengine_model_name("doubao-seedream-5.0-lite", base_url) == "doubao-seedream-5-0-260128"
    assert resolve_volcengine_model_name("doubao-seedance-2.0", base_url) == "doubao-seedance-2-0-260128"
    assert resolve_volcengine_model_name("doubao-seedance-2.0-mini", base_url) == "doubao-seedance-2-0-mini-260615"
